import 'dart:io';
import 'dart:typed_data';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'api.dart';
import 'main.dart';
import 'ui.dart';

/// Update many panels at once: newest release from GitHub, or an APK file picked on this phone.
class BulkUpdatePage extends StatefulWidget {
  final Session s;
  const BulkUpdatePage({super.key, required this.s});
  @override
  State<BulkUpdatePage> createState() => _BulkUpdatePageState();
}

class _BulkUpdatePageState extends State<BulkUpdatePage> {
  List<Found> panels = [];
  final Set<String> picked = {};
  final Map<String, String> result = {}; // ip -> message
  bool loading = true, running = false;
  (String, String)? latest; // version, url on GitHub
  String? filePath, fileName;
  bool useFile = false;

  Api get api => widget.s.api;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() => loading = true);
    try {
      final known = <String>[];
      try {
        for (final p in await api.players()) {
          final r = Room(p);
          if (r.ip != null) known.add(r.ip!);
        }
      } catch (_) {}
      final rel = await Releases.latestPanel(widget.s.repo);
      final found = (await Net.scan(extra: known)).where((f) => f.hasApp).toList();
      if (!mounted) return;
      setState(() {
        panels = found;
        latest = rel;
        picked
          ..clear()
          ..addAll(found.where(needs).map((f) => f.ip));
      });
    } catch (e) {
      if (mounted) toast(context, '$e');
    }
    if (mounted) setState(() => loading = false);
  }

  /// older than the newest release?
  bool needs(Found f) => latest != null && newerVersion(latest!.$1, f.version);

  Future<void> pickFile() async {
    try {
      final r = await FilePicker.platform.pickFiles(type: FileType.any);
      final path = r?.files.single.path;
      if (path == null) return;
      if (!path.toLowerCase().endsWith('.apk')) {
        if (mounted) toast(context, 'Pick an .apk file');
        return;
      }
      setState(() { filePath = path; fileName = r!.files.single.name; useFile = true; });
    } catch (e) {
      if (mounted) toast(context, 'Could not open the file: $e');
    }
  }

  Future<void> run() async {
    final todo = panels.where((f) => picked.contains(f.ip)).toList();
    if (todo.isEmpty) return;
    final what = useFile ? fileName ?? 'the picked file' : 'AJ Panel v${latest!.$1}';
    if (!await confirm(context, 'Update ${todo.length} panels?',
        'Sends $what to each panel. On every panel someone taps "Install" once on its screen.', ok: 'Send')) return;
    setState(() { running = true; result.clear(); });
    Uint8List? bytes;
    if (useFile) {
      try {
        bytes = await File(filePath!).readAsBytes();
      } catch (e) {
        if (mounted) toast(context, 'Could not read the file: $e');
        setState(() => running = false);
        return;
      }
    }
    for (final f in todo) {
      setState(() => result[f.ip] = 'sending…');
      try {
        if (bytes != null) {
          await PanelApi.upload(f.ip, api.token, bytes);
        } else {
          await PanelApi.post(f.ip, api.token, '/api/update', {'url': latest!.$2});
        }
        result[f.ip] = 'sent – tap Install on the panel';
      } catch (e) {
        result[f.ip] = e is ApiException && e.code == 401 ? 'not connected to this server (set it up first)' : 'failed: $e';
      }
      if (mounted) setState(() {});
    }
    if (mounted) setState(() => running = false);
  }

  @override
  Widget build(BuildContext context) {
    final canRun = !running && picked.isNotEmpty && (useFile ? filePath != null : latest != null);
    return AjPage(
      title: 'Update panels',
      subtitle: 'Pick what to install and which panels get it.',
      onRefresh: load,
      bottom: GradButton(
        label: picked.isEmpty ? 'Pick panels' : 'Update ${picked.length} panels',
        icon: Icons.system_update_alt,
        busy: running,
        onPressed: canRun ? run : null,
      ),
      children: [
        const Label('What to install'),
        Glass(
          selected: !useFile,
          onTap: () => setState(() => useFile = false),
          child: Row(children: [
            const IconBadge(Icons.cloud_download_outlined),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Newest release', style: TextStyle(fontSize: 16.5, fontWeight: FontWeight.w600)),
                Text(latest == null ? (loading ? 'Checking GitHub…' : 'No release found (${widget.s.repo}) – needs internet') : 'AJ Panel v${latest!.$1} from GitHub',
                    style: const TextStyle(color: C.sub, fontSize: 13)),
              ]),
            ),
          ]),
        ),
        Glass(
          selected: useFile,
          onTap: pickFile,
          child: Row(children: [
            const IconBadge(Icons.folder_open_outlined),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('An APK file on this phone', style: TextStyle(fontSize: 16.5, fontWeight: FontWeight.w600)),
                Text(fileName ?? 'Tap to choose (any app, e.g. an older AJ Panel)', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: C.sub, fontSize: 13)),
              ]),
            ),
          ]),
        ),
        Row(children: [
          const Expanded(child: Label('Panels')),
          TextButton(
            onPressed: panels.isEmpty
                ? null
                : () => setState(() {
                      if (picked.length == panels.length) {
                        picked.clear();
                      } else {
                        picked.addAll(panels.map((f) => f.ip));
                      }
                    }),
            child: Text(picked.length == panels.length && panels.isNotEmpty ? 'None' : 'All'),
          ),
        ]),
        if (loading) const LinearProgressIndicator(),
        if (!loading && panels.isEmpty) const Glass(child: Text('No panels with AJ Panel found on this network.')),
        for (final f in panels)
          Glass(
            onTap: running
                ? null
                : () => setState(() {
                      if (!picked.remove(f.ip)) picked.add(f.ip);
                    }),
            child: Row(children: [
              Icon(picked.contains(f.ip) ? Icons.check_circle : Icons.radio_button_unchecked, color: picked.contains(f.ip) ? C.accent : C.sub),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(f.name, style: const TextStyle(fontSize: 16.5, fontWeight: FontWeight.w600)),
                  Text('${f.ip} · v${f.version}${needs(f) ? ' → v${latest!.$1}' : latest != null ? ' · up to date' : ''}',
                      style: TextStyle(color: needs(f) ? C.warn : C.sub, fontSize: 13)),
                  if (result[f.ip] != null)
                    Text(result[f.ip]!, style: TextStyle(color: result[f.ip]!.startsWith('sent') ? C.ok : result[f.ip]!.startsWith('sending') ? C.sub : C.bad, fontSize: 13)),
                ]),
              ),
            ]),
          ),
      ],
    );
  }
}
