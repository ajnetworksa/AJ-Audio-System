import 'dart:async';
import 'dart:io' show Platform;
import 'package:flutter/material.dart';
import 'ma.dart';
import 'capture.dart';
import 'updater.dart';

const _accent = Color(0xFF6BBC46); // brand green

/// Home: groups on top (saved + temporary), rooms (single panels/speakers) below.
class HomePage extends StatefulWidget {
  final Ma ma;
  final VoidCallback onLogout;
  const HomePage({super.key, required this.ma, required this.onLogout});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  List<Map<String, dynamic>> players = [];
  Map<String, Map<String, dynamic>> aj = {};
  Set<String> airplay = {};
  final Map<String, double> dragging = {};
  String error = '';
  Timer? timer;
  int tick = 0;
  // play my phone
  Map<String, dynamic> cap = {'running': false};
  String? capTarget;
  bool muteOn = true;

  @override
  void initState() {
    super.initState();
    PhoneCapture.status().then((st) async {
      final m = st['mute'] != false;
      await PhoneCapture.configure(server: widget.ma.base, token: widget.ma.token, mute: m);
      if (mounted) setState(() { cap = st; muteOn = m; });
    });
    refresh();
    timer = Timer.periodic(const Duration(seconds: 2), (_) => refresh());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> refresh() async {
    try {
      final all = await widget.ma.players();
      final a = await widget.ma.ajPanels();
      if (tick++ % 10 == 0) airplay = await widget.ma.airplayReceiverPlayers();
      if (PhoneCapture.supported) cap = await PhoneCapture.status();
      // hidden/disabled players and Music Assistant's own AirPlay receivers found again by its AirPlay provider
      all.removeWhere((p) => p['hide_in_ui'] == true || p['enabled'] == false || '${p['name']}'.endsWith('| Music Assistant'));
      all.sort((x, y) => '${x['name']}'.toLowerCase().compareTo('${y['name']}'.toLowerCase()));
      if (mounted) setState(() { players = all; aj = a; error = ''; });
    } on MaException catch (e) {
      if (e.code == 401) widget.onLogout();
      if (mounted) setState(() => error = e.toString());
    } catch (e) {
      if (mounted) setState(() => error = 'Server not reachable: $e');
    }
  }

  // ---------- model helpers ----------
  Map<String, dynamic>? byId(String? id) {
    for (final p in players) {
      if (p['player_id'] == id) return p;
    }
    return null;
  }

  bool isGroupPlayer(Map<String, dynamic> p) => p['type'] == 'group';
  List<String> members(Map<String, dynamic> p) =>
      [for (final m in (p['group_members'] as List? ?? [])) m.toString()];
  List<Map<String, dynamic>> get savedGroups => players.where(isGroupPlayer).toList();
  /// temporary group = a normal player that leads other players
  List<Map<String, dynamic>> get tempGroups => players
      .where((p) => !isGroupPlayer(p) && p['synced_to'] == null && members(p).length > 1)
      .toList();
  List<Map<String, dynamic>> get rooms => players.where((p) => !isGroupPlayer(p)).toList();
  String nameOf(String id) => (byId(id)?['name'] ?? id).toString();

  void toast(String m) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));
  }

  Future<void> run(Future<void> Function() f, {String? ok}) async {
    try {
      await f();
      if (ok != null) toast(ok);
    } on MaException catch (e) {
      if (e.code == 403) {
        toast('Your Music Assistant user is not allowed to do this (needs an admin user).');
      } else {
        toast(e.message.length > 200 ? e.message.substring(0, 200) : e.message);
      }
    } catch (e) {
      toast('$e');
    }
    await refresh();
  }

  /// play/pause, but explain instead of "500" when nothing is queued yet
  Future<void> playPause(Map<String, dynamic> p) async {
    final idle = (p['playback_state'] ?? 'idle') == 'idle' && p['current_media'] == null;
    if (idle) {
      showNothingQueued(p);
      return;
    }
    await run(() => widget.ma.simple('play_pause', p['player_id']));
  }

  void showNothingQueued(Map<String, dynamic> p) {
    final name = p['name'];
    showDialog(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Nothing to play yet'),
        content: Text(Platform.isIOS
            ? 'Start music on your iPhone: Control Centre → AirPlay → "$name | Music Assistant".\n\n'
                'Or pick music in the Music Assistant web page for "$name".'
            : 'Use "Play my phone" above (any app on this phone → $name), pick music in Music Assistant or on a panel\'s Music page, '
                'or AirPlay to "$name | Music Assistant" from an iPhone.'),
        actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('OK'))],
      ),
    );
  }

  // ---------- new / edit group ----------
  Future<void> newGroup({Map<String, dynamic>? edit}) async {
    final nameCtl = TextEditingController(text: edit?['name']?.toString() ?? '');
    final chosen = <String>{...?(edit == null ? null : members(edit))};
    bool saved = true;
    final ok = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => Padding(
          padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + MediaQuery.of(c).viewInsets.bottom),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(edit == null ? 'New group' : 'Edit ${edit['name']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
            if (edit == null)
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Save with a name'),
                subtitle: Text(saved ? 'Stays in the list, gets its own AirPlay name' : 'Temporary: until you ungroup'),
                value: saved,
                onChanged: (v) => set(() => saved = v),
              ),
            if (edit == null && saved)
              TextField(controller: nameCtl, decoration: const InputDecoration(labelText: 'Group name (e.g. Whole House)')),
            const SizedBox(height: 8),
            const Text('Rooms', style: TextStyle(color: Colors.white70)),
            ConstrainedBox(
              constraints: BoxConstraints(maxHeight: MediaQuery.of(c).size.height * 0.45),
              child: ListView(shrinkWrap: true, children: [
                for (final r in rooms)
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    value: chosen.contains(r['player_id']),
                    title: Text('${r['name']}'),
                    subtitle: r['available'] == false ? const Text('offline') : null,
                    onChanged: (v) => set(() => v == true ? chosen.add(r['player_id']) : chosen.remove(r['player_id'])),
                  ),
              ]),
            ),
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: chosen.length < (edit == null && !saved ? 2 : 1) ? null : () => Navigator.pop(c, true),
                child: Text(edit != null ? 'Save' : (saved ? 'Create group' : 'Group now')),
              ),
            ),
          ]),
        ),
      ),
    );
    if (ok != true) return;
    final list = chosen.toList();
    if (edit != null) {
      final old = members(edit).toSet();
      await run(() => widget.ma.setMembers(edit['player_id'],
          add: list.where((x) => !old.contains(x)).toList(),
          remove: old.where((x) => !chosen.contains(x)).toList()), ok: 'Group updated');
      return;
    }
    if (!saved) {
      await run(() => widget.ma.setMembers(list.first, add: list.sublist(1)), ok: 'Grouped ${list.length} rooms');
      return;
    }
    final name = nameCtl.text.trim().isEmpty ? list.map(nameOf).join(' + ') : nameCtl.text.trim();
    await run(() async {
      final g = await widget.ma.createGroup(name, list);
      final id = g?['player_id']?.toString();
      // every saved group gets an AirPlay receiver by default
      if (id != null) {
        try {
          await widget.ma.setAirplayReceiver(id, true);
        } catch (_) {
          toast('Group created; AirPlay receiver could not be enabled (admin needed)');
        }
      }
    }, ok: 'Group "$name" created – AirPlay name: "$name | Music Assistant"');
  }

  // ---------- play my phone ----------
  List<Map<String, dynamic>> get targets => [...savedGroups, ...tempGroups, ...rooms.where((r) => r['available'] != false && !tempGroups.contains(r))];

  Future<void> startCapture() async {
    final id = capTarget ?? (targets.isEmpty ? null : targets.first['player_id'] as String);
    if (id == null) {
      toast('No room or group to play on');
      return;
    }
    await PhoneCapture.start(server: widget.ma.base, token: widget.ma.token, target: id, targetName: nameOf(id));
    toast('Allow "Start recording / casting" – then everything your phone plays goes to ${nameOf(id)}');
  }

  Widget captureCard() {
    final running = cap['running'] == true;
    final err = '${cap['error'] ?? ''}';
    final list = targets;
    final sel = capTarget != null && list.any((t) => t['player_id'] == capTarget) ? capTarget : (list.isEmpty ? null : list.first['player_id'] as String);
    return Container(
      margin: const EdgeInsets.fromLTRB(4, 8, 4, 4),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(18),
        gradient: LinearGradient(colors: running ? const [Color(0xFF2E6B2A), Color(0xFF13233A)] : const [Color(0xFF1C3524), Color(0xFF13213D)]),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(running ? Icons.cast_connected : Icons.phone_android, color: Colors.white),
          const SizedBox(width: 10),
          Expanded(
            child: Text(running ? 'Playing phone audio on ${cap['targetName']}' : 'Play my phone',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
          ),
        ]),
        const SizedBox(height: 6),
        Text(
          running
              ? 'Everything your phone plays (YouTube, Spotify, TikTok…) is heard there. Stop here or in the notification.'
              : 'Send any app\'s sound to a room or group. Tip: add the "Play my phone" tile to Quick Settings for one tap.',
          style: const TextStyle(color: Colors.white70, height: 1.35),
        ),
        Row(children: [
          const Expanded(child: Text('Mute this phone while playing', style: TextStyle(color: Colors.white))),
          Switch(
            value: muteOn,
            onChanged: (v) {
              setState(() => muteOn = v);
              PhoneCapture.configure(server: widget.ma.base, token: widget.ma.token, mute: v);
            },
          ),
        ]),
        if (cap['muteBroken'] == true)
          const Text('This phone silences the stream when muted, so its volume stays on.', style: TextStyle(color: Colors.white60, fontSize: 12)),
        if (!running && cap['noPrompt'] != true)
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Text(
                'Android asks "Start recording or casting?" each time – choose "Entire screen" (needed so every app is heard). '
                'Your installer can switch this question off once for this phone (tools\\phone_allow_capture.bat).',
                style: TextStyle(color: Colors.white60, fontSize: 12, height: 1.35)),
          ),
        if (err.isNotEmpty && !running) Padding(padding: const EdgeInsets.only(top: 6), child: Text(err, style: const TextStyle(color: Color(0xFFFF8A80)))),
        const SizedBox(height: 10),
        if (!running)
          Row(children: [
            Expanded(
              child: DropdownButton<String>(
                isExpanded: true,
                value: sel,
                items: [for (final t in list) DropdownMenuItem(value: t['player_id'] as String, child: Text('${t['name']}', overflow: TextOverflow.ellipsis))],
                onChanged: (v) => setState(() => capTarget = v),
              ),
            ),
            const SizedBox(width: 10),
            FilledButton.icon(onPressed: () { capTarget = sel; startCapture(); }, icon: const Icon(Icons.play_arrow), label: const Text('Start')),
          ])
        else
          FilledButton.icon(
            style: FilledButton.styleFrom(backgroundColor: const Color(0xFFFF8A80), foregroundColor: Colors.black),
            onPressed: () async { await PhoneCapture.stop(); await Future.delayed(const Duration(milliseconds: 500)); refresh(); },
            icon: const Icon(Icons.stop),
            label: const Text('Stop'),
          ),
      ]),
    );
  }

  // ---------- widgets ----------
  Widget volumeSlider(String id, num? level, Future<void> Function(int) set) {
    final v = dragging[id] ?? (level ?? 0).toDouble();
    return Slider(
      value: v.clamp(0, 100).toDouble(),
      max: 100,
      onChanged: (x) => setState(() => dragging[id] = x),
      onChangeEnd: (x) {
        dragging.remove(id);
        run(() => set(x.round()));
      },
    );
  }

  String nowLine(Map<String, dynamic> p) {
    final m = p['current_media'] as Map<String, dynamic>?;
    final t = (m?['title'] ?? '').toString();
    final a = (m?['artist'] ?? '').toString();
    final st = (p['playback_state'] ?? 'idle').toString();
    if (t.isEmpty) return st;
    return '$st · $t${a.isEmpty ? '' : ' – $a'}';
  }

  Widget transport(Map<String, dynamic> p) {
    final id = p['player_id'] as String;
    final playing = p['playback_state'] == 'playing';
    return Row(children: [
      IconButton(icon: const Icon(Icons.skip_previous), onPressed: () => run(() => widget.ma.simple('previous', id))),
      IconButton(iconSize: 40, icon: Icon(playing ? Icons.pause_circle : Icons.play_circle), onPressed: () => playPause(p)),
      IconButton(icon: const Icon(Icons.skip_next), onPressed: () => run(() => widget.ma.simple('next', id))),
      IconButton(icon: const Icon(Icons.stop), onPressed: () => run(() => widget.ma.simple('stop', id))),
    ]);
  }

  Widget groupCard(Map<String, dynamic> g, {required bool saved}) {
    final id = g['player_id'] as String;
    final user = aj[id]?['user_display_name'] ?? (members(g).isEmpty ? null : aj[members(g).first]?['user_display_name']);
    final memberNames = members(g).where((m) => m != id || !saved).map(nameOf).join(', ');
    return Card(
      color: const Color(0xFF141B29),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 4),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(saved ? Icons.speaker_group : Icons.link, color: _accent),
            const SizedBox(width: 8),
            Expanded(
              child: Text(saved ? '${g['name']}' : '${g['name']} + ${members(g).length - 1}',
                  style: const TextStyle(fontSize: 19, fontWeight: FontWeight.bold)),
            ),
            if (saved)
              Tooltip(
                message: 'AirPlay receiver: "${g['name']} | Music Assistant"',
                child: Row(children: [
                  const Icon(Icons.airplay, size: 18, color: Colors.white70),
                  Switch(
                    value: airplay.contains(id),
                    onChanged: (v) => run(() async {
                      await widget.ma.setAirplayReceiver(id, v);
                      airplay = await widget.ma.airplayReceiverPlayers();
                    }),
                  ),
                ]),
              ),
            PopupMenuButton<String>(
              onSelected: (a) {
                if (a == 'edit') newGroup(edit: g);
                if (a == 'ungroup') run(() => widget.ma.ungroup(id), ok: 'Ungrouped');
                if (a == 'delete') {
                  run(() => widget.ma.removeGroup(id), ok: 'Group deleted');
                }
              },
              itemBuilder: (c) => [
                if (saved) const PopupMenuItem(value: 'edit', child: Text('Change rooms')),
                if (!saved) const PopupMenuItem(value: 'ungroup', child: Text('Ungroup')),
                if (saved) const PopupMenuItem(value: 'delete', child: Text('Delete group')),
              ],
            ),
          ]),
          Text(memberNames, style: const TextStyle(color: Colors.white70)),
          Text(nowLine(g), maxLines: 1, overflow: TextOverflow.ellipsis),
          if (user != null) Text('▶ played by $user', style: const TextStyle(color: _accent)),
          Row(children: [
            Expanded(child: transport(g)),
          ]),
          volumeSlider(id, g['volume_level'] as num?, (v) => widget.ma.groupVolume(id, v)),
        ]),
      ),
    );
  }

  Widget roomCard(Map<String, dynamic> p) {
    final id = p['player_id'] as String;
    final inGroup = p['synced_to'] != null
        ? nameOf(p['synced_to'])
        : (p['active_group'] != null ? nameOf(p['active_group']) : null);
    final leads = members(p).length > 1 && p['synced_to'] == null;
    final user = aj[id]?['user_display_name'];
    final offline = p['available'] == false;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 10, 8, 2),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${p['name']}${offline ? '  (offline)' : ''}', style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold)),
                Text([
                  nowLine(p),
                  if (inGroup != null) 'in $inGroup',
                  if (leads) 'leads ${members(p).length - 1} more',
                ].join(' · '), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white70)),
                if (user != null) Text('▶ $user', style: const TextStyle(color: _accent)),
              ]),
            ),
            if (inGroup != null)
              IconButton(tooltip: 'Leave group', icon: const Icon(Icons.link_off), onPressed: () => run(() => widget.ma.ungroup(id))),
            IconButton(
              tooltip: 'Mute this room',
              icon: Icon(p['volume_muted'] == true ? Icons.volume_off : Icons.volume_up),
              onPressed: () => run(() => widget.ma.mute(id, !(p['volume_muted'] == true))),
            ),
            if (inGroup == null && !leads)
              IconButton(icon: Icon(p['playback_state'] == 'playing' ? Icons.pause : Icons.play_arrow), onPressed: () => playPause(p)),
          ]),
          volumeSlider(id, p['volume_level'] as num?, (v) => widget.ma.volume(id, v)),
        ]),
      ),
    );
  }

  Widget header(String t) => Padding(
        padding: const EdgeInsets.fromLTRB(8, 16, 8, 4),
        child: Text(t, style: const TextStyle(fontSize: 14, color: Colors.white60, fontWeight: FontWeight.w600, letterSpacing: 1)),
      );

  @override
  Widget build(BuildContext context) {
    final saved = savedGroups;
    final temp = tempGroups;
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 12,
        title: Row(children: [
          Image.asset('assets/logo_mark.png', height: 30),
          const SizedBox(width: 10),
          const Text('AJ Audio', style: TextStyle(fontWeight: FontWeight.w700)),
        ]),
        actions: [
          IconButton(
            tooltip: 'AirPlay receiver on for all saved groups',
            icon: const Icon(Icons.airplay),
            onPressed: () => run(() async {
              for (final g in savedGroups) {
                if (!airplay.contains(g['player_id'])) await widget.ma.setAirplayReceiver(g['player_id'], true);
              }
              airplay = await widget.ma.airplayReceiverPlayers();
            }, ok: 'AirPlay receiver enabled for all saved groups'),
          ),
          IconButton(icon: const Icon(Icons.refresh), onPressed: refresh),
          IconButton(icon: const Icon(Icons.logout), onPressed: widget.onLogout),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => newGroup(),
        icon: const Icon(Icons.add),
        label: const Text('New group'),
      ),
      body: RefreshIndicator(
        onRefresh: refresh,
        child: ListView(padding: const EdgeInsets.all(8), children: [
          if (error.isNotEmpty) Padding(padding: const EdgeInsets.all(8), child: Text(error, style: const TextStyle(color: Colors.redAccent))),
          const UpdateBanner(appName: 'AJ Audio Client'),
          if (PhoneCapture.supported) captureCard(),
          header('GROUPS'),
          if (saved.isEmpty && temp.isEmpty)
            const Padding(padding: EdgeInsets.all(8), child: Text('No groups yet – tap "New group".', style: TextStyle(color: Colors.white54))),
          ...saved.map((g) => groupCard(g, saved: true)),
          ...temp.map((g) => groupCard(g, saved: false)),
          header('ROOMS'),
          ...rooms.map(roomCard),
          const SizedBox(height: 90),
        ]),
      ),
    );
  }
}
