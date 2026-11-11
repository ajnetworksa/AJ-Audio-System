import 'package:flutter/material.dart';

/// AJ look (mockups "AJ Audio UI Revamp"): deep blue → purple gradient, glass cards, blue→violet accent.
class C {
  static const text = Color(0xFFF2F4F8);
  static const sub = Color(0xFFB9C2D0);
  static const accent = Color(0xFF40A0FF);
  static const accent2 = Color(0xFF9A86FF);
  static const card = Color(0xE61A2333);
  static const card2 = Color(0xFF232D40);
  static const line = Color(0xFF3A4760);
  static const ok = Color(0xFF8FE0AE);
  static const warn = Color(0xFFF5B547);
  static const bad = Color(0xFFFF8A80);
  static const ink = Color(0xFF0B1220);
}

ThemeData ajTheme() {
  final base = ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: ColorScheme.fromSeed(seedColor: C.accent, brightness: Brightness.dark),
  );
  return base.copyWith(
    scaffoldBackgroundColor: Colors.transparent,
    appBarTheme: const AppBarTheme(backgroundColor: Colors.transparent, elevation: 0, foregroundColor: C.text),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: const Color(0xFF0F1622),
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: C.line)),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: C.line)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: C.accent, width: 2)),
      labelStyle: const TextStyle(color: C.sub),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? Colors.white : C.sub),
      trackColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? C.accent : C.card2),
    ),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
  );
}

/// page background with the gradient
class Backdrop extends StatelessWidget {
  final Widget child;
  const Backdrop({super.key, required this.child});
  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xFF1C2C52), Color(0xFF0F1622), Color(0xFF2A1640)],
            stops: [0, 0.5, 1],
          ),
        ),
        child: child,
      );
}

/// scaffold with backdrop + title + optional action
class AjPage extends StatelessWidget {
  final String title;
  final String? subtitle;
  final List<Widget> children;
  final Widget? action;
  final Widget? bottom;
  final Future<void> Function()? onRefresh;
  const AjPage({super.key, required this.title, this.subtitle, required this.children, this.action, this.bottom, this.onRefresh});
  @override
  Widget build(BuildContext context) {
    final list = ListView(
      padding: const EdgeInsets.fromLTRB(18, 8, 18, 24),
      children: [
        if (subtitle != null) Padding(padding: const EdgeInsets.only(bottom: 12), child: Text(subtitle!, style: const TextStyle(color: C.sub, fontSize: 14, height: 1.4))),
        ...children,
      ],
    );
    return Backdrop(
      child: Scaffold(
        appBar: AppBar(
          title: Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 22)),
          actions: [if (action != null) Padding(padding: const EdgeInsets.only(right: 12), child: action!)],
        ),
        body: onRefresh == null ? list : RefreshIndicator(onRefresh: onRefresh!, child: list),
        bottomNavigationBar: bottom == null ? null : SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(18, 0, 18, 12), child: bottom!)),
      ),
    );
  }
}

class Glass extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;
  final bool selected;
  final VoidCallback? onTap;
  const Glass({super.key, required this.child, this.padding = const EdgeInsets.all(14), this.selected = false, this.onTap});
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: onTap,
            child: Ink(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(16),
                color: selected ? null : C.card,
                gradient: selected ? const LinearGradient(colors: [Color(0xFF1E3A66), Color(0xFF2B2156)]) : null,
                border: Border.all(color: selected ? C.accent : const Color(0x00000000), width: selected ? 2 : 0),
              ),
              child: Padding(padding: padding, child: child),
            ),
          ),
        ),
      );
}

class GradButton extends StatelessWidget {
  final String label;
  final IconData? icon;
  final VoidCallback? onPressed;
  final bool busy;
  const GradButton({super.key, required this.label, this.icon, this.onPressed, this.busy = false});
  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null && !busy;
    return Opacity(
      opacity: enabled ? 1 : 0.5,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: enabled ? onPressed : null,
          child: Ink(
            height: 54,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              gradient: const LinearGradient(colors: [C.accent, C.accent2]),
            ),
            child: Center(
              child: busy
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: C.ink))
                  : Row(mainAxisSize: MainAxisSize.min, children: [
                      if (icon != null) Padding(padding: const EdgeInsets.only(right: 8), child: Icon(icon, color: C.ink, size: 20)),
                      Text(label, style: const TextStyle(color: C.ink, fontWeight: FontWeight.w700, fontSize: 16)),
                    ]),
            ),
          ),
        ),
      ),
    );
  }
}

class OutlineBtn extends StatelessWidget {
  final String label;
  final IconData icon;
  final VoidCallback? onPressed;
  const OutlineBtn({super.key, required this.label, required this.icon, this.onPressed});
  @override
  Widget build(BuildContext context) => OutlinedButton.icon(
        onPressed: onPressed,
        icon: Icon(icon, size: 18),
        label: Text(label, overflow: TextOverflow.ellipsis),
        style: OutlinedButton.styleFrom(
          foregroundColor: C.text,
          backgroundColor: C.card,
          side: const BorderSide(color: C.line),
          minimumSize: const Size(0, 52),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ),
      );
}

class Label extends StatelessWidget {
  final String text;
  const Label(this.text, {super.key});
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(2, 14, 2, 8),
        child: Text(text.toUpperCase(), style: const TextStyle(color: C.sub, fontSize: 12.5, fontWeight: FontWeight.w700, letterSpacing: 1)),
      );
}

class Dot extends StatelessWidget {
  final Color color;
  const Dot(this.color, {super.key});
  @override
  Widget build(BuildContext context) =>
      Container(width: 10, height: 10, decoration: BoxDecoration(color: color, shape: BoxShape.circle));
}

class IconBadge extends StatelessWidget {
  final IconData icon;
  const IconBadge(this.icon, {super.key});
  @override
  Widget build(BuildContext context) => Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          gradient: const LinearGradient(colors: [Color(0xFF1E3A66), Color(0xFF2B2156)]),
        ),
        child: Icon(icon, color: const Color(0xFF9CCBFF), size: 22),
      );
}

/// row with label + trailing widget (switch, value…)
class SettingRow extends StatelessWidget {
  final String label;
  final String? hint;
  final Widget trailing;
  const SettingRow({super.key, required this.label, this.hint, required this.trailing});
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label, style: const TextStyle(fontSize: 15.5)),
              if (hint != null) Text(hint!, style: const TextStyle(color: C.sub, fontSize: 12.5)),
            ]),
          ),
          trailing,
        ]),
      );
}

void toast(BuildContext context, String msg) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
}

Future<bool> confirm(BuildContext context, String title, String text, {String ok = 'Yes'}) async {
  final r = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      backgroundColor: const Color(0xFF1A2333),
      title: Text(title),
      content: Text(text),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(ok)),
      ],
    ),
  );
  return r == true;
}

/// tick chips for choosing rooms
class RoomChips extends StatelessWidget {
  final List<(String id, String name, bool online)> rooms;
  final Set<String> selected;
  final ValueChanged<String> onToggle;
  const RoomChips({super.key, required this.rooms, required this.selected, required this.onToggle});
  @override
  Widget build(BuildContext context) => Wrap(spacing: 8, runSpacing: 8, children: [
        for (final r in rooms)
          FilterChip(
            label: Text(r.$2 + (r.$3 ? '' : ' · offline')),
            selected: selected.contains(r.$1),
            onSelected: (_) => onToggle(r.$1),
            selectedColor: const Color(0xFF1E3A66),
            checkmarkColor: const Color(0xFF9CCBFF),
            side: BorderSide(color: selected.contains(r.$1) ? C.accent : C.line),
            backgroundColor: const Color(0xFF0F1622),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
          ),
      ]);
}
