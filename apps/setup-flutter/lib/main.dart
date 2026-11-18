import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'api.dart';
import 'ui.dart';
import 'rooms.dart';
import 'groups.dart';
import 'users.dart';
import 'logs.dart';
import 'scan.dart';
import 'update.dart';
import 'tools.dart';
import 'updater.dart';

void main() => runApp(const AjSetupApp());

/// shared state for all screens
class Session {
  final Api api;
  String repo; // GitHub repo with the releases (panel app updates)
  String userName = '';
  Session(this.api, this.repo);
}

class AjSetupApp extends StatelessWidget {
  const AjSetupApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'AJ Audio Installer',
        debugShowCheckedModeBanner: false,
        theme: ajTheme(),
        home: const Start(),
      );
}

class Start extends StatefulWidget {
  const Start({super.key});
  @override
  State<Start> createState() => _StartState();
}

class _StartState extends State<Start> {
  Session? s;
  bool signedIn = false;

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((p) async {
      final api = Api(p.getString('base') ?? 'http://192.168.8.5:8095', p.getString('token') ?? '');
      final ses = Session(api, p.getString('repo') ?? 'ajnetworksa/AJ-Audio-System');
      var ok = false;
      if (api.token.isNotEmpty) {
        try {
          final me = await api.me();
          ok = me['role'] == 'admin';
          ses.userName = '${me['display_name'] ?? me['username']}';
        } catch (_) {}
      }
      setState(() { s = ses; signedIn = ok; });
    });
  }

  Future<void> logout() async {
    final p = await SharedPreferences.getInstance();
    await p.remove('token');
    setState(() { s!.api.token = ''; signedIn = false; });
  }

  @override
  Widget build(BuildContext context) {
    if (s == null) return const Backdrop(child: Scaffold(body: Center(child: Logo(height: 96))));
    if (!signedIn) return LoginPage(s: s!, onDone: () => setState(() => signedIn = true));
    return Overview(s: s!, onLogout: logout);
  }
}

// ======================================================= sign in
class LoginPage extends StatefulWidget {
  final Session s;
  final VoidCallback onDone;
  const LoginPage({super.key, required this.s, required this.onDone});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  late final server = TextEditingController(text: widget.s.api.base.replaceFirst('http://', ''));
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
      widget.s.api.base = b;
      final u = await widget.s.api.login(user.text.trim(), pass.text);
      if (u['role'] != 'admin') {
        widget.s.api.token = '';
        throw ApiException(403, 'This user is not an admin');
      }
      widget.s.userName = '${u['display_name'] ?? u['username']}';
      final p = await SharedPreferences.getInstance();
      await p.setString('base', b);
      await p.setString('token', widget.s.api.token);
      widget.onDone();
    } catch (e) {
      setState(() => error = e is ApiException && e.code == 403
          ? 'This Music Assistant user is not an admin. Use an admin user (Settings → Users in Music Assistant).'
          : '$e');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Backdrop(
        child: Scaffold(
          body: SafeArea(
            child: ListView(padding: const EdgeInsets.fromLTRB(24, 48, 24, 24), children: [
              const BrandLine(height: 44, color: C.text),
              const SizedBox(height: 26),
              const Text('AJ AUDIO · INSTALLER', style: TextStyle(color: C.accent, fontWeight: FontWeight.w700, letterSpacing: 1.2, fontSize: 13)),
              const SizedBox(height: 6),
              const Text('Connect to the music server', style: TextStyle(fontSize: 28, fontWeight: FontWeight.w800)),
              const SizedBox(height: 8),
              const Text('Sign in once with a Music Assistant admin user. Clients never need this app.',
                  style: TextStyle(color: C.sub, height: 1.4)),
              const SizedBox(height: 28),
              TextField(controller: server, decoration: const InputDecoration(labelText: 'Server address', hintText: '192.168.8.5')),
              const SizedBox(height: 12),
              TextField(controller: user, decoration: const InputDecoration(labelText: 'Admin username')),
              const SizedBox(height: 12),
              TextField(controller: pass, obscureText: true, decoration: const InputDecoration(labelText: 'Password'), onSubmitted: (_) => go()),
              const SizedBox(height: 22),
              GradButton(label: 'Sign in', icon: Icons.login, busy: busy, onPressed: go),
              if (error.isNotEmpty)
                Padding(padding: const EdgeInsets.only(top: 14), child: Text(error, style: const TextStyle(color: C.bad))),
            ]),
          ),
        ),
      );
}

// ======================================================= overview
class Overview extends StatefulWidget {
  final Session s;
  final VoidCallback onLogout;
  const Overview({super.key, required this.s, required this.onLogout});
  @override
  State<Overview> createState() => _OverviewState();
}

class _OverviewState extends State<Overview> {
  List<Room> players = [];
  int? ms;
  String error = '';
  bool loading = true;
  int users = 0;
  bool aria = true;   // AriaCast Receiver plugin present (needed for "Play my phone" and panel Share)

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final t = await widget.s.api.ping();
      final p = await widget.s.api.players();
      int u = 0;
      try { u = (await widget.s.api.users()).length; } catch (_) {}
      try { aria = await widget.s.api.providerInstance('ajcast') != null || await widget.s.api.providerInstance('ariacast_receiver') != null; } catch (_) {}
      setState(() { ms = t; players = p.map(Room.new).toList(); users = u; error = ''; loading = false; });
      provisionRepair();
    } catch (e) {
      setState(() { error = '$e'; ms = null; loading = false; });
    }
  }

  /// self-healing needs every panel to hold a repair token (AriaCast reload). Hand it over in the background
  /// to panels that don't have one yet – once per app start.
  bool _provisioned = false;
  Future<void> provisionRepair() async {
    if (_provisioned) return;
    _provisioned = true;
    String? token;
    for (final r in players.where((r) => r.isPanel && r.online && r.ip != null)) {
      final i = await PanelApi.info(r.ip!);
      if (i == null || i['repair_ready'] != false) continue;   // older panel app, or already has it
      token ??= await widget.s.api.repairToken();
      if (token == null) return;                               // this login isn't admin
      try { await PanelApi.post(r.ip!, widget.s.api.token, '/api/settings', {'repair_token': token}); } catch (_) {}
    }
  }

  Future<void> open(Widget page) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => page));
    load();
  }

  Future<void> testAll() async {
    var n = 0;
    for (final r in players.where((r) => r.isPanel && r.online && r.ip != null)) {
      try { await widget.s.api.announce(r.id, PanelApi.chimeUrl(r.ip!)); n++; } catch (_) {}
    }
    if (mounted) toast(context, n == 0 ? 'No online panels to test' : 'Test sound sent to $n rooms – listen in each room');
  }

  @override
  Widget build(BuildContext context) {
    final rooms = players.where((r) => !r.isGroup).toList();
    final groups = players.where((r) => r.isGroup).toList();
    final online = rooms.where((r) => r.online).length;
    final offline = rooms.where((r) => !r.online).toList();
    final host = widget.s.api.host;
    return AjPage(
      title: 'AJ Audio Installer',
      onRefresh: load,
      action: PopupMenuButton<String>(
        onSelected: (v) async {
          if (v == 'logout') widget.onLogout();
          if (v == 'repo') {
            final c = TextEditingController(text: widget.s.repo);
            final ok = await showDialog<bool>(
              context: context,
              builder: (d) => AlertDialog(
                backgroundColor: const Color(0xFF1A2333),
                title: const Text('Panel app updates from'),
                content: TextField(controller: c, decoration: const InputDecoration(labelText: 'GitHub repo (owner/name)')),
                actions: [TextButton(onPressed: () => Navigator.pop(d, true), child: const Text('Save'))],
              ),
            );
            if (ok == true) {
              widget.s.repo = c.text.trim();
              (await SharedPreferences.getInstance()).setString('repo', widget.s.repo);
            }
          }
        },
        itemBuilder: (_) => const [
          PopupMenuItem(value: 'repo', child: Text('Update source (GitHub)')),
          PopupMenuItem(value: 'logout', child: Text('Sign out')),
        ],
      ),
      subtitle: 'Music server $host · signed in as ${widget.s.userName}',
      children: [
        const Padding(padding: EdgeInsets.only(bottom: 14), child: BrandLine(height: 30)),
        const UpdateBanner(appName: 'AJ Audio Installer'),
        if (loading) const LinearProgressIndicator(),
        Row(children: [
          Expanded(child: _stat(ms == null ? 'Down' : 'OK', ms == null ? 'Server' : 'Server · $ms ms', ms == null ? C.bad : C.ok)),
          const SizedBox(width: 10),
          Expanded(child: _stat('$online/${rooms.length}', 'Rooms online', offline.isEmpty ? C.ok : C.warn)),
          const SizedBox(width: 10),
          Expanded(child: _stat('${offline.length + (ms == null ? 1 : 0)}', 'Need attention', offline.isEmpty && ms != null ? C.ok : C.warn)),
        ]),
        const SizedBox(height: 12),
        if (error.isNotEmpty) Glass(child: Text(error, style: const TextStyle(color: C.bad))),
        for (final r in offline)
          Glass(
            child: Row(children: [
              const Icon(Icons.warning_amber_rounded, color: C.warn),
              const SizedBox(width: 10),
              Expanded(child: Text('${r.name} is offline. Check power and Wi-Fi.', style: const TextStyle(height: 1.3))),
              TextButton(onPressed: () => open(RoomDetail(s: widget.s, room: r)), child: const Text('Details')),
            ]),
          ),
        if (!aria && ms != null)
          const Glass(
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(Icons.warning_amber_rounded, color: C.warn),
              SizedBox(width: 10),
              Expanded(
                child: Text(
                  'Phone & panel sharing is off. In Music Assistant: Settings → Plugins → Add a plugin → "AriaCast Receiver", '
                  'pick your main room or group as its player, Save. Needed for "Play my phone" and the panels\' Share.',
                  style: TextStyle(height: 1.35),
                ),
              ),
            ]),
          ),
        const Label('Set up'),
        _nav(Icons.radar, 'Find & set up panels', 'Scan the network · addresses · connect panels to this server',
            () => open(ScanPage(s: widget.s))),
        _nav(Icons.system_update_alt, 'Update panels', 'All at once · newest release or an APK file',
            () => open(BulkUpdatePage(s: widget.s))),
        _nav(Icons.build_circle_outlined, 'Panel tools', 'Install AJ Panel · silent updates · diagnostics · Zigbee (no PC needed)',
            () => open(ToolsPage(s: widget.s))),
        const Label('Manage'),
        _nav(Icons.speaker_outlined, 'Rooms & panels', '${rooms.length} rooms · names, AirPlay, Spotify, volume',
            () => open(RoomsPage(s: widget.s))),
        _nav(Icons.speaker_group_outlined, 'Groups', '${groups.length} groups · shown in AirPlay and Spotify',
            () => open(GroupsPage(s: widget.s))),
        _nav(Icons.people_alt_outlined, 'Users & access', '$users users · who may use which room',
            () => open(UsersPage(s: widget.s))),
        _nav(Icons.receipt_long_outlined, 'Logs & diagnostics', 'Server log, panel logs, one-tap report',
            () => open(LogsPage(s: widget.s))),
        const SizedBox(height: 10),
        OutlineBtn(label: 'Test sound in every room', icon: Icons.notifications_active_outlined, onPressed: testAll),
      ],
    );
  }

  Widget _stat(String big, String small, Color color) => Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: C.card,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: color.withAlpha(110)),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(big, style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: color)),
          Text(small, style: const TextStyle(color: C.sub, fontSize: 12)),
        ]),
      );

  Widget _nav(IconData icon, String title, String sub, VoidCallback onTap) => Glass(
        onTap: onTap,
        padding: const EdgeInsets.all(16),
        child: Row(children: [
          IconBadge(icon),
          const SizedBox(width: 14),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
              Text(sub, style: const TextStyle(color: C.sub, fontSize: 13)),
            ]),
          ),
          const Icon(Icons.chevron_right, color: C.sub),
        ]),
      );
}
