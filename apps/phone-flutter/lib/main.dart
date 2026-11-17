import 'dart:io' show Platform;
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'ma.dart';
import 'home.dart';

/// AJ Network Solutions brand (D-021)
const accent = Color(0xFF6BBC46); // logo green
const navy = Color(0xFF0A0F1A);
const card = Color(0xFF141B29);

/// logo mark + AJ NETWORK SOLUTIONS
class BrandLine extends StatelessWidget {
  final double height;
  const BrandLine({super.key, this.height = 28});
  @override
  Widget build(BuildContext context) => Row(mainAxisSize: MainAxisSize.min, children: [
        Image.asset('assets/logo_mark.png', height: height),
        SizedBox(width: height * 0.35),
        Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Text('AJ NETWORK', style: TextStyle(fontWeight: FontWeight.w800, letterSpacing: 1.6, fontSize: height * 0.42, height: 1.05)),
          Text('SOLUTIONS', style: TextStyle(color: Colors.white70, fontWeight: FontWeight.w600, letterSpacing: 2.2, fontSize: height * 0.32, height: 1.1)),
        ]),
      ]);
}

void main() => runApp(const AjAudioApp());

class AjAudioApp extends StatelessWidget {
  const AjAudioApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'AJ Audio Client',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          brightness: Brightness.dark,
          colorScheme: ColorScheme.fromSeed(seedColor: accent, brightness: Brightness.dark, primary: accent, surface: navy),
          scaffoldBackgroundColor: navy,
          cardColor: card,
          appBarTheme: const AppBarTheme(backgroundColor: navy, surfaceTintColor: Colors.transparent),
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
    return HomePage(ma: ma!, onLogout: () async {
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
        body: ListView(padding: const EdgeInsets.fromLTRB(24, 64, 24, 24), children: [
          const Align(alignment: Alignment.centerLeft, child: BrandLine(height: 44)),
          const SizedBox(height: 28),
          const Text('AJ AUDIO', style: TextStyle(color: accent, fontWeight: FontWeight.w700, letterSpacing: 1.4, fontSize: 13)),
          const SizedBox(height: 6),
          const Text('Sign in to your music system', style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800)),
          const SizedBox(height: 20),
          TextField(controller: server, decoration: const InputDecoration(labelText: 'Server (Music Assistant)')),
          TextField(controller: user, decoration: const InputDecoration(labelText: 'Username')),
          TextField(controller: pass, obscureText: true, decoration: const InputDecoration(labelText: 'Password')),
          const SizedBox(height: 20),
          FilledButton(onPressed: busy ? null : go, child: Text(busy ? 'Signing in…' : 'Sign in')),
          if (error.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 12), child: Text(error, style: const TextStyle(color: Colors.redAccent))),
        ]),
      );
}
