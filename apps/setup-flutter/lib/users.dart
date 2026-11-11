import 'package:flutter/material.dart';
import 'api.dart';
import 'main.dart';
import 'ui.dart';

class UsersPage extends StatefulWidget {
  final Session s;
  const UsersPage({super.key, required this.s});
  @override
  State<UsersPage> createState() => _UsersPageState();
}

class _UsersPageState extends State<UsersPage> {
  List<Map<String, dynamic>> users = [];
  List<Room> rooms = [];
  bool loading = true;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final u = await widget.s.api.users();
      final p = await widget.s.api.players();
      u.removeWhere((x) => x['role'] == 'service');
      setState(() { users = u; rooms = p.map(Room.new).toList(); loading = false; });
    } catch (e) {
      setState(() => loading = false);
      if (mounted) toast(context, '$e');
    }
  }

  String access(Map<String, dynamic> u) {
    if (u['role'] == 'admin') return 'Admin · everything';
    final f = [for (final x in (u['player_filter'] as List? ?? [])) '$x'];
    if (f.isEmpty) return '${_role(u['role'])} · all rooms';
    final names = f.map((id) => rooms.firstWhere((r) => r.id == id, orElse: () => Room({'player_id': id, 'name': '?'})).name);
    return '${_role(u['role'])} · ${names.join(', ')}';
  }

  Future<void> open([Map<String, dynamic>? u]) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => UserEdit(s: widget.s, user: u, rooms: rooms)));
    load();
  }

  @override
  Widget build(BuildContext context) => AjPage(
        title: 'Users & access',
        subtitle: 'Who may use which rooms and groups. Someone at a panel can always mute their own room.',
        onRefresh: load,
        action: FilledButton.icon(onPressed: () => open(), icon: const Icon(Icons.person_add_alt), label: const Text('Add')),
        children: [
          if (loading) const LinearProgressIndicator(),
          for (final u in users)
            Glass(
              onTap: () => open(u),
              child: Row(children: [
                CircleAvatar(
                  backgroundColor: u['role'] == 'admin' ? const Color(0xFF2B3A55) : const Color(0xFF3B2B55),
                  child: Text(_initial(u), style: const TextStyle(color: C.text, fontWeight: FontWeight.w700)),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(_shown(u), style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                    Text(u['username'] == 'panels' ? 'Used by the wall panels – keep it' : access(u),
                        maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(color: C.sub, fontSize: 13)),
                  ]),
                ),
                const Icon(Icons.chevron_right, color: C.sub),
              ]),
            ),
        ],
      );
}

String _shown(Map<String, dynamic> u) {
  final d = '${u['display_name'] ?? ''}'.trim();
  return d.isEmpty ? '${u['username'] ?? '?'}' : d;
}

String _initial(Map<String, dynamic> u) {
  final s = _shown(u);
  return s.isEmpty ? '?' : s.substring(0, 1).toUpperCase();
}

String _role(dynamic r) => r == 'admin' ? 'Admin' : r == 'guest' ? 'Guest' : 'Family member';

class UserEdit extends StatefulWidget {
  final Session s;
  final Map<String, dynamic>? user;
  final List<Room> rooms;
  const UserEdit({super.key, required this.s, this.user, required this.rooms});
  @override
  State<UserEdit> createState() => _UserEditState();
}

class _UserEditState extends State<UserEdit> {
  late final username = TextEditingController(text: '${widget.user?['username'] ?? ''}');
  late final display = TextEditingController(text: '${widget.user?['display_name'] ?? ''}');
  final password = TextEditingController();
  late String role = '${widget.user?['role'] ?? 'user'}';
  late final Set<String> picked = {for (final x in (widget.user?['player_filter'] as List? ?? [])) '$x'};
  late bool allRooms = picked.isEmpty;
  bool busy = false;

  bool get isNew => widget.user == null;
  bool get protectedUser => widget.user?['username'] == 'panels';

  Future<void> save() async {
    setState(() => busy = true);
    try {
      final rooms = allRooms ? <String>[] : picked.toList();
      if (isNew) {
        if (username.text.trim().length < 2 || password.text.length < 8) {
          throw ApiException(400, 'Username (2+ characters) and password (8+ characters) are needed');
        }
        await widget.s.api.createUser(username.text.trim(), password.text, role, display.text.trim(), rooms);
      } else {
        await widget.s.api.updateUser('${widget.user!['user_id']}',
            display: display.text.trim(), role: protectedUser ? null : role, rooms: rooms, password: password.text.isEmpty ? null : password.text);
      }
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
    if (mounted) setState(() => busy = false);
  }

  Future<void> delete() async {
    if (!await confirm(context, 'Delete ${widget.user!['username']}?', 'This person can no longer use the music system.', ok: 'Delete')) return;
    try {
      await widget.s.api.deleteUser('${widget.user!['user_id']}');
      if (mounted) Navigator.pop(context);
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final rooms = widget.rooms;
    return AjPage(
      title: isNew ? 'Add user' : _shown(widget.user!),
      action: isNew || protectedUser ? null : IconButton(onPressed: delete, icon: const Icon(Icons.delete_outline), tooltip: 'Delete user'),
      bottom: GradButton(label: isNew ? 'Add user' : 'Save', icon: Icons.check, busy: busy, onPressed: save),
      children: [
        if (protectedUser)
          const Glass(child: Text('The wall panels sign in with this user. You can change its rooms, but don\'t delete it.', style: TextStyle(color: C.warn))),
        Glass(
          child: Column(children: [
            TextField(controller: username, enabled: isNew, decoration: const InputDecoration(labelText: 'Username')),
            const SizedBox(height: 10),
            TextField(controller: display, decoration: const InputDecoration(labelText: 'Name shown on the panels ("played by …")')),
            const SizedBox(height: 10),
            TextField(controller: password, obscureText: true, decoration: InputDecoration(labelText: isNew ? 'Password (8+ characters)' : 'New password (leave empty to keep)')),
          ]),
        ),
        const Label('Role'),
        Glass(
          child: SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'user', label: Text('Family')),
              ButtonSegment(value: 'guest', label: Text('Guest')),
              ButtonSegment(value: 'admin', label: Text('Admin')),
            ],
            selected: {role},
            onSelectionChanged: protectedUser ? null : (v) => setState(() => role = v.first),
          ),
        ),
        if (role != 'admin') ...[
          const Label('May play in'),
          Glass(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              SettingRow(label: 'All rooms and groups', trailing: Switch(value: allRooms, onChanged: (v) => setState(() => allRooms = v))),
              if (!allRooms) ...[
                const SizedBox(height: 8),
                RoomChips(
                  rooms: [for (final r in rooms) (r.id, r.isGroup ? '${r.name} (group)' : r.name, r.online)],
                  selected: picked,
                  onToggle: (id) => setState(() => picked.contains(id) ? picked.remove(id) : picked.add(id)),
                ),
              ],
            ]),
          ),
        ],
      ],
    );
  }
}
