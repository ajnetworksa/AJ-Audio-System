import 'package:flutter/material.dart';
import 'api.dart';
import 'main.dart';
import 'ui.dart';
import 'logs.dart';

/// Find panels on the network, see their address / version / state, and connect them to this music server.
class ScanPage extends StatefulWidget {
  final Session s;
  const ScanPage({super.key, required this.s});
  @override
  State<ScanPage> createState() => _ScanPageState();
}

class _ScanPageState extends State<ScanPage> {
  List<Found> found = [];
  bool scanning = false;
  double progress = 0;
  String note = '';
  final Set<String> busy = {};

  Api get api => widget.s.api;

  @override
  void initState() {
    super.initState();
    scan();
  }

  Future<void> scan() async {
    if (scanning) return;
    setState(() { scanning = true; progress = 0; note = ''; });
    try {
      final nets = await Net.prefixes();
      // panels Music Assistant already knows are tried too (they may sit in another subnet)
      final known = <String>[];
      try {
        for (final p in await api.players()) {
          final r = Room(p);
          if (r.ip != null) known.add(r.ip!);
        }
      } catch (_) {}
      if (nets.isEmpty && known.isEmpty) {
        note = 'This phone is not on Wi-Fi. Connect it to the same network as the panels and scan again.';
      }
      final r = await Net.scan(
        extra: known,
        progress: (d, t) { if (mounted) setState(() => progress = t == 0 ? 0 : d / t); },
      );
      if (mounted) setState(() => found = r);
    } catch (e) {
      note = '$e';
    }
    if (mounted) setState(() => scanning = false);
  }

  /// does this panel already use our music server?
  bool onThisServer(Found f) => f.server == api.host && f.loggedIn && f.serverOk;

  Future<bool> adopt(Found f, {String? name}) async {
    setState(() => busy.add(f.ip));
    var ok = false;
    try {
      final n = (name ?? f.name).trim();
      final token = await api.panelToken(n.isEmpty ? f.ip : n);
      final port = Uri.parse(api.base).port;
      final repair = await api.repairToken();
      await PanelApi.post(f.ip, api.token, '/api/settings', {
        'host': api.host,
        'ma_port': '$port',
        'token': token,
        if (repair != null) 'repair_token': repair,
        if (n.isNotEmpty) 'name': n,
      });
      ok = true;
    } catch (e) {
      if (mounted) {
        toast(context, e is ApiException && e.code == 401
            ? '${f.name}: this panel is connected to another music server. Sign in to that server to move it, or reset the panel app.'
            : '${f.name}: $e');
      }
    }
    if (mounted) setState(() => busy.remove(f.ip));
    return ok;
  }

  Future<void> adoptAll() async {
    final todo = found.where((f) => f.hasApp && !onThisServer(f)).toList();
    if (todo.isEmpty) return;
    if (!await confirm(context, 'Connect ${todo.length} panels?', 'They will play from the music server ${api.host}.', ok: 'Connect')) return;
    var n = 0;
    for (final f in todo) {
      if (await adopt(f)) n++;
    }
    if (mounted) toast(context, '$n of ${todo.length} panels connected. Scanning again…');
    await Future.delayed(const Duration(seconds: 4));
    scan();
  }

  Future<void> setup(Found f) async {
    final name = TextEditingController(text: f.name == f.ip ? '' : f.name);
    final go = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        backgroundColor: const Color(0xFF1A2333),
        title: Text(onThisServer(f) ? 'Panel ${f.ip}' : 'Connect this panel'),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${f.info?['model'] ?? ''} · Android ${f.info?['android'] ?? '?'} · AJ Panel v${f.version}', style: const TextStyle(color: C.sub, fontSize: 13)),
          const SizedBox(height: 4),
          Text('${f.info?['status'] ?? ''}', style: const TextStyle(color: C.sub, fontSize: 13)),
          const SizedBox(height: 14),
          TextField(controller: name, decoration: const InputDecoration(labelText: 'Room name')),
          const SizedBox(height: 10),
          Text('Music server: ${api.host}', style: const TextStyle(color: C.sub)),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(onThisServer(f) ? 'Save' : 'Connect')),
        ],
      ),
    );
    if (go != true) return;
    if (await adopt(f, name: name.text)) {
      if (mounted) toast(context, 'Done – the panel appears under Rooms in a few seconds');
      await Future.delayed(const Duration(seconds: 3));
      scan();
    }
  }

  @override
  Widget build(BuildContext context) {
    final withApp = found.where((f) => f.hasApp).toList();
    final noApp = found.where((f) => !f.hasApp).toList();
    final todo = withApp.where((f) => !onThisServer(f)).length;
    return AjPage(
      title: 'Find & set up panels',
      subtitle: 'Looks at every address on this Wi-Fi network. Panels with the AJ Panel app answer with their details.',
      onRefresh: scan,
      action: IconButton(onPressed: scanning ? null : scan, icon: const Icon(Icons.radar), tooltip: 'Scan again'),
      bottom: todo == 0 ? null : GradButton(label: 'Connect all $todo to this server', icon: Icons.link, onPressed: adoptAll),
      children: [
        if (scanning) ...[
          LinearProgressIndicator(value: progress == 0 ? null : progress),
          Padding(padding: const EdgeInsets.only(top: 6, bottom: 8), child: Text('Scanning… ${(progress * 100).round()} %', style: const TextStyle(color: C.sub, fontSize: 13))),
        ],
        if (note.isNotEmpty) Glass(child: Text(note, style: const TextStyle(color: C.warn))),
        if (!scanning && found.isEmpty && note.isEmpty)
          const Glass(child: Text('No panels found. Check that the panels are switched on and on the same Wi-Fi as this phone.', style: TextStyle(height: 1.4))),
        if (withApp.isNotEmpty) Label('${withApp.length} panels with AJ Panel'),
        for (final f in withApp)
          Glass(
            onTap: busy.contains(f.ip) ? null : () => setup(f),
            child: Row(children: [
              Dot(onThisServer(f) ? (f.connected ? C.ok : C.warn) : C.bad),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(f.name, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                  Text('${f.ip} · v${f.version} · Android ${f.info?['android'] ?? '?'} · ${f.info?['model'] ?? ''}',
                      maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: C.sub, fontSize: 13)),
                  Text(
                    onThisServer(f)
                        ? (f.connected ? 'Connected to this server' : 'On this server – player not connected yet')
                        : f.server.isEmpty || !f.loggedIn
                            ? 'Not set up yet – tap to connect'
                            : 'Uses another server (${f.server}) – tap to connect here',
                    style: TextStyle(color: onThisServer(f) ? (f.connected ? C.ok : C.warn) : C.warn, fontSize: 13),
                  ),
                  if (f.info?['crashed'] == true) const Text('The app crashed earlier – see its log', style: TextStyle(color: C.bad, fontSize: 13)),
                ]),
              ),
              if (busy.contains(f.ip))
                const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5))
              else
                IconButton(
                  tooltip: 'Panel log',
                  icon: const Icon(Icons.article_outlined, color: C.sub),
                  onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => LogView(title: '${f.name} log', loader: () => PanelApi.log(f.ip, api.token)))),
                ),
            ]),
          ),
        if (noApp.isNotEmpty) ...[
          Label('${noApp.length} Android devices without AJ Panel'),
          const Padding(
            padding: EdgeInsets.fromLTRB(2, 0, 2, 8),
            child: Text('These have the network debug port open, like a new panel. The first install is done once from a PC: tools\\deploy_ajpanel.bat -panels "ADDRESS=Room name".',
                style: TextStyle(color: C.sub, fontSize: 13, height: 1.4)),
          ),
          for (final f in noApp)
            Glass(
              child: Row(children: [
                const Dot(C.sub),
                const SizedBox(width: 12),
                Expanded(child: Text(f.ip, style: const TextStyle(fontSize: 16))),
                const Text('no AJ Panel', style: TextStyle(color: C.sub, fontSize: 13)),
              ]),
            ),
        ],
      ],
    );
  }
}
