import 'dart:io' show Platform;
import 'package:flutter/services.dart';

/// "Play my phone" (Android 10+): native capture service, see android/app/src/main/java/.../CaptureService.java
class PhoneCapture {
  static const _ch = MethodChannel('aj/capture');
  static bool get supported => Platform.isAndroid;

  static Future<void> start({required String server, required String token, required String target, required String targetName}) =>
      _ch.invokeMethod('start', {'server': server, 'token': token, 'target': target, 'targetName': targetName});

  static Future<void> stop() => _ch.invokeMethod('stop');

  /// give the native side the server + token (for the Quick Settings picker) and the mute setting
  static Future<void> configure({required String server, required String token, required bool mute}) async {
    if (!supported) return;
    try {
      await _ch.invokeMethod('configure', {'server': server, 'token': token, 'mute': mute});
    } catch (_) {}
  }

  static Future<Map<String, dynamic>> status() async {
    try {
      final r = await _ch.invokeMethod('status');
      return Map<String, dynamic>.from(r as Map);
    } catch (_) {
      return {'running': false};
    }
  }
}
