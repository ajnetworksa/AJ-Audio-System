import 'dart:convert';
import 'dart:async';
import 'dart:io' show Platform;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

/// In-app updates from GitHub Releases (FIX-032). Android: download + Android's "Install?" window.
/// iPhone: apps can't update themselves – the banner tells where to get the new IPA.
class AppUpdate {
  static const _ch = MethodChannel('aj/update');
  static const repo = 'ajnetworksa/AJ-Audio-System';
  static const _prefix = 'AJ_Audio_Client';

  static Future<String> installed() async {
    try {
      return '${await _ch.invokeMethod('version')}';
    } catch (_) {
      return '0';
    }
  }

  /// (version, apk url) of a newer release, or null
  static Future<(String, String)?> check() async {
    if (!Platform.isAndroid) return null; // iPhone: install the new IPA with Sideloadly/AltStore
    try {
      final r = await http
          .get(Uri.parse('https://api.github.com/repos/$repo/releases/latest'), headers: {'Accept': 'application/vnd.github+json'})
          .timeout(const Duration(seconds: 12));
      if (r.statusCode != 200) return null;
      final j = jsonDecode(r.body) as Map;
      const want = 'apk';
      for (final a in (j['assets'] as List? ?? [])) {
        final m = RegExp('^${_prefix}_v([0-9.]+)\\.$want\$').firstMatch('${(a as Map)['name']}');
        if (m == null) continue;
        final v = m.group(1)!;
        if (_newer(v, await installed())) return (v, '${a['browser_download_url']}');
      }
    } catch (_) {}
    return null;
  }

  static Future<void> install(String url) async {
    if (!Platform.isAndroid) return;
    await _ch.invokeMethod('install', {'url': url});
  }

  /// "", "downloading 42 %", "waiting for Install", or an error
  static Future<String> state() async {
    try {
      return '${await _ch.invokeMethod('state') ?? ''}';
    } catch (_) {
      return '';
    }
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
}

/// "Update available" card: checks at start and every 6 h, one tap installs.
class UpdateBanner extends StatefulWidget {
  final String appName;
  const UpdateBanner({super.key, required this.appName});
  @override
  State<UpdateBanner> createState() => _UpdateBannerState();
}

class _UpdateBannerState extends State<UpdateBanner> {
  (String, String)? avail;
  String state = '';
  Timer? t;

  @override
  void initState() {
    super.initState();
    _check();
    t = Timer.periodic(const Duration(hours: 6), (_) => _check());
  }

  @override
  void dispose() {
    t?.cancel();
    super.dispose();
  }

  Future<void> _check() async {
    final a = await AppUpdate.check();
    if (mounted) setState(() => avail = a);
  }

  Future<void> _install() async {
    await AppUpdate.install(avail!.$2);
    // follow the download until Android's window opens
    for (var i = 0; i < 240 && mounted; i++) {
      final s = await AppUpdate.state();
      setState(() => state = s);
      if (s.isEmpty || s == 'waiting for Install' || s.startsWith('Update failed') || s.startsWith('Download failed')) break;
      await Future.delayed(const Duration(milliseconds: 500));
    }
  }

  @override
  Widget build(BuildContext context) {
    if (avail == null) return const SizedBox.shrink();
    final busy = state.startsWith('downloading');
    return Card(
      color: const Color(0xFF1C3524),
      margin: const EdgeInsets.fromLTRB(4, 8, 4, 4),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 12, 12),
        child: Row(children: [
          const Icon(Icons.system_update, color: Colors.white),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${widget.appName} ${avail!.$1} is available', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15.5)),
              Text(
                state.isEmpty ? 'Tap Update – Android asks once to install it.' : state == 'waiting for Install' ? 'Tap Install in the Android window.' : state,
                style: const TextStyle(color: Colors.white70, fontSize: 13),
              ),
            ]),
          ),
          if (!busy && state != 'waiting for Install') FilledButton(onPressed: _install, child: const Text('Update')),
          if (busy) const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5)),
        ]),
      ),
    );
  }
}
