import 'dart:convert';
import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'api.dart';
import 'logs.dart';
import 'main.dart';
import 'ui.dart';

/// Android debug bridge over the network (port 5555) – native side: android/.../Adb.java.
/// Replaces the PC tools (deploy_ajpanel, panel_silent_updates, collect_logs, zigbee_test) on the phone.
class Adb {
  static const _ch = MethodChannel('aj/adb');
  static Future<String> connect(String ip) async => '${await _ch.invokeMethod('connect', {'ip': ip})}';
  static Future<String> sh(String ip, String cmd) async => '${await _ch.invokeMethod('shell', {'ip': ip, 'cmd': cmd})}';
  static Future<String> install(String ip, String path, {String flags = '-r -g'}) async =>
      '${await _ch.invokeMethod('install', {'ip': ip, 'path': path, 'flags': flags})}';
}

const _pkg = 'com.ajnetwork.ajpanel';
const _gateway = 'com.judian.gateway.c6';

/// single-quote for the panel's shell
String _q(String s) => "'${s.replaceAll("'", "'\\''")}'";

String _adbError(Object e) {
  final m = e is PlatformException ? (e.message ?? '$e') : '$e';
  if (m.contains('refused') || m.contains('ECONNREFUSED')) return 'The panel\'s network debugging (port 5555) is off. It has to be switched on once in the panel\'s developer settings.';
  if (m.contains('timed out') || m.contains('not allow')) return 'No answer – tap "Allow" on the panel screen (tick "Always allow from this computer") and try again.';
  return m;
}

// =============================================================== list of panels
class ToolsPage extends StatefulWidget {
  final Session s;
  const ToolsPage({super.key, required this.s});
  @override
  State<ToolsPage> createState() => _ToolsPageState();
}

class _ToolsPageState extends State<ToolsPage> {
  List<Found> found = [];
  bool scanning = false;
  final ip = TextEditingController();

  @override
  void initState() {
    super.initState();
    scan();
  }

  Future<void> scan() async {
    setState(() => scanning = true);
    final known = <String>[];
    try {
      for (final p in await widget.s.api.players()) {
        final r = Room(p);
        if (r.ip != null) known.add(r.ip!);
      }
    } catch (_) {}
    final r = await Net.scan(extra: known);
    if (mounted) setState(() { found = r; scanning = false; });
  }

  @override
  Widget build(BuildContext context) => AjPage(
        title: 'Panel tools',
        subtitle: 'Install AJ Panel, silent updates, diagnostics, Zigbee – straight from this phone, no PC. '
            'The first time, the panel asks "Allow debugging?": tick "Always allow" and tap Allow.',
        onRefresh: scan,
        action: IconButton(onPressed: scanning ? null : scan, icon: const Icon(Icons.radar), tooltip: 'Scan again'),
        children: [
          if (scanning) const LinearProgressIndicator(),
          for (final f in found)
            Glass(
              onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => PanelToolsPage(s: widget.s, ip: f.ip, name: f.hasApp ? f.name : ''))),
              child: Row(children: [
                Dot(f.hasApp ? C.ok : C.warn),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(f.hasApp ? f.name : f.ip, style: const TextStyle(fontSize: 16.5, fontWeight: FontWeight.w600)),
                    Text(f.hasApp ? '${f.ip} · AJ Panel v${f.version}' : 'no AJ Panel yet – tap to install', style: const TextStyle(color: C.sub, fontSize: 13)),
                  ]),
                ),
                const Icon(Icons.chevron_right, color: C.sub),
              ]),
            ),
          const Label('Other address'),
          Row(children: [
            Expanded(child: TextField(controller: ip, keyboardType: TextInputType.number, decoration: const InputDecoration(hintText: '192.168.8.x'))),
            const SizedBox(width: 10),
            FilledButton(
              onPressed: () {
                final a = ip.text.trim();
                if (a.isNotEmpty) Navigator.push(context, MaterialPageRoute(builder: (_) => PanelToolsPage(s: widget.s, ip: a, name: '')));
              },
              child: const Text('Open'),
            ),
          ]),
        ],
      );
}

// =============================================================== one panel
class PanelToolsPage extends StatefulWidget {
  final Session s;
  final String ip, name;
  const PanelToolsPage({super.key, required this.s, required this.ip, required this.name});
  @override
  State<PanelToolsPage> createState() => _PanelToolsPageState();
}

class _PanelToolsPageState extends State<PanelToolsPage> {
  final List<String> log = [];
  bool busy = false;
  String model = '', appVersion = '', zigbee = '';
  bool owner = false, hasZigbee = false;
  late final TextEditingController name = TextEditingController(text: widget.name);

  Api get api => widget.s.api;
  String get ip => widget.ip;

  @override
  void initState() {
    super.initState();
    refresh();
  }

  void say(String s) {
    if (mounted) setState(() => log.add(s));
  }

  Future<String> sh(String cmd) => Adb.sh(ip, cmd);

  Future<void> run(String what, Future<void> Function() f) async {
    if (busy) return;
    setState(() { busy = true; log.add('▶ $what'); });
    try {
      await f();
      say('✓ done');
    } catch (e) {
      say('✗ ${_adbError(e)}');
    }
    if (mounted) setState(() => busy = false);
    await refresh(quiet: true);
  }

  Future<void> refresh({bool quiet = false}) async {
    try {
      final out = await sh('getprop ro.product.manufacturer; getprop ro.product.model; '
          'dumpsys package $_pkg | grep versionName | head -1; '
          'dumpsys device_policy | grep -c $_pkg; getprop persist.sys.jd.has_zigbee; pm list packages -d | grep -c $_gateway');
      final l = const LineSplitter().convert(out).map((x) => x.trim()).toList();
      String at(int i) => i < l.length ? l[i] : '';
      if (!mounted) return;
      setState(() {
        model = '${at(0)} ${at(1)}'.trim();
        appVersion = at(2).replaceAll('versionName=', '');
        owner = (int.tryParse(at(3)) ?? 0) > 0;
        hasZigbee = at(4) == '1';
        zigbee = (int.tryParse(at(5)) ?? 0) > 0 ? 'Home Assistant (bridge)' : 'Tuya / Smart Life';
      });
    } catch (e) {
      if (!quiet) say('✗ ${_adbError(e)}');
    }
  }

  // ---------- install / set up ----------
  Future<String?> _apk({required bool pick}) async {
    if (pick) {
      final r = await FilePicker.platform.pickFiles(type: FileType.any);
      final p = r?.files.single.path;
      if (p == null) return null;
      if (!p.toLowerCase().endsWith('.apk')) throw 'Pick an .apk file';
      return p;
    }
    say('looking for the newest AJ Panel on GitHub (${widget.s.repo}) …');
    final rel = await Releases.latestPanel(widget.s.repo);
    if (rel == null) throw 'No AJ Panel release found – needs internet';
    say('downloading AJ Panel v${rel.$1} …');
    final r = await http.get(Uri.parse(rel.$2)).timeout(const Duration(minutes: 3));
    if (r.statusCode != 200) throw 'Download failed (${r.statusCode})';
    final f = File('${(await getTemporaryDirectory()).path}/AJ_Panel_v${rel.$1}.apk');
    await f.writeAsBytes(r.bodyBytes);
    return f.path;
  }

  Future<void> install({required bool pick}) => run('Install AJ Panel', () async {
        final path = await _apk(pick: pick);
        if (path == null) return;
        say('installing (watch the panel: if it asks, allow) …');
        var r = await Adb.install(ip, path);
        if (r.contains('UPDATE_INCOMPATIBLE') || r.contains('signatures do not match')) {
          final ok = await confirm(context, 'Different signing key',
              'The AJ Panel on this panel was built with another key. Remove it and install the new one? The panel keeps its room (player id) – you only connect it again here.',
              ok: 'Replace');
          if (!ok) throw 'Not replaced';
          await sh('am broadcast -n $_pkg/.BootReceiver -a $_pkg.RELEASE_OWNER; sleep 1; pm uninstall $_pkg');
          r = await Adb.install(ip, path);
        }
        say(r.trim().isEmpty ? '(no answer)' : r.trim());
        if (!r.contains('Success')) throw 'Install failed';
        await _setup();
      });

  /// what deploy_ajpanel does after installing: permissions, silent updates, server + login
  Future<void> _setup() async {
    say('permissions …');
    await sh('appops set $_pkg SYSTEM_ALERT_WINDOW allow; appops set $_pkg REQUEST_INSTALL_PACKAGES allow; '
        'dumpsys deviceidle whitelist +$_pkg; cmd appops set $_pkg RUN_IN_BACKGROUND allow; '
        'pm grant $_pkg android.permission.RECORD_AUDIO; appops set $_pkg PROJECT_MEDIA allow; '
        'cmd notification allow_listener $_pkg/$_pkg.MediaListener; '
        'am force-stop de.badaix.snapcast 2>/dev/null; true');
    final o = await sh('dpm set-device-owner $_pkg/.AdminReceiver 2>&1');
    say(o.contains('Success') || o.contains('already') ? 'silent updates: on' : 'silent updates: not possible (${o.trim().split('\n').first})');
    say('connecting to the music server ${api.host} …');
    final mac = (await sh('cat /sys/class/net/wlan0/address 2>/dev/null || cat /sys/class/net/eth0/address 2>/dev/null')).trim().toLowerCase();
    final n = name.text.trim().isEmpty ? (model.isEmpty ? ip : model) : name.text.trim();
    final token = await api.panelToken(n);
    final repair = await api.repairToken();
    final port = Uri.parse(api.base).port;
    final args = StringBuffer('am broadcast -f 32 -n $_pkg/.BootReceiver -a $_pkg.CONFIG --es host ${_q(api.host)} --ei ma_port $port --es name ${_q(n)} --es token ${_q(token)}');
    if (repair != null) args.write(' --es repair_token ${_q(repair)}');
    if (RegExp(r'^([0-9a-f]{2}:){5}[0-9a-f]{2}$').hasMatch(mac) && mac != '00:00:00:00:00:00' && mac != '02:00:00:00:00:00') {
      args.write(' --es host_id_default ajpanel_${mac.replaceAll(':', '')}');
    }
    await sh(args.toString()); // never shown: holds the panel's token
    say('panel "$n" connected – it appears under Rooms in a few seconds');
  }

  // ---------- other tools ----------
  Future<void> silent(bool on) => run(on ? 'Silent updates on' : 'Silent updates off', () async {
        if (on) {
          final o = await sh('dpm set-device-owner $_pkg/.AdminReceiver 2>&1');
          say(o.trim());
          if (o.contains('accounts')) say('→ remove the account in the panel\'s Settings → Accounts, then try again');
        } else {
          await sh('am broadcast -n $_pkg/.BootReceiver -a $_pkg.RELEASE_OWNER');
        }
      });

  Future<void> restartApp() => run('Restart AJ Panel', () async {
        await sh('am force-stop $_pkg; sleep 1; am broadcast -f 32 -n $_pkg/.BootReceiver -a $_pkg.START');
      });

  Future<void> reboot() async {
    if (!await confirm(context, 'Restart the panel?', 'Takes about 1–2 minutes. Music on this panel stops meanwhile.', ok: 'Restart')) return;
    await run('Restart panel', () async {
      try { await sh('reboot'); } catch (_) {} // connection drops while rebooting
    });
  }

  Future<String> diagnostics() async {
    final out = await sh('echo "=== ${ip} \$(date)"; getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; '
        'echo "--- AJ Panel"; dumpsys package $_pkg | grep -E "versionName|lastUpdateTime"; '
        'echo "--- uptime"; uptime; echo "--- network"; ip -4 addr show | grep inet; ping -c 3 -W 1 ${api.host} | tail -2; '
        'echo "--- processes"; ps -A | grep -iE "ajpanel|snap|gateway|judian"; '
        'echo "--- cpu (top)"; top -b -n 1 -m 12 2>/dev/null | head -20; '
        'echo "--- wifi"; dumpsys wifi | grep -iE "mWifiInfo|RSSI|Link speed|Frequency" | head -4; '
        'echo "--- zigbee"; getprop persist.sys.jd.has_zigbee; stty -F /dev/ttyS3 -a 2>&1 | head -6; pm list packages -d; '
        'echo "--- AJ Panel log"; logcat -d -v time -s AJPanel | tail -n 1500');
    return out;
  }

  Future<void> zigbeeToHa() async {
    if (!await confirm(context, 'Use this panel\'s Zigbee radio in Home Assistant?',
        'The Tuya/Smart Life gateway on this panel is switched off and the radio is passed to Home Assistant (ZHA). '
        'Devices paired in Smart Life have to be paired again in Home Assistant. A backup of the Tuya Zigbee files is saved on this phone first. '
        'Undo any time with "Back to Tuya".', ok: 'Switch')) return;
    await run('Zigbee → Home Assistant', () async {
      say('backing up the Tuya Zigbee files …');
      final b64 = (await sh('cd /storage/emulated/0 && tar -cz .jd-tuya 2>/dev/null | base64')).replaceAll(RegExp(r'\s'), '');
      final bytes = b64.isEmpty ? <int>[] : base64.decode(b64);
      if (bytes.length < 200) throw 'Backup failed (no Tuya Zigbee files found) – nothing was changed';
      final dir = await getApplicationDocumentsDirectory();
      final f = File('${dir.path}/zigbee_backup_${ip}_${DateTime.now().millisecondsSinceEpoch}.tgz');
      await f.writeAsBytes(bytes);
      say('backup saved (${bytes.length} bytes) – keep it: "Share backup" below');
      lastBackup = f.path;
      await sh('am force-stop $_gateway; pm disable-user --user 0 $_gateway; '
          'am broadcast -f 32 -n $_pkg/.BootReceiver -a $_pkg.CONFIG --ez zigbee_bridge true --es zigbee_allow ha');
      say('radio switched. In Home Assistant: Add integration → Zigbee Home Automation → Enter manually → EZSP → '
          'socket://$ip:6638, 115200 → Advanced → "Create a new network".');
    });
  }

  String? lastBackup;

  Future<void> zigbeeToTuya() async {
    if (!await confirm(context, 'Back to Tuya / Smart Life?', 'Remove the ZHA integration in Home Assistant first. The panel restarts.', ok: 'Switch back')) return;
    await run('Zigbee → Tuya', () async {
      await sh('am broadcast -f 32 -n $_pkg/.BootReceiver -a $_pkg.CONFIG --ez zigbee_bridge false; pm enable $_gateway');
      try { await sh('reboot'); } catch (_) {}
      say('panel restarting – Smart Life shows the gateway again in 2–3 minutes');
    });
  }

  Widget tile(IconData i, String t, String sub, VoidCallback? onTap) => Glass(
        onTap: busy ? null : onTap,
        child: Row(children: [
          IconBadge(i),
          const SizedBox(width: 14),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(t, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              Text(sub, style: const TextStyle(color: C.sub, fontSize: 13)),
            ]),
          ),
        ]),
      );

  @override
  Widget build(BuildContext context) => AjPage(
        title: widget.name.isEmpty ? ip : widget.name,
        subtitle: [
          ip,
          if (model.isNotEmpty) model,
          appVersion.isEmpty ? 'AJ Panel not installed' : 'AJ Panel $appVersion',
          if (appVersion.isNotEmpty) owner ? 'silent updates on' : 'silent updates off',
        ].join(' · '),
        onRefresh: refresh,
        children: [
          tile(Icons.my_location, 'Locate this panel', 'Screen flashes green with its name and it beeps (30 s, tap the panel to stop)', () async {
            try {
              await PanelApi.locate(ip);
              say('locating – look for the flashing panel');
            } catch (_) {
              // older/no AJ Panel: at least wake the screen over adb
              try { await sh('input keyevent KEYCODE_WAKEUP'); say('screen switched on (Locate needs AJ Panel 0.8.3+)'); } catch (e) { say('✗ ${_adbError(e)}'); }
            }
          }),
          const Label('Install & set up'),
          TextField(controller: name, decoration: const InputDecoration(labelText: 'Room name (e.g. Living room)')),
          const SizedBox(height: 10),
          tile(Icons.cloud_download_outlined, appVersion.isEmpty ? 'Install AJ Panel (newest)' : 'Reinstall / update AJ Panel (newest)',
              'Downloads from GitHub, installs, sets permissions, connects to ${api.host}', () => install(pick: false)),
          tile(Icons.folder_open_outlined, 'Install from an APK file', 'An AJ Panel APK saved on this phone', () => install(pick: true)),
          tile(Icons.tune, 'Repair set-up', 'Permissions, silent updates and server connection again', () => run('Repair set-up', _setup)),
          const Label('Updates'),
          tile(owner ? Icons.toggle_on : Icons.toggle_off_outlined, owner ? 'Silent updates: ON' : 'Silent updates: off',
              owner ? 'Tap to switch off (needed before removing AJ Panel)' : 'Updates install without tapping Install on the panel', () => silent(!owner)),
          const Label('Diagnostics'),
          tile(Icons.receipt_long_outlined, 'Panel log & system info', 'AJ Panel log, network, clock, versions – share it',
              () => Navigator.push(context, MaterialPageRoute(builder: (_) => LogView(title: '${name.text.isEmpty ? ip : name.text} diagnostics', loader: diagnostics)))),
          tile(Icons.restart_alt, 'Restart AJ Panel', 'Stops and starts the app (music on this panel pauses briefly)', restartApp),
          tile(Icons.power_settings_new, 'Restart the panel', 'Reboots Android (1–2 minutes)', reboot),
          if (hasZigbee) ...[
            Label('Zigbee radio · now: $zigbee'),
            tile(Icons.hub_outlined, 'Use radio in Home Assistant', 'Backs up the Tuya files, switches the radio to ZHA', zigbeeToHa),
            tile(Icons.undo, 'Back to Tuya / Smart Life', 'Gateway app on again, panel restarts', zigbeeToTuya),
            if (lastBackup != null)
              tile(Icons.share_outlined, 'Share Zigbee backup', 'Contains the Zigbee network key – keep it private',
                  () => Share.shareXFiles([XFile(lastBackup!)], subject: 'Zigbee backup $ip')),
          ],
          if (log.isNotEmpty || busy) ...[
            const Label('Progress'),
            Glass(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                if (busy) const LinearProgressIndicator(),
                for (final l in log.reversed.take(40))
                  Padding(padding: const EdgeInsets.only(top: 4), child: Text(l, style: const TextStyle(fontSize: 13, height: 1.3))),
              ]),
            ),
          ],
        ],
      );
}
