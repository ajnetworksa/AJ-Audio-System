import 'dart:convert';
import 'package:http/http.dart' as http;

/// Music Assistant HTTP API: POST /api {message_id, command, args}, Bearer token.
class MaException implements Exception {
  final int code;
  final String message;
  MaException(this.code, this.message);
  @override
  String toString() => '$code: $message';
}

class Ma {
  String base; // http://192.168.8.5:8095
  String token;
  Ma(this.base, this.token);

  Future<dynamic> cmd(String command, [Map<String, dynamic> args = const {}]) async {
    final r = await http
        .post(Uri.parse('$base/api'),
            headers: {
              'Content-Type': 'application/json',
              if (token.isNotEmpty) 'Authorization': 'Bearer $token',
            },
            body: jsonEncode({
              'message_id': DateTime.now().microsecondsSinceEpoch.toString(),
              'command': command,
              'args': args,
            }))
        .timeout(const Duration(seconds: 15));
    if (r.statusCode >= 400) throw MaException(r.statusCode, r.body);
    final b = utf8.decode(r.bodyBytes).trim();
    if (b.isEmpty || b == 'null') return null;
    return jsonDecode(b);
  }

  Future<String> login(String user, String pass, String device) async {
    final r = await cmd('auth/login', {'username': user, 'password': pass, 'device_name': device});
    if (r is Map && r['success'] == true && r['access_token'] != null) {
      token = r['access_token'];
      try {
        final t = await cmd('auth/token/create', {'name': device});
        if (t is String && t.isNotEmpty) token = t;
      } catch (_) {}
      return token;
    }
    throw MaException(401, r is Map ? (r['error'] ?? 'login failed').toString() : 'login failed');
  }

  Future<List<Map<String, dynamic>>> players() async {
    final r = await cmd('players/all', {'return_unavailable': false});
    return (r as List? ?? []).cast<Map<String, dynamic>>();
  }

  /// who is playing per panel (only on AJ server with the ajsnap provider)
  /// official Music Assistant doesn't have it and logs an ERROR per call -> ask again only every 30 min
  DateTime _ajRetry = DateTime(2000);
  Future<Map<String, Map<String, dynamic>>> ajPanels() async {
    if (DateTime.now().isBefore(_ajRetry)) return {};
    try {
      final r = await cmd('ajsnap/panels');
      return {for (final p in (r as List).cast<Map<String, dynamic>>()) p['player_id'] as String: p};
    } on MaException catch (e) {
      if (e.code == 400 || e.code == 404) _ajRetry = DateTime.now().add(const Duration(minutes: 30));
      return {};
    } catch (_) {
      return {};
    }
  }

  Future<void> setMembers(String leader, {List<String> add = const [], List<String> remove = const []}) =>
      cmd('players/cmd/set_members', {
        'target_player': leader,
        if (add.isNotEmpty) 'player_ids_to_add': add,
        if (remove.isNotEmpty) 'player_ids_to_remove': remove,
      });
  Future<void> ungroup(String id) => cmd('players/cmd/ungroup', {'player_id': id});
  Future<void> volume(String id, int v) => cmd('players/cmd/volume_set', {'player_id': id, 'volume_level': v});
  Future<void> groupVolume(String id, int v) => cmd('players/cmd/group_volume', {'player_id': id, 'volume_level': v});
  Future<void> mute(String id, bool m) => cmd('players/cmd/volume_mute', {'player_id': id, 'muted': m});
  Future<void> simple(String c, String id) => cmd('players/cmd/$c', {'player_id': id});
  Future<void> playUrl(String queueId, String url) =>
      cmd('player_queues/play_media', {'queue_id': queueId, 'media': url, 'option': 'replace'});

  // ---- groups / plugins ----
  /// loaded provider instances (domain, instance_id, ...)
  Future<List<Map<String, dynamic>>> providers() async {
    final r = await cmd('providers');
    return (r as List? ?? []).cast<Map<String, dynamic>>();
  }

  Future<String?> providerInstance(String domain) async {
    for (final p in await providers()) {
      if (p['domain'] == domain) return (p['instance_id'] ?? p['lookup_key'] ?? domain).toString();
    }
    return null;
  }

  /// permanent, named group ("Sync group" in Music Assistant) - returns the new group player
  Future<Map<String, dynamic>?> createGroup(String name, List<String> members) async {
    final inst = await providerInstance('sync_group');
    if (inst == null) throw MaException(404, 'Sync group provider not found in Music Assistant');
    final r = await cmd('players/create_group_player',
        {'provider': inst, 'name': name, 'members': members, 'dynamic': true});
    return r is Map<String, dynamic> ? r : null;
  }

  Future<void> removeGroup(String id) => cmd('players/remove_group_player', {'player_id': id});

  /// expose a player/group as AirPlay receiver ("<name> | Music Assistant")
  Future<bool> setAirplayReceiver(String playerId, bool on) async {
    final inst = await providerInstance('airplay_receiver');
    if (inst == null) return false;
    await cmd('config/players/save', {
      'player_id': playerId,
      'values': {'$inst||plugin||enabled': on},
    });
    return true;
  }

  /// player ids that currently have the AirPlay receiver enabled
  Future<Set<String>> airplayReceiverPlayers() async {
    try {
      final inst = await providerInstance('airplay_receiver');
      if (inst == null) return {};
      final v = await cmd('config/providers/get_value', {'instance_id': inst, 'key': 'connected_players'});
      return {for (final x in (v as List? ?? [])) x.toString()};
    } catch (_) {
      return {};
    }
  }
}

