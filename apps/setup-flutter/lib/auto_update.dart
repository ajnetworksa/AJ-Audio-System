import 'package:flutter/material.dart';
import 'api.dart';
import 'main.dart';
import 'tools.dart';
import 'ui.dart';

/// Automatic updates for all panels: when you publish a fix on GitHub, every panel installs it by itself
/// inside the time window you pick (never while music plays there). Needs AJ Panel 0.8.2+ with silent updates.
class AutoUpdatePage extends StatefulWidget {
  final Session s;
  final List<Found> panels;
  const AutoUpdatePage({super.key, required this.s, required this.panels});
  @override
  State<AutoUpdatePage> createState() => _AutoUpdatePageState();
}

class _AutoUpdatePageState extends State<AutoUpdatePage> {
  static const windows = {
    '03-05': 'Night 03:00 – 05:00',
    '01-06': 'Night 01:00 – 06:00',
    '22-06': 'Evening & night 22:00 – 06:00',
    '10-12': 'Morning 10:00 – 12:00',
    'any': 'Any time (when nothing plays)',
  };
  static const delays = {0: 'As soon as it is published', 12: '12 hours after publishing', 24: '1 day after publishing', 48: '2 days after publishing'};
  static const dayNames = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

  bool on = true;
  String window = '03-05';
  final Set<int> days = {1, 2, 3, 4, 5, 6, 7};
  int delay = 0;
  bool saving = false;
  final Map<String, String> result = {};

  List<Found> get panels => widget.panels.where((f) => f.hasApp).toList();

  @override
  void initState() {
    super.initState();
    // start from what the first panel has
    final i = panels.isEmpty ? null : panels.first.info;
    if (i != null && i['auto_update'] != null) {
      on = i['auto_update'] == true;
      if (windows.containsKey('${i['update_window']}')) window = '${i['update_window']}';
      final d = '${i['update_days'] ?? ''}';
      if (RegExp(r'^[1-7]+$').hasMatch(d)) {
        days
          ..clear()
          ..addAll(d.split('').map(int.parse));
      }
      final dl = int.tryParse('${i['update_delay_h']}') ?? 0;
      if (delays.containsKey(dl)) delay = dl;
    }
  }

  Future<void> save() async {
    if (days.isEmpty) {
      toast(context, 'Pick at least one day');
      return;
    }
    setState(() { saving = true; result.clear(); });
    final q = {
      'auto_update': '$on',
      'update_window': window,
      'update_days': (days.toList()..sort()).join(),
      'update_delay_h': '$delay',
    };
    for (final f in panels) {
      setState(() => result[f.ip] = 'saving…');
      try {
        await PanelApi.post(f.ip, widget.s.api.token, '/api/settings', q);
        var msg = 'saved';
        if (on && f.info?['silent_update'] != true) {
          // automatic updates need silent installs – switch them on over the network
          try {
            final o = await Adb.sh(f.ip, 'dpm set-device-owner com.ajnetwork.ajpanel/.AdminReceiver 2>&1');
            msg = o.contains('Success') || o.contains('already') ? 'saved · silent updates switched on' : 'saved – but silent updates not possible: ${o.trim().split('\n').first}';
          } catch (e) {
            msg = 'saved – silent updates still off (open Panel tools for this panel)';
          }
        }
        result[f.ip] = msg;
      } catch (e) {
        result[f.ip] = e is ApiException && e.code == 401 ? 'not connected to this server' : 'failed: $e';
      }
      if (mounted) setState(() {});
    }
    if (mounted) setState(() => saving = false);
  }

  @override
  Widget build(BuildContext context) => AjPage(
        title: 'Automatic updates',
        subtitle: 'When a fix is published on GitHub, every panel installs it by itself in this time window – '
            'never while music plays on it. Uses the panel\'s own clock.',
        bottom: GradButton(label: 'Save for ${panels.length} panels', icon: Icons.save_outlined, busy: saving, onPressed: panels.isEmpty ? null : save),
        children: [
          Glass(
            child: SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Install updates automatically', style: TextStyle(fontWeight: FontWeight.w600)),
              subtitle: Text(on ? 'On – panels update themselves' : 'Off – panels only show "Update available"', style: const TextStyle(color: C.sub)),
              value: on,
              onChanged: (v) => setState(() => on = v),
            ),
          ),
          if (on) ...[
            const Label('When'),
            for (final e in windows.entries)
              Glass(
                selected: window == e.key,
                onTap: () => setState(() => window = e.key),
                child: Text(e.value, style: const TextStyle(fontSize: 15.5)),
              ),
            const Label('Days'),
            Wrap(spacing: 8, runSpacing: 8, children: [
              for (var d = 1; d <= 7; d++)
                FilterChip(
                  label: Text(dayNames[d - 1]),
                  selected: days.contains(d),
                  onSelected: (v) => setState(() => v ? days.add(d) : days.remove(d)),
                ),
            ]),
            const Label('Test period'),
            for (final e in delays.entries)
              Glass(
                selected: delay == e.key,
                onTap: () => setState(() => delay = e.key),
                child: Text(e.value, style: const TextStyle(fontSize: 15.5)),
              ),
            const Padding(
              padding: EdgeInsets.fromLTRB(2, 0, 2, 8),
              child: Text('Tip: with a test period you can install a new version on one panel first ("Update panels"), '
                  'check it, and the others follow later by themselves.', style: TextStyle(color: C.sub, fontSize: 13, height: 1.4)),
            ),
          ],
          const Label('Panels'),
          for (final f in panels)
            Glass(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(f.name, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                Text(
                  f.info?['auto_update'] == null
                      ? 'v${f.version} – needs AJ Panel 0.8.2 or newer (update it once first)'
                      : '${f.info?['auto_update'] == true ? 'automatic ${f.info?['update_window']}' : 'automatic off'}'
                          ' · silent updates ${f.info?['silent_update'] == true ? 'on' : 'off'}',
                  style: const TextStyle(color: C.sub, fontSize: 13),
                ),
                if (result[f.ip] != null)
                  Text(result[f.ip]!, style: TextStyle(color: result[f.ip]!.startsWith('saved') ? C.ok : C.sub, fontSize: 13)),
              ]),
            ),
        ],
      );
}
