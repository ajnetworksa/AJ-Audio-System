import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'api.dart';
import 'main.dart';
import 'ui.dart';

/// writes text to a temp file and opens the share sheet (WhatsApp, mail, Drive …)
Future<void> shareText(String fileName, String text) async {
  final dir = await getTemporaryDirectory();
  final f = File('${dir.path}/$fileName');
  await f.writeAsString(text);
  await Share.shareXFiles([XFile(f.path, mimeType: 'text/plain')], subject: fileName);
}

/// never put tokens / passwords into a report
String scrub(String s) => s
    .replaceAllMapped(RegExp(r'(Bearer\s+)[A-Za-z0-9._\-]+'), (m) => '${m[1]}***')
    .replaceAllMapped(RegExp(r'("?(token|password|access_token|secret)"?\s*[:=]\s*)"?[^",\s}]+"?', caseSensitive: false),
        (m) => '${m[1]}"***"');

class LogsPage extends StatefulWidget {
  final Session s;
  const LogsPage({super.key, required this.s});
  @override
  State<LogsPage> createState() => _LogsPageState();
}

class _LogsPageState extends State<LogsPage> {
  List<Room> rooms = [];
  int? serverMs;
  final Map<String, int?> panelMs = {};
  bool checking = true;
  bool reporting = false;

  Api get api => widget.s.api;

  @override
  void initState() {
    super.initState();
    check();
  }

  Future<void> check() async {
    setState(() => checking = true);
    try {
      serverMs = await api.ping();
      rooms = (await api.players()).map(Room.new).where((r) => !r.isGroup).toList();
      await Future.wait([
        for (final r in rooms.where((r) => r.isPanel && r.ip != null))
          PanelApi.ping(r.ip!).then((v) => panelMs[r.id] = v),
      ]);
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
    if (mounted) setState(() => checking = false);
  }

  Future<void> fullReport() async {
    setState(() => reporting = true);
    final b = StringBuffer();
    final now = DateTime.now();
    b.writeln('AJ Audio System – diagnostics report');
    b.writeln('Created: $now');
    b.writeln('Server: ${api.base}');
    b.writeln();
    b.writeln('=== CHECKS ===');
    b.writeln('Music server: ${serverMs == null ? 'NOT reachable' : '$serverMs ms'}');
    for (final r in rooms) {
      final ms = panelMs[r.id];
      b.writeln('${r.name.padRight(20)} ${r.online ? 'online ' : 'OFFLINE'} ${r.ip ?? ''} ${r.isPanel ? (ms == null ? 'panel api: no answer' : 'panel api: $ms ms') : ''} state=${r.state}');
    }
    for (final r in rooms.where((r) => r.isPanel && r.ip != null)) {
      b.writeln();
      b.writeln('=== PANEL ${r.name} (${r.ip}) ===');
      final i = await PanelApi.info(r.ip!);
      b.writeln(i == null ? 'info: no answer' : const JsonEncoder.withIndent('  ').convert(i));
      try {
        b.writeln(await PanelApi.log(r.ip!, api.token));
      } catch (e) {
        b.writeln('log: $e');
      }
    }
    b.writeln();
    b.writeln('=== MUSIC ASSISTANT DIAGNOSTICS ===');
    try {
      b.writeln(const JsonEncoder.withIndent('  ').convert(await api.diagnostics()));
    } catch (e) {
      b.writeln('diagnostics: $e');
    }
    b.writeln();
    b.writeln('=== MUSIC ASSISTANT LOG (last 1500 lines) ===');
    try {
      final lines = const LineSplitter().convert(await api.serverLog());
      b.writeln(lines.skip(lines.length > 1500 ? lines.length - 1500 : 0).join('\n'));
    } catch (e) {
      b.writeln('log: $e');
    }
    final stamp = '${now.year}${_2(now.month)}${_2(now.day)}_${_2(now.hour)}${_2(now.minute)}';
    try {
      await shareText('aj_report_$stamp.txt', scrub(b.toString()));
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
    if (mounted) setState(() => reporting = false);
  }

  static String _2(int v) => v.toString().padLeft(2, '0');

  @override
  Widget build(BuildContext context) => AjPage(
        title: 'Logs & diagnostics',
        onRefresh: check,
        children: [
          GradButton(label: 'Make a full report', icon: Icons.summarize_outlined, busy: reporting, onPressed: fullReport),
          const Padding(
            padding: EdgeInsets.fromLTRB(4, 8, 4, 0),
            child: Text('Server log + every panel + network check in one file to share. Never includes passwords or tokens.',
                style: TextStyle(color: C.sub, fontSize: 13, height: 1.4)),
          ),
          const Label('Checks'),
          if (checking) const LinearProgressIndicator(),
          Glass(
            child: Column(children: [
              _check('Music server', serverMs == null ? (checking ? '…' : 'not reachable') : '$serverMs ms', serverMs == null ? C.bad : C.ok),
              for (final r in rooms)
                _check(
                  r.name,
                  !r.online
                      ? 'offline'
                      : r.isPanel
                          ? (panelMs[r.id] == null ? 'panel app not answering' : '${panelMs[r.id]} ms')
                          : 'online',
                  !r.online
                      ? C.bad
                      : r.isPanel && panelMs[r.id] == null
                          ? C.warn
                          : (panelMs[r.id] ?? 0) > 150
                              ? C.warn
                              : C.ok,
                ),
            ]),
          ),
          const Label('Logs'),
          Glass(
            onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => LogView(title: 'Music server log', loader: api.serverLog))),
            child: const Row(children: [
              Icon(Icons.dns_outlined, color: C.sub),
              SizedBox(width: 12),
              Expanded(child: Text('Music server log', style: TextStyle(fontSize: 15.5))),
              Icon(Icons.chevron_right, color: C.sub),
            ]),
          ),
          for (final r in rooms.where((r) => r.isPanel && r.ip != null))
            Glass(
              onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => LogView(title: '${r.name} log', loader: () => PanelApi.log(r.ip!, api.token)))),
              child: Row(children: [
                const Icon(Icons.tablet_outlined, color: C.sub),
                const SizedBox(width: 12),
                Expanded(child: Text('${r.name} panel', style: const TextStyle(fontSize: 15.5))),
                const Icon(Icons.chevron_right, color: C.sub),
              ]),
            ),
        ],
      );

  Widget _check(String name, String value, Color color) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(children: [
          Dot(color),
          const SizedBox(width: 10),
          Expanded(child: Text(name, style: const TextStyle(fontSize: 15))),
          Text(value, style: TextStyle(color: color, fontSize: 14)),
        ]),
      );
}

/// scrollable log text with share + "errors only" filter
class LogView extends StatefulWidget {
  final String title;
  final Future<String> Function() loader;
  const LogView({super.key, required this.title, required this.loader});
  @override
  State<LogView> createState() => _LogViewState();
}

class _LogViewState extends State<LogView> {
  String text = '';
  bool loading = true;
  bool errorsOnly = false;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() => loading = true);
    try {
      text = scrub(await widget.loader());
    } catch (e) {
      text = 'Could not load: $e';
    }
    if (mounted) setState(() => loading = false);
  }

  @override
  Widget build(BuildContext context) {
    var lines = const LineSplitter().convert(text);
    if (errorsOnly) lines = lines.where((l) => RegExp(r'error|warn|exception|fail|refused', caseSensitive: false).hasMatch(l)).toList();
    final shown = lines.length > 3000 ? lines.sublist(lines.length - 3000) : lines;
    return Backdrop(
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.title),
          actions: [
            IconButton(onPressed: load, icon: const Icon(Icons.refresh), tooltip: 'Reload'),
            IconButton(
              onPressed: text.isEmpty
                  ? null
                  : () {
                      Clipboard.setData(ClipboardData(text: text));
                      toast(context, 'Log copied');
                    },
              icon: const Icon(Icons.copy_all_outlined),
              tooltip: 'Copy all',
            ),
            IconButton(
              onPressed: text.isEmpty ? null : () => shareText('${widget.title.replaceAll(' ', '_')}.txt', text),
              icon: const Icon(Icons.share_outlined),
              tooltip: 'Share',
            ),
          ],
        ),
        body: Column(children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: SettingRow(label: 'Only errors and warnings', trailing: Switch(value: errorsOnly, onChanged: (v) => setState(() => errorsOnly = v))),
          ),
          if (loading) const LinearProgressIndicator(),
          Expanded(
            child: Container(
              margin: const EdgeInsets.fromLTRB(12, 4, 12, 12),
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(color: const Color(0xFF0B1018), borderRadius: BorderRadius.circular(14), border: Border.all(color: const Color(0xFF263248))),
              // long-press to select and copy lines (SelectionArea), or "Copy all" in the bar
              child: SelectionArea(child: ListView.builder(
                reverse: true,
                itemCount: shown.length,
                itemBuilder: (_, i) {
                  final l = shown[shown.length - 1 - i];
                  final bad = RegExp(r'error|exception|fail|refused', caseSensitive: false).hasMatch(l);
                  final warn = !bad && RegExp(r'warn', caseSensitive: false).hasMatch(l);
                  return Text(l,
                      style: TextStyle(fontFamily: 'monospace', fontSize: 11.5, height: 1.35, color: bad ? C.bad : warn ? C.warn : C.sub));
                },
              )),
            ),
          ),
        ]),
      ),
    );
  }
}
