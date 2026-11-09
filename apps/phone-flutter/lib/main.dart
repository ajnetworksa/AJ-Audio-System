import 'dart:async';
import 'dart:io' show Platform;
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'ma.dart';

const accent = Color(0xFF40A0FF);

void main() => runApp(const AjAudioApp());

class AjAudioApp extends StatelessWidget {
  const AjAudioApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'AJ Audio',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          brightness: Brightness.dark,
          colorScheme: ColorScheme.fromSeed(seedColor: accent, brightness: Brightness.dark),
          useMaterial3: true,
        ),
        home: const Start(),
      );
}

/// decides between login and rooms
class Start extends StatefulWidget {
  const Start({super.key});
  @override
  State<Start> createState() => _StartState();
}

class _StartState extends State<Start> {
  Ma? ma;
  bool loading = true;

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((p) {
      final base = p.getString('base') ?? 'http://192.168.8.5:8095';
      final token = p.getString('token') ?? '';
      setState(() {
        ma = Ma(base, token);
        loading = false;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    if (loading) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    if (ma!.token.isEmpty) return LoginPage(ma: ma!, onDone: () => setState(() {}));
    return RoomsPage(ma: ma!, onLogout: () async {
      final p = await SharedPreferences.getInstance();
      await p.remove('token');
      setState(() => ma!.token = '');
    });
  }
}

class LoginPage extends StatefulWidget {
  final Ma ma;
  final VoidCallback onDone;
  const LoginPage({super.key, required this.ma, required this.onDone});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  late final server = TextEditingController(text: widget.ma.base);
  final user = TextEditingController();
  final pass = TextEditingController();
  String error = '';
  bool busy = false;

  Future<void> go() async {
    setState(() { busy = true; error = ''; });
    try {
      var b = server.text.trim();
      if (!b.startsWith('http')) b = 'http://$b';
      if (!RegExp(r':\d+$').hasMatch(b)) b = '$b:8095';
      widget.ma.base = b;
      await widget.ma.login(user.text.trim(), pass.text, 'AJ Audio ${Platform.operatingSystem}');
      final p = await SharedPreferences.getInstance();
      await p.setString('base', b);
      await p.setString('token', widget.ma.token);
      widget.onDone();
    } catch (e) {
      setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('AJ Audio – sign in')),
        body: ListView(padding: const EdgeInsets.all(20), children: [
          TextField(controller: server, decoration: const InputDecoration(labelText: 'Server (Music Assistant)')),
          TextField(controller: user, decoration: const InputDecoration(labelText: 'Username')),
          TextField(controller: pass, obscureText: true, decoration: const InputDecoration(labelText: 'Password')),
          const SizedBox(height: 20),
          FilledButton(onPressed: busy ? null : go, child: Text(busy ? 'Signing in…' : 'Sign in')),
          if (error.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 12), child: Text(error, style: const TextStyle(color: Colors.redAccent))),
        ]),
      );
}

class RoomsPage extends StatefulWidget {
  final Ma ma;
  final VoidCallback onLogout;
  const RoomsPage({super.key, required this.ma, required this.onLogout});
  @override
  State<RoomsPage> createState() => _RoomsPageState();
}

class _RoomsPageState extends State<RoomsPage> {
  List<Map<String, dynamic>> players = [];
  Map<String, Map<String, dynamic>> aj = {};
  final List<String> ticked = []; // order matters: first = leader
  final Map<String, double> dragging = {};
  String error = '';
  Timer? timer;

  @override
  void initState() {
    super.initState();
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
      // rooms = real speakers (not hidden, not protocol helpers); group players listed as scenes
      all.removeWhere((p) => p['hide_in_ui'] == true || p['enabled'] == false);
      all.sort((x, y) => (x['name'] ?? '').toString().compareTo((y['name'] ?? '').toString()));
      if (mounted) setState(() { players = all; aj = a; error = ''; });
    } on MaException catch (e) {
      if (e.code == 401) widget.onLogout();
      if (mounted) setState(() => error = e.toString());
    } catch (e) {
      if (mounted) setState(() => error = 'Server not reachable: $e');
    }
  }

  Map<String, dynamic>? byId(String? id) {
    for (final p in players) {
      if (p['player_id'] == id) return p;
    }
    return null;
  }

  Future<void> run(Future<void> Function() f) async {
    try {
      await f();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
    await refresh();
  }

  String groupLine(Map<String, dynamic> p) {
    final members = (p['group_members'] as List? ?? []);
    if (p['synced_to'] != null) return "in ${byId(p['synced_to'])?['name'] ?? 'a'} group";
    if (p['active_group'] != null) return "in ${byId(p['active_group'])?['name'] ?? 'group'}";
    if (members.length > 1) return 'group of ${members.length}';
    return '';
  }

  Widget card(Map<String, dynamic> p) {
    final id = p['player_id'] as String;
    final media = p['current_media'] as Map<String, dynamic>?;
    final state = (p['playback_state'] ?? 'idle').toString();
    final isGroup = p['type'] == 'group';
    final members = (p['group_members'] as List? ?? []);
    final leader = members.length > 1 && p['synced_to'] == null;
    final user = aj[id]?['user_display_name'];
    final vol = dragging[id] ?? ((p['volume_level'] ?? 0) as num).toDouble();
    final pos = ticked.indexOf(id);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 8, 16, 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Checkbox(
                value: pos >= 0,
                onChanged: (v) => setState(() => v == true ? ticked.add(id) : ticked.remove(id))),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${p['name']}${isGroup ? '  (scene)' : ''}${pos == 0 && ticked.length > 1 ? '  ★ leader' : ''}',
                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                Text([
                  state,
                  if (media != null && (media['title'] ?? '').toString().isNotEmpty) media['title'],
                  groupLine(p),
                ].where((s) => s.toString().isNotEmpty).join(' · '),
                    maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white70)),
                if (user != null) Text('▶ $user', style: const TextStyle(color: accent)),
              ]),
            ),
            IconButton(
                icon: Icon(p['volume_muted'] == true ? Icons.volume_off : Icons.volume_up),
                onPressed: () => run(() => widget.ma.mute(id, !(p['volume_muted'] == true)))),
          ]),
          Slider(
            value: vol.clamp(0, 100).toDouble(),
            max: 100,
            onChanged: (v) => setState(() => dragging[id] = v),
            onChangeEnd: (v) {
              dragging.remove(id);
              run(() => leader ? widget.ma.groupVolume(id, v.round()) : widget.ma.volume(id, v.round()));
            },
          ),
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final lead = ticked.isEmpty ? null : ticked.first;
    return Scaffold(
      appBar: AppBar(
        title: const Text('AJ Audio'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: refresh),
          IconButton(icon: const Icon(Icons.logout), onPressed: widget.onLogout),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: refresh,
        child: ListView(padding: const EdgeInsets.all(8), children: [
          if (error.isNotEmpty) Padding(padding: const EdgeInsets.all(8), child: Text(error, style: const TextStyle(color: Colors.redAccent))),
          ...players.map(card),
          const SizedBox(height: 8),
          if (Platform.isIOS)
            const Padding(
              padding: EdgeInsets.all(8),
              child: Text('Play your iPhone audio: Control Centre → AirPlay → choose "<room or group> | Music Assistant".',
                  style: TextStyle(color: Colors.white70)),
            ),
          const SizedBox(height: 120),
        ]),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Row(children: [
              Expanded(
                child: FilledButton.icon(
                  icon: const Icon(Icons.link),
                  label: const Text('Group ticked'),
                  onPressed: ticked.length < 2 ? null : () => run(() => widget.ma.setMembers(ticked.first, add: ticked.sublist(1))),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  icon: const Icon(Icons.link_off),
                  label: const Text('Ungroup ticked'),
                  onPressed: ticked.isEmpty ? null : () => run(() async { for (final t in List.of(ticked)) { await widget.ma.ungroup(t); } }),
                ),
              ),
            ]),
            Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
              IconButton(iconSize: 36, icon: const Icon(Icons.skip_previous), onPressed: lead == null ? null : () => run(() => widget.ma.simple('previous', lead))),
              IconButton(iconSize: 44, icon: const Icon(Icons.play_circle), onPressed: lead == null ? null : () => run(() => widget.ma.simple('play_pause', lead))),
              IconButton(iconSize: 36, icon: const Icon(Icons.skip_next), onPressed: lead == null ? null : () => run(() => widget.ma.simple('next', lead))),
              IconButton(iconSize: 36, icon: const Icon(Icons.stop), onPressed: lead == null ? null : () => run(() => widget.ma.simple('stop', lead))),
            ]),
          ]),
        ),
      ),
    );
  }
}
