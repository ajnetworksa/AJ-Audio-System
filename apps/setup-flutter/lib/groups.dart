import 'package:flutter/material.dart';
import 'api.dart';
import 'main.dart';
import 'ui.dart';

class GroupsPage extends StatefulWidget {
  final Session s;
  const GroupsPage({super.key, required this.s});
  @override
  State<GroupsPage> createState() => _GroupsPageState();
}

class _GroupsPageState extends State<GroupsPage> {
  List<Room> all = [];
  Set<String> airplay = {};
  Set<String> spotify = {};
  bool loading = true;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final p = await widget.s.api.players();
      final a = await widget.s.api.airplayPlayers();
      final sp = await widget.s.api.spotifyPlayers();
      setState(() { all = p.map(Room.new).toList(); airplay = a; spotify = sp; loading = false; });
    } catch (e) {
      setState(() => loading = false);
      if (mounted) toast(context, '$e');
    }
  }

  String nameOf(String id) => all.firstWhere((r) => r.id == id, orElse: () => Room({'player_id': id, 'name': id})).name;

  Future<void> edit([Room? g]) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => GroupEdit(s: widget.s, group: g, all: all, airplayOn: g == null || airplay.contains(g.id), spotifyOn: g != null && spotify.contains(g.id))));
    load();
  }

  @override
  Widget build(BuildContext context) {
    final groups = all.where((r) => r.isGroup).toList();
    return AjPage(
      title: 'Groups',
      subtitle: 'Clients pick a group in AirPlay (iPhone) or the AJ Audio app – each group shows up as "name | Music Assistant".',
      onRefresh: load,
      action: FilledButton.icon(onPressed: () => edit(), icon: const Icon(Icons.add), label: const Text('New')),
      children: [
        if (loading) const LinearProgressIndicator(),
        if (!loading && groups.isEmpty)
          const Glass(child: Text('No groups yet. Tap "New" to make one, e.g. "Whole House".', style: TextStyle(color: C.sub))),
        for (final g in groups)
          Glass(
            onTap: () => edit(g),
            padding: const EdgeInsets.all(16),
            child: Row(children: [
              const IconBadge(Icons.speaker_group_outlined),
              const SizedBox(width: 14),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(g.name, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
                  Text(
                    '${g.members.map(nameOf).join(', ')}${airplay.contains(g.id) ? ' · AirPlay' : ''}${spotify.contains(g.id) ? ' · Spotify' : ''}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: C.sub, fontSize: 13),
                  ),
                  if (g.state != 'idle')
                    Text('${g.state}${g.title.isEmpty ? '' : ' · ${g.title}'}', style: const TextStyle(color: Color(0xFF9CCBFF), fontSize: 13)),
                ]),
              ),
              const Icon(Icons.chevron_right, color: C.sub),
            ]),
          ),
      ],
    );
  }
}

class GroupEdit extends StatefulWidget {
  final Session s;
  final Room? group;
  final List<Room> all;
  final bool airplayOn;
  final bool spotifyOn;
  const GroupEdit({super.key, required this.s, this.group, required this.all, required this.airplayOn, this.spotifyOn = false});
  @override
  State<GroupEdit> createState() => _GroupEditState();
}

class _GroupEditState extends State<GroupEdit> {
  late final name = TextEditingController(text: widget.group?.name ?? '');
  late final Set<String> picked = {...?widget.group?.members};
  late bool airplay = widget.airplayOn;
  late bool spotify = widget.spotifyOn;
  bool busy = false;

  Api get api => widget.s.api;

  Future<void> save() async {
    final n = name.text.trim();
    if (n.isEmpty || picked.isEmpty) {
      toast(context, 'Give the group a name and pick at least one room');
      return;
    }
    setState(() => busy = true);
    try {
      // only rooms that still exist (a replaced panel leaves a dead id behind in old groups)
      final existing = {for (final r in widget.all) if (!r.isGroup) r.id};
      final members = picked.where(existing.contains).toList();
      if (members.isEmpty) throw ApiException(400, 'Pick at least one room');
      String? id = widget.group?.id;
      if (id == null) {
        id = await api.createGroup(n, members);
      } else {
        await api.saveConfig(id, {'name': n, 'group_members': members});
      }
      if (id != null) {
        try { await api.setAirplay(id, airplay); } catch (e) { if (mounted) toast(context, 'Saved, but AirPlay: $e'); }
        if (spotify != widget.spotifyOn) {
          try { await api.setSpotify(id, spotify); } catch (e) { if (mounted) toast(context, 'Saved, but Spotify: $e'); }
        }
      }
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
    if (mounted) setState(() => busy = false);
  }

  Future<void> delete() async {
    if (!await confirm(context, 'Delete ${widget.group!.name}?', 'The rooms stay; only the group is removed.', ok: 'Delete')) return;
    try {
      await api.removeGroup(widget.group!.id);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final rooms = widget.all.where((r) => !r.isGroup).toList();
    return AjPage(
      title: widget.group == null ? 'New group' : widget.group!.name,
      action: widget.group == null ? null : IconButton(onPressed: delete, icon: const Icon(Icons.delete_outline), tooltip: 'Delete group'),
      bottom: GradButton(label: widget.group == null ? 'Create group' : 'Save', icon: Icons.check, busy: busy, onPressed: save),
      children: [
        Glass(child: TextField(controller: name, decoration: const InputDecoration(labelText: 'Group name', hintText: 'Whole House'), onChanged: (_) => setState(() {}))),
        const Label('Rooms'),
        Glass(
          child: RoomChips(
            rooms: [for (final r in rooms) (r.id, r.name, r.online)],
            selected: picked,
            onToggle: (id) => setState(() => picked.contains(id) ? picked.remove(id) : picked.add(id)),
          ),
        ),
        Glass(
          child: SettingRow(
            label: 'AirPlay receiver',
            hint: 'iPhones see "${name.text.isEmpty ? 'name' : name.text} | Music Assistant"',
            trailing: Switch(value: airplay, onChanged: (v) => setState(() => airplay = v)),
          ),
        ),
        Glass(
          child: SettingRow(
            label: 'Show in Spotify',
            hint: 'The Spotify app lists this group under "Connect to a device"',
            trailing: Switch(value: spotify, onChanged: (v) => setState(() => spotify = v)),
          ),
        ),
        if (picked.any((id) => !widget.all.any((r) => r.id == id)))
          const Padding(
            padding: EdgeInsets.all(4),
            child: Text('This group still lists a panel that no longer exists. Tap Save to clean it up.', style: TextStyle(color: C.warn, fontSize: 13)),
          ),
        const Padding(
          padding: EdgeInsets.all(4),
          child: Text(
            'Rooms can take a break from a group on their panel (15 min, 1 h, until 07:00 …) and come back by themselves.',
            style: TextStyle(color: C.sub, fontSize: 13, height: 1.4),
          ),
        ),
      ],
    );
  }
}
