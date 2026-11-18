import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'api.dart';
import 'tools.dart';
import 'auto_update.dart';
import 'main.dart';
import 'ui.dart';

/// Update many panels at once: newest release from GitHub, or an APK file picked on this phone.
class BulkUpdatePage extends StatefulWidget {
  final Session s;
  const BulkUpdatePage({super.key, required this.s});
  @override
  State<BulkUpdatePage> createState() => _BulkUpdatePageState();
}

class _BulkUpdatePageState extends State<BulkUpdatePage> {
  List<Found> panels = [];
  final Set<String> picked = {};
  final Map<String, String> result = {}; // ip -> message
  bool loading = true, running = false;
  (String, String)? latest; // version, url on GitHub
  String? filePath, fileName;
  bool useFile = false;

  Api get api => widget.s.api;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() => loading = true);
    try {
      final known = <String>[];
      try {
        for (final p in await api.players()) {
          final r = Room(p);
          if (r.ip != null) known.add(r.ip!);
        }
      } catch (_) {}
      final rel = await Releases.latestPanel(widget.s.repo);
      final found = (await Net.scan(extra: known)).where((f) => f.hasApp).toList();
      if (!mounted) return;
      setState(() {
        panels = found;
        latest = rel;
        picked
          ..clear()
          ..addAll(found.where(needs).map((f) => f.ip));
      });
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
    if (mounted) setState(() => loading = false);
  }

  String _autoLine() {
    final withInfo = panels.where((f) => f.info?['auto_update'] != null).toList();
    if (withInfo.isEmpty) return 'Panels install fixes by themselves at night – set the schedule';
    final onN = withInfo.where((f) => f.info?['auto_update'] == true).length;
    return onN == 0 ? 'Off on all panels' : 'On for $onN of ${panels.length} panels · ${withInfo.first.info?['update_window']}';
  }

  /// older than the newest release?
  bool needs(Found f) => latest != null && newerVersion(latest!.$1, f.version);

  Future<void> pickFile() async {
    try {
      final r = await FilePicker.platform.pickFiles(type: FileType.any);
      final path = r?.files.single.path;
      if (path == null) return;
      if (!path.toLowerCase().endsWith('.apk')) {
        if (mounted) toast(context, 'Pick an .apk file');
        return;
      }
      setState(() { filePath = path; fileName = r!.files.single.name; useFile = true; });
    } catch (e) {
      if (mounted) toast(context, 'Could not open the file: $e');
    }
  }

  Future<void> run() async {
    final todo = panels.where((f) => picked.contains(f.ip)).toList();
    if (todo.isEmpty) return;
    final what = useFile ? fileName ?? 'the picked file' : 'AJ Panel v${latest!.$1}';
    if (!await confirm(context, 'Update ${todo.length} panels?',
        'Installs $what on each panel over the network – nobody has to tap anything. '
        '(A panel that never allowed this phone shows "Allow debugging?" once: tick "Always allow".)', ok: 'Update')) return;
    setState(() { running = true; result.clear(); });
    String? path = useFile ? filePath : null;
    Uint8List? bytes;
    try {
      if (path == null) {
        setState(() => result['*'] = 'downloading v${latest!.$1} …');
        final r = await http.get(Uri.parse(latest!.$2)).timeout(const Duration(minutes: 3));
        if (r.statusCode != 200) throw 'download failed (${r.statusCode})';
        bytes = r.bodyBytes;
        final f = File('${(await getTemporaryDirectory()).path}/AJ_Panel_v${latest!.$1}.apk');
        await f.writeAsBytes(bytes);
        path = f.path;
      } else {
        bytes = await File(path).readAsBytes();
      }
    } catch (e) {
      if (mounted) toast(context, 'Could not get the APK: $e');
      setState(() { running = false; result.remove('*'); });
      return;
    }
    result.remove('*');
    for (final f in todo) {
      setState(() => result[f.ip] = 'installing…');
      try {
        // 1st choice: install over the network debug port – silent, no tap on the panel
        final r = await Adb.install(f.ip, path!);
        if (!r.contains('Success')) throw r.trim().isEmpty ? 'no answer' : r.trim();
        // and make later updates (also the panel's own GitHub updates) silent too
        try { await Adb.sh(f.ip, 'dpm set-device-owner com.ajnetwork.ajpanel/.AdminReceiver 2>&1'); } catch (_) {}
        result[f.ip] = 'installed ✓ (restarts by itself)';
      } catch (adbErr) {
        // fallback: hand the APK to AJ Panel's own installer (panel shows "Install" unless silent updates are on)
        try {
          await PanelApi.upload(f.ip, api.token, bytes!);
          result[f.ip] = f.info?['silent_update'] == true ? 'sent – installs by itself (≈1 min)' : 'sent – tap Install on the panel (network install not possible: $adbErr)';
        } catch (e) {
          result[f.ip] = e is ApiException && e.code == 401 ? 'not connected to this server (set it up first)' : 'failed: $e';
        }
      }
      if (mounted) setState(() {});
    }
    if (mounted) setState(() => running = false);
  }

  @override
  Widget build(BuildContext context) {
    final canRun = !running && picked.isNotEmpty && (useFile ? filePath != null : latest != null);
    return AjPage(
      title: 'Update panels',
      subtitle: 'Pick what to install and which panels get it. Installs over the network – no tap on the panels.',
      onRefresh: load,
      bottom: GradButton(
        label: picked.isEmpty ? 'Pick panels' : 'Update ${picked.length} panels',
        icon: Icons.system_update_alt,
        busy: running,
        onPressed: canRun ? run : null,
      ),
      children: [
        Glass(
          onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => AutoUpdatePage(s: widget.s, panels: panels))).then((_) => load()),
          child: Row(children: [
            const IconBadge(Icons.schedule),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Automatic updates', style: TextStyle(fontSize: 16.5, fontWeight: FontWeight.w600)),
                Text(_autoLine(), style: const TextStyle(color: C.sub, fontSize: 13)),
              ]),
            ),
            const Icon(Icons.chevron_right, color: C.sub),
          ]),
        ),
        const Label('Update now'),
        const Label('What to install'),
        Glass(
          selected: !useFile,
          onTap: () => setState(() => useFile = false),
          child: Row(children: [
            const IconBadge(Icons.cloud_download_outlined),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Newest release', style: TextStyle(fontSize: 16.5, fontWeight: FontWeight.w600)),
                Text(latest == null ? (loading ? 'Checking GitHub…' : 'No release found (${widget.s.repo}) – needs internet') : 'AJ Panel v${latest!.$1} from GitHub',
                    style: const TextStyle(color: C.sub, fontSize: 13)),
              ]),
            ),
          ]),
        ),
        Glass(
          selected: useFile,
          onTap: pickFile,
          child: Row(children: [
            const IconBadge(Icons.folder_open_outlined),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('An APK file on this phone', style: TextStyle(fontSize: 16.5, fontWeight: FontWeight.w600)),
                Text(fileName ?? 'Tap to choose (any app, e.g. an older AJ Panel)', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: C.sub, fontSize: 13)),
              ]),
            ),
          ]),
        ),
        Row(children: [
          const Expanded(child: Label('Panels')),
          TextButton(
            onPressed: panels.isEmpty
                ? null
                : () => setState(() {
                      if (picked.length == panels.length) {
                        picked.clear();
                      } else {
                        picked.addAll(panels.map((f) => f.ip));
                      }
                    }),
            child: Text(picked.length == panels.length && panels.isNotEmpty ? 'None' : 'All'),
          ),
        ]),
        if (loading) const LinearProgressIndicator(),
        if (!loading && panels.isEmpty) const Glass(child: Text('No panels with AJ Panel found on this network.')),
        for (final f in panels)
          Glass(
            onTap: running
                ? null
                : () => setState(() {
                      if (!picked.remove(f.ip)) picked.add(f.ip);
                    }),
            child: Row(children: [
              Icon(picked.contains(f.ip) ? Icons.check_circle : Icons.radio_button_unchecked, color: picked.contains(f.ip) ? C.accent : C.sub),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(f.name, style: const TextStyle(fontSize: 16.5, fontWeight: FontWeight.w600)),
                  Text('${f.ip} · v${f.version}${needs(f) ? ' → v${latest!.$1}' : latest != null ? ' · up to date' : ''}',
                      style: TextStyle(color: needs(f) ? C.warn : C.sub, fontSize: 13)),
                  if (result[f.ip] != null)
                    Text(result[f.ip]!, style: TextStyle(color: RegExp(r'^(sent|installed)').hasMatch(result[f.ip]!) ? C.ok : RegExp(r'^(installing|sending|downloading)').hasMatch(result[f.ip]!) ? C.sub : C.bad, fontSize: 13)),
                ]),
              ),
            ]),
          ),
      ],
    );
  }
}
