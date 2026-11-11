import 'package:flutter/material.dart';
import 'api.dart';
import 'main.dart';
import 'ui.dart';
import 'logs.dart';

class RoomsPage extends StatefulWidget {
  final Session s;
  const RoomsPage({super.key, required this.s});
  @override
  State<RoomsPage> createState() => _RoomsPageState();
}

class _RoomsPageState extends State<RoomsPage> {
  List<Room> rooms = [];
  Set<String> airplay = {};
  bool loading = true;
  String error = '';

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final p = await widget.s.api.players();
      final a = await widget.s.api.airplayPlayers();
      setState(() { rooms = p.map(Room.new).where((r) => !r.isGroup).toList(); airplay = a; loading = false; error = ''; });
    } catch (e) {
      setState(() { error = '$e'; loading = false; });
    }
  }

  @override
  Widget build(BuildContext context) => AjPage(
        title: 'Rooms & panels',
        subtitle: 'Every speaker Music Assistant knows. AJ panels show their app version and can be updated from here.',
        onRefresh: load,
        children: [
          if (loading) const LinearProgressIndicator(),
          if (error.isNotEmpty) Text(error, style: const TextStyle(color: C.bad)),
          for (final r in rooms)
            Glass(
              onTap: () async {
                await Navigator.push(context, MaterialPageRoute(builder: (_) => RoomDetail(s: widget.s, room: r)));
                load();
              },
              child: Row(children: [
                Dot(r.online ? C.ok : C.bad),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(r.name, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                    Text(
                      [
                        r.isPanel ? 'AJ panel' : (r.model.isEmpty ? 'speaker' : r.model),
                        if (r.ip != null) r.ip!,
                        r.online ? (r.state == 'idle' ? 'idle' : '${r.state}${r.title.isEmpty ? '' : ' · ${r.title}'}') : 'offline',
                        if (airplay.contains(r.id)) 'AirPlay on',
                      ].join(' · '),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: C.sub, fontSize: 13),
                    ),
                  ]),
                ),
                const Icon(Icons.chevron_right, color: C.sub),
              ]),
            ),
        ],
      );
}

// ======================================================= one room
class RoomDetail extends StatefulWidget {
  final Session s;
  final Room room;
  const RoomDetail({super.key, required this.s, required this.room});
  @override
  State<RoomDetail> createState() => _RoomDetailState();
}

class _RoomDetailState extends State<RoomDetail> {
  late final name = TextEditingController(text: widget.room.name);
  Map<String, dynamic>? info; // panel /api/info
  int? pingMs;
  bool airplay = false;
  double maxVol = 100;
  double delay = 0;
  bool showCard = true;
  bool busy = false;
  (String, String)? latest; // newest panel version on GitHub

  Room get r => widget.room;
  Api get api => widget.s.api;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    final a = await api.airplayPlayers();
    try {
      final v = await api.cmd('config/players/get_value', {'player_id': r.id, 'key': 'max_volume'});
      if (v is num) maxVol = v.toDouble();
    } catch (_) {}
    Map<String, dynamic>? i;
    int? ms;
    if (r.ip != null && r.isPanel) {
      ms = await PanelApi.ping(r.ip!);
      i = await PanelApi.info(r.ip!);
    }
    final rel = r.isPanel ? await Releases.latestPanel(widget.s.repo) : null;
    if (!mounted) return;
    setState(() {
      airplay = a.contains(r.id);
      info = i;
      pingMs = ms;
      latest = rel;
      if (i != null) {
        delay = ((i['latency'] ?? 0) as num).toDouble();
        showCard = i['overlay'] == true;
      }
    });
  }

  Future<void> run(String done, Future<void> Function() f) async {
    setState(() => busy = true);
    try {
      await f();
      if (mounted) toast(context, done);
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
    if (mounted) setState(() => busy = false);
  }

  Future<void> save() => run('Saved', () async {
        final n = name.text.trim();
        final values = <String, dynamic>{};
        if (n.isNotEmpty && n != r.name) values['name'] = n;
        values['max_volume'] = maxVol.round();
        await api.saveConfig(r.id, values);
        if (info != null && r.ip != null) {
          await PanelApi.post(r.ip!, api.token, '/api/settings', {
            'latency': '${delay.round()}',
            'overlay': '$showCard',
            if (n.isNotEmpty) 'name': n,
          });
        }
      });

  String get wifi {
    if (pingMs == null) return '–';
    if (pingMs! < 30) return '$pingMs ms · good';
    if (pingMs! < 120) return '$pingMs ms · ok';
    return '$pingMs ms · weak';
  }

  bool get updateReady {
    if (latest == null || info == null) return false;
    return _newer(latest!.$1, '${info!['version']}');
  }

  static bool _newer(String a, String b) {
    final x = a.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    final y = b.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    for (var i = 0; i < 3; i++) {
      final p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
      if (p != q) return p > q;
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    final panel = r.isPanel && r.ip != null;
    return AjPage(
      title: r.name,
      subtitle: r.online ? (panel ? 'Online · AJ panel' : 'Online') : 'Offline – check power and Wi-Fi',
      bottom: GradButton(label: 'Save', icon: Icons.check, busy: busy, onPressed: save),
      children: [
        Glass(
          child: TextField(controller: name, decoration: const InputDecoration(labelText: 'Room name (also its AirPlay name)')),
        ),
        Glass(
          child: Wrap(runSpacing: 10, children: [
            _info('Device', panel ? '${info?['model'] ?? 'AJ panel'}' : (r.model.isEmpty ? 'Speaker' : r.model)),
            _info('Address', r.ip ?? '–'),
            _info('AJ Panel', info == null ? (panel ? 'not reachable' : '–') : 'v${info!['version']}${updateReady ? ' · update v${latest!.$1}' : ''}'),
            _info('Network', panel ? wifi : '–'),
            if (info != null) _info('Sync player', info!['sync_connected'] == true ? 'connected' : 'not connected'),
            if (info != null) _info('Restarts', '${info!['sync_restarts']}'),
          ]),
        ),
        Glass(
          child: Column(children: [
            SettingRow(
              label: 'AirPlay receiver',
              hint: 'iPhones see "${name.text} | Music Assistant"',
              trailing: Switch(
                value: airplay,
                onChanged: (v) => run(v ? 'AirPlay on' : 'AirPlay off', () async {
                  await api.setAirplay(r.id, v);
                  setState(() => airplay = v);
                }),
              ),
            ),
            if (panel)
              SettingRow(
                label: 'Now-playing card on the panel',
                trailing: Switch(value: showCard, onChanged: (v) => setState(() => showCard = v)),
              ),
            const Divider(color: C.line),
            SettingRow(label: 'Max volume', trailing: Text('${maxVol.round()} %', style: const TextStyle(color: C.sub))),
            Slider(value: maxVol.clamp(10, 100).toDouble(), min: 10, max: 100, divisions: 18, onChanged: (v) => setState(() => maxVol = v)),
            if (panel) ...[
              SettingRow(
                label: 'Sound delay trim',
                hint: 'if this room sounds early/late vs. the others',
                trailing: Text('${delay.round() > 0 ? '+' : ''}${delay.round()} ms', style: const TextStyle(color: C.sub)),
              ),
              Slider(value: delay.clamp(-200, 200).toDouble(), min: -200, max: 200, divisions: 40, onChanged: (v) => setState(() => delay = v)),
            ],
          ]),
        ),
        const Label('Actions'),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 10,
          crossAxisSpacing: 10,
          childAspectRatio: 3.0,
          children: [
            OutlineBtn(
              label: 'Test sound',
              icon: Icons.notifications_active_outlined,
              onPressed: !panel ? null : () => run('Playing a chime – listen in ${r.name}', () => api.announce(r.id, PanelApi.chimeUrl(r.ip!))),
            ),
            OutlineBtn(
              label: 'Restart player',
              icon: Icons.restart_alt,
              onPressed: info == null ? null : () => run('Player restarting', () => PanelApi.post(r.ip!, api.token, '/api/restart')),
            ),
            OutlineBtn(
              label: 'Panel log',
              icon: Icons.article_outlined,
              onPressed: info == null
                  ? null
                  : () => Navigator.push(context, MaterialPageRoute(builder: (_) => LogView(title: '${r.name} log', loader: () => PanelApi.log(r.ip!, api.token)))),
            ),
            OutlineBtn(
              label: updateReady ? 'Update to v${latest!.$1}' : 'Up to date',
              icon: Icons.system_update_alt,
              onPressed: !updateReady
                  ? null
                  : () async {
                      if (!await confirm(context, 'Update ${r.name}?',
                          'The panel downloads AJ Panel v${latest!.$1}. Someone must tap "Install" on the panel screen once.',
                          ok: 'Update')) return;
                      await run('Update sent – tap Install on the panel', () => PanelApi.post(r.ip!, api.token, '/api/update', {'url': latest!.$2}));
                    },
            ),
          ],
        ),
      ],
    );
  }

  Widget _info(String k, String v) => SizedBox(
        width: 150,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(k, style: const TextStyle(color: C.sub, fontSize: 12)),
          Text(v, style: const TextStyle(fontSize: 14.5)),
        ]),
      );
}
