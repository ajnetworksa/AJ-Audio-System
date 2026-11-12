import 'dart:io' show Platform;
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'ma.dart';
import 'home.dart';

const accent = Color(0xFF40A0FF);

void main() => runApp(const AjAudioApp());

class AjAudioApp extends StatelessWidget {
  const AjAudioApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'AJ Audio Client',
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
        appBar: AppBar(title: const Text('AJ Audio Client – sign in')),
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
