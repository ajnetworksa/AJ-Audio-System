import 'dart:convert';
import 'package:http/http.dart' as http;

class ApiException implements Exception {
  final int code;
  final String message;
  ApiException(this.code, this.message);
  @override
  String toString() => code == 403
      ? 'Not allowed – sign in with a Music Assistant admin user'
      : (message.length > 300 ? message.substring(0, 300) : message);
}

/// Music Assistant API (POST /api, Bearer token)
class Api {
  String base; // http://192.168.8.5:8095
  String token;
  Api(this.base, this.token);

  String get host => Uri.parse(base).host;

  Future<dynamic> cmd(String command, [Map<String, dynamic> args = const {}]) async {
    final r = await http
        .post(Uri.parse('$base/api'),
            headers: {
              'Content-Type': 'application/json',
              if (token.isNotEmpty) 'Authorization': 'Bearer $token',
            },
            body: jsonEncode({'message_id': '${DateTime.now().microsecondsSinceEpoch}', 'command': command, 'args': args}))
        .timeout(const Duration(seconds: 20));
    if (r.statusCode >= 400) throw ApiException(r.statusCode, utf8.decode(r.bodyBytes));
    final b = utf8.decode(r.bodyBytes).trim();
    if (b.isEmpty || b == 'null') return null;
    try {
      return jsonDecode(b);
    } catch (_) {
      return b;
    }
  }

  /// sign in, keep a long-lived token; returns the user
  Future<Map<String, dynamic>> login(String user, String pass) async {
    token = '';
    final r = await cmd('auth/login', {'username': user, 'password': pass, 'device_name': 'AJ Audio Installer'});
    if (r is! Map || r['success'] != true) {
      throw ApiException(401, r is Map ? '${r['error'] ?? 'Sign-in failed'}' : 'Sign-in failed');
    }
    token = '${r['access_token']}';
    try {
      final t = await cmd('auth/token/create', {'name': 'AJ Audio Installer'});
      if (t is String && t.isNotEmpty) token = t;
    } catch (_) {}
    return Map<String, dynamic>.from(r['user'] as Map);
  }

  Future<Map<String, dynamic>> me() async => Map<String, dynamic>.from(await cmd('auth/me') as Map);

  Future<List<Map<String, dynamic>>> players() async {
    final r = await cmd('players/all', {'return_unavailable': true, 'return_disabled': false});
    final list = [for (final p in (r as List? ?? [])) Map<String, dynamic>.from(p as Map)];
    list.removeWhere((p) => p['hide_in_ui'] == true);
    list.sort((a, b) => '${a['name']}'.toLowerCase().compareTo('${b['name']}'.toLowerCase()));
    return list;
  }

  Future<String?> providerInstance(String domain) async {
    final r = await cmd('providers');
    for (final p in (r as List? ?? [])) {
      if (p is Map && p['domain'] == domain) return '${p['instance_id']}';
    }
    return null;
  }

  Future<void> saveConfig(String playerId, Map<String, dynamic> values) =>
      cmd('config/players/save', {'player_id': playerId, 'values': values});

  Future<Set<String>> airplayPlayers() async {
    try {
      final inst = await providerInstance('airplay_receiver');
      if (inst == null) return {};
      final v = await cmd('config/providers/get_value', {'instance_id': inst, 'key': 'connected_players'});
      return {for (final x in (v as List? ?? [])) '$x'};
    } catch (_) {
      return {};
    }
  }

  Future<void> setAirplay(String playerId, bool on) async {
    final inst = await providerInstance('airplay_receiver');
    if (inst == null) throw ApiException(404, 'The AirPlay Receiver plugin is not installed in Music Assistant');
    await saveConfig(playerId, {'$inst||plugin||enabled': on});
  }

  Future<String?> createGroup(String name, List<String> members) async {
    final inst = await providerInstance('sync_group');
    if (inst == null) throw ApiException(404, 'Sync group provider not found');
    final r = await cmd('players/create_group_player', {'provider': inst, 'name': name, 'members': members, 'dynamic': true});
    return r is Map ? '${r['player_id']}' : null;
  }

  Future<void> removeGroup(String id) => cmd('players/remove_group_player', {'player_id': id});

  Future<List<Map<String, dynamic>>> users() async {
    final r = await cmd('auth/users');
    return [for (final u in (r as List? ?? [])) Map<String, dynamic>.from(u as Map)];
  }

  Future<void> createUser(String username, String password, String role, String display, List<String> rooms) =>
      cmd('auth/user/create', {
        'username': username,
        'password': password,
        'role': role,
        if (display.isNotEmpty) 'display_name': display,
        'player_filter': rooms,
      });

  Future<void> updateUser(String id, {String? display, String? role, List<String>? rooms, String? password}) =>
      cmd('auth/user/update', {
        'user_id': id,
        if (display != null) 'display_name': display,
        if (role != null) 'role': role,
        if (rooms != null) 'player_filter': rooms,
        if (password != null && password.isNotEmpty) 'password': password,
      });

  Future<void> deleteUser(String id) => cmd('auth/user/delete', {'user_id': id});

  Future<String> serverLog() async => '${await cmd('logging/get') ?? ''}';

  Future<dynamic> diagnostics() => cmd('diagnostics/get', {'include_log_tail': true});

  Future<void> announce(String playerId, String url) =>
      cmd('players/cmd/play_announcement', {'player_id': playerId, 'url': url, 'pre_announce': false});

  /// round-trip time to the server in ms (null = unreachable)
  Future<int?> ping() async {
    final sw = Stopwatch()..start();
    try {
      await cmd('auth/me');
      return sw.elapsedMilliseconds;
    } catch (_) {
      return null;
    }
  }
}

/// one room/speaker as Music Assistant sees it
class Room {
  final Map<String, dynamic> p;
  Room(this.p);
  String get id => '${p['player_id']}';
  String get name => '${p['name']}';
  bool get online => p['available'] != false;
  bool get isGroup => p['type'] == 'group';
  bool get isPanel => id.startsWith('ma_ajpanel_');
  String get state => '${p['playback_state'] ?? 'idle'}';
  String get title => '${(p['current_media'] as Map?)?['title'] ?? ''}';
  List<String> get members => [for (final m in (p['group_members'] as List? ?? [])) '$m'];
  String? get ip {
    final di = p['device_info'];
    if (di is Map) {
      final ids = di['identifiers'];
      if (ids is Map && ids['ip_address'] != null) return '${ids['ip_address']}';
    }
    return null;
  }
  String get model {
    final di = p['device_info'];
    return di is Map ? '${di['model'] ?? ''}' : '';
  }
}

/// AJ Panel LAN API (port 8766) – needs the installer's MA admin token
class PanelApi {
  static const port = 8766;
  static Uri _u(String ip, String path) => Uri.parse('http://$ip:$port$path');

  static Future<Map<String, dynamic>?> info(String ip) async {
    try {
      final r = await http.get(_u(ip, '/api/info')).timeout(const Duration(seconds: 4));
      if (r.statusCode != 200) return null;
      return Map<String, dynamic>.from(jsonDecode(utf8.decode(r.bodyBytes)) as Map);
    } catch (_) {
      return null;
    }
  }

  static Future<int?> ping(String ip) async {
    final sw = Stopwatch()..start();
    final i = await info(ip);
    return i == null ? null : sw.elapsedMilliseconds;
  }

  static Future<String> log(String ip, String token) async {
    final r = await http.get(_u(ip, '/api/log'), headers: {'Authorization': 'Bearer $token'}).timeout(const Duration(seconds: 8));
    if (r.statusCode != 200) throw ApiException(r.statusCode, 'Panel said ${r.statusCode}: ${r.body}');
    return utf8.decode(r.bodyBytes);
  }

  static Future<void> post(String ip, String token, String path, [Map<String, String> q = const {}]) async {
    final u = _u(ip, path).replace(queryParameters: q.isEmpty ? null : q);
    final r = await http.post(u, headers: {'Authorization': 'Bearer $token'}).timeout(const Duration(seconds: 10));
    if (r.statusCode != 200) throw ApiException(r.statusCode, 'Panel said ${r.statusCode}: ${r.body}');
  }

  static String chimeUrl(String ip) => 'http://$ip:$port/chime.wav';
}

/// newest AJ Panel APK on GitHub Releases
class Releases {
  static Future<(String version, String url)?> latestPanel(String repo) async {
    try {
      final r = await http
          .get(Uri.parse('https://api.github.com/repos/$repo/releases/latest'), headers: {'Accept': 'application/vnd.github+json'})
          .timeout(const Duration(seconds: 10));
      if (r.statusCode != 200) return null;
      final j = jsonDecode(r.body) as Map;
      for (final a in (j['assets'] as List? ?? [])) {
        final name = '${(a as Map)['name']}';
        final m = RegExp(r'^AJ_Panel_v([0-9.]+)\.apk$').firstMatch(name);
        if (m != null) return (m.group(1)!, '${a['browser_download_url']}');
      }
    } catch (_) {}
    return null;
  }
}
