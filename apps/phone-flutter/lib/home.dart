import 'dart:async';
import 'dart:io' show Platform;
import 'package:flutter/material.dart';
import 'ma.dart';
import 'capture.dart';
import 'updater.dart';

const _accent = Color(0xFF6BBC46); // brand green

/// Three tabs: Playing (player + Play my phone), Groups, Rooms.
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
                'Android asks once each time you start – tap "Start" (the whole phone\'s sound is sent). '
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
  // volume: sends while dragging (4×/s) so you hear it, and keeps showing your value until the server reports it
  // (otherwise the 2-s refresh shows the old value for a moment and the slider jumps back and forth)
  final Map<String, _Pending> pending = {};
  final Map<String, DateTime> lastSent = {};
  Widget volumeSlider(String id, num? level, Future<void> Function(int) set) {
    final server = (level ?? 0).toDouble();
    double v = dragging[id] ?? server;
    if (dragging[id] == null) {
      final p = pending[id];
      if (p != null) {
        if (DateTime.now().isBefore(p.until) && (server - p.value).abs() > 2) {
          v = p.value;
        } else {
          pending.remove(id);
        }
      }
    }
    return Slider(
      value: v.clamp(0, 100).toDouble(),
      max: 100,
      onChanged: (x) {
        setState(() => dragging[id] = x);
        final now = DateTime.now();
        if (now.difference(lastSent[id] ?? DateTime(2000)).inMilliseconds > 250) {
          lastSent[id] = now;
          set(x.round()).catchError((_) {});
        }
      },
      onChangeEnd: (x) {
        dragging.remove(id);
        pending[id] = _Pending(x, DateTime.now().add(const Duration(seconds: 5)));
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

  // ---------- now playing (home tab) ----------
  int tab = 0;
  String? focus; // which group/room the player card shows

  /// groups and rooms that have music right now (members of a group are shown through their group)
  List<Map<String, dynamic>> get active {
    bool hasMedia(Map<String, dynamic> p) => p['current_media'] != null && '${p['playback_state']}' != 'idle';
    final memberOfActive = <String>{};
    final out = <Map<String, dynamic>>[];
    for (final g in [...savedGroups, ...tempGroups]) {
      if (hasMedia(g)) {
        out.add(g);
        memberOfActive.addAll(members(g));
      }
    }
    for (final r in rooms) {
      if (memberOfActive.contains(r['player_id']) || r['synced_to'] != null || tempGroups.contains(r)) continue;
      if (hasMedia(r)) out.add(r);
    }
    out.sort((a, b) => (a['playback_state'] == 'playing' ? 0 : 1).compareTo(b['playback_state'] == 'playing' ? 0 : 1));
    return out;
  }

  String? imageUrl(Map<String, dynamic>? m) {
    final u = '${m?['image_url'] ?? ''}';
    if (u.isEmpty) return null;
    if (u.startsWith('http')) return u;
    return '${widget.ma.base}${u.startsWith('/') ? '' : '/'}$u';
  }

  Widget nowPlaying() {
    final list = active;
    if (list.isEmpty) {
      return Container(
        margin: const EdgeInsets.fromLTRB(4, 8, 4, 4),
        padding: const EdgeInsets.all(22),
        decoration: BoxDecoration(color: const Color(0xFF141B29), borderRadius: BorderRadius.circular(18)),
        child: Column(children: [
          const Icon(Icons.music_note, size: 48, color: Colors.white38),
          const SizedBox(height: 10),
          const Text('Nothing playing', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Text(PhoneCapture.supported ? 'Use "Play my phone" below, or pick music on a panel.' : 'AirPlay from your iPhone to a group, or pick music on a panel.',
              textAlign: TextAlign.center, style: const TextStyle(color: Colors.white60)),
        ]),
      );
    }
    final p = list.firstWhere((x) => x['player_id'] == focus, orElse: () => list.first);
    final id = p['player_id'] as String;
    final m = p['current_media'] as Map<String, dynamic>?;
    final img = imageUrl(m);
    final title = '${m?['title'] ?? ''}';
    final artist = '${m?['artist'] ?? ''}';
    final isGroup = isGroupPlayer(p) || members(p).length > 1;
    final where = isGroup
        ? '${p['name']} · ${members(p).where((x) => x != id || !isGroupPlayer(p)).map(nameOf).join(', ')}'
        : '${p['name']}';
    return Container(
      margin: const EdgeInsets.fromLTRB(4, 8, 4, 4),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: const Color(0xFF141B29), borderRadius: BorderRadius.circular(18)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (list.length > 1)
          SizedBox(
            height: 40,
            child: ListView(scrollDirection: Axis.horizontal, children: [
              for (final x in list)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: Text('${x['name']}'),
                    selected: x['player_id'] == p['player_id'],
                    onSelected: (_) => setState(() => focus = x['player_id'] as String),
                  ),
                ),
            ]),
          ),
        if (list.length > 1) const SizedBox(height: 12),
        AspectRatio(
          aspectRatio: 1.6,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(14),
            child: img == null
                ? Container(color: const Color(0xFF1C2638), child: const Icon(Icons.album, size: 72, color: Colors.white24))
                : Image.network(img,
                    fit: BoxFit.cover,
                    headers: {if (widget.ma.token.isNotEmpty) 'Authorization': 'Bearer ${widget.ma.token}'},
                    errorBuilder: (_, __, ___) => Container(color: const Color(0xFF1C2638), child: const Icon(Icons.album, size: 72, color: Colors.white24))),
          ),
        ),
        const SizedBox(height: 14),
        Text(title.isEmpty ? 'Playing' : title, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w700)),
        if (artist.isNotEmpty) Text(artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 15, color: Colors.white70)),
        const SizedBox(height: 4),
        Row(children: [
          Icon(isGroup ? Icons.speaker_group : Icons.speaker, size: 16, color: _accent),
          const SizedBox(width: 6),
          Expanded(child: Text(where, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: _accent))),
        ]),
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          IconButton(iconSize: 34, icon: const Icon(Icons.skip_previous), onPressed: () => run(() => widget.ma.simple('previous', id))),
          IconButton(iconSize: 60, icon: Icon(p['playback_state'] == 'playing' ? Icons.pause_circle_filled : Icons.play_circle_filled, color: _accent), onPressed: () => playPause(p)),
          IconButton(iconSize: 34, icon: const Icon(Icons.skip_next), onPressed: () => run(() => widget.ma.simple('next', id))),
        ]),
        Row(children: [
          const Icon(Icons.volume_down, color: Colors.white54),
          Expanded(child: volumeSlider(id, p['volume_level'] as num?, (v) => isGroup ? widget.ma.groupVolume(id, v) : widget.ma.volume(id, v))),
          const Icon(Icons.volume_up, color: Colors.white54),
        ]),
      ]),
    );
  }

  Widget songInfoHint() {
    if (!PhoneCapture.supported || cap['songInfo'] == true) return const SizedBox.shrink();
    return Container(
      margin: const EdgeInsets.fromLTRB(4, 8, 4, 4),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: const Color(0xFF141B29), borderRadius: BorderRadius.circular(18), border: Border.all(color: _accent.withOpacity(0.5))),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Row(children: [
          Icon(Icons.image_outlined, color: _accent),
          SizedBox(width: 10),
          Expanded(child: Text('Show song name & cover on the panels', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700))),
        ]),
        const SizedBox(height: 6),
        const Text(
            'Android only tells an app what is playing (title, artist, picture) when you allow "notification access" for it. '
            'Tap the button, then switch on "AJ Audio".\n\n'
            'If the switch is grey ("Restricted setting"): open Settings → Apps → AJ Audio Client → ⋮ (top right) → '
            '"Allow restricted settings", then try again.',
            style: TextStyle(color: Colors.white70, height: 1.35)),
        const SizedBox(height: 10),
        FilledButton.icon(onPressed: () => PhoneCapture.openSongInfoSettings(), icon: const Icon(Icons.settings), label: const Text('Allow notification access')),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    final saved = savedGroups;
    final temp = tempGroups;
    final pages = [
      // 0 – Home: the player + play my phone
      [
        const UpdateBanner(appName: 'AJ Audio Client'),
        nowPlaying(),
        if (PhoneCapture.supported) captureCard(),
        songInfoHint(),
        const SizedBox(height: 24),
      ],
      // 1 – Groups
      [
        if (saved.isEmpty && temp.isEmpty)
          const Padding(padding: EdgeInsets.all(16), child: Text('No groups yet – tap "New group".', style: TextStyle(color: Colors.white54))),
        ...saved.map((g) => groupCard(g, saved: true)),
        ...temp.map((g) => groupCard(g, saved: false)),
      ],
      // 2 – Rooms
      [...rooms.map(roomCard)],
    ];
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 12,
        title: Row(children: [
          Image.asset('assets/logo_mark.png', height: 30),
          const SizedBox(width: 10),
          Text(const ['AJ Audio', 'Groups', 'Rooms'][tab], style: const TextStyle(fontWeight: FontWeight.w700)),
        ]),
        actions: [
          if (tab == 1)
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
      floatingActionButton: tab == 1
          ? FloatingActionButton.extended(onPressed: () => newGroup(), icon: const Icon(Icons.add), label: const Text('New group'))
          : null,
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.play_circle_outline), selectedIcon: Icon(Icons.play_circle), label: 'Playing'),
          NavigationDestination(icon: Icon(Icons.speaker_group_outlined), selectedIcon: Icon(Icons.speaker_group), label: 'Groups'),
          NavigationDestination(icon: Icon(Icons.speaker_outlined), selectedIcon: Icon(Icons.speaker), label: 'Rooms'),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: refresh,
        child: ListView(padding: const EdgeInsets.all(8), children: [
          if (error.isNotEmpty) Padding(padding: const EdgeInsets.all(8), child: Text(error, style: const TextStyle(color: Colors.redAccent))),
          ...pages[tab],
          const SizedBox(height: 90),
        ]),
      ),
    );
  }
}

class _Pending {
  final double value;
  final DateTime until;
  _Pending(this.value, this.until);
}
