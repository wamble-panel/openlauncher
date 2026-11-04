import 'dart:convert';
import 'package:flutter/widgets.dart' hide Text;
import '../i18n.dart';
import '../bridge.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/pressable.dart';

/// Car buttons the driver has given a job: button code → action. Loaded once at start
/// and kept here so the shell can look a button up instantly.
class CarKeys {
  CarKeys._();
  static Map<int, String> map = {};

  /// While set, the next car button goes here instead of doing anything (learning).
  static void Function(int code)? capture;

  static const actions = [
    ('home', 'Home', MibIcons.home),
    ('back', 'Back', MibIcons.arrow_back),
    ('radio', 'Radio', MibIcons.cell_tower),
    ('music', 'Media', MibIcons.music_note),
    ('phone', 'Telephone', MibIcons.call),
    ('nav', 'Navigation', MibIcons.near_me),
    ('settings', 'Settings', MibIcons.settings),
    ('sound', 'Sound', MibIcons.equalizer),
    ('toggle', 'Play / pause', MibIcons.play_arrow),
    ('next', 'Next track', MibIcons.skip_next),
    ('prev', 'Previous track', MibIcons.skip_previous),
  ];

  /// The knob: never re-assigned, so the screen can always be driven.
  static const knob = {5, 6, 19, 21, 66, 37};

  static void load(String? json) {
    try {
      final m = (jsonDecode(json ?? '{}') as Map).map((k, v) => MapEntry(int.parse('$k'), '$v'));
      map = m;
    } catch (_) {
      map = {};
    }
  }

  static Future<void> save() => Bridge.call('settingsSet', {'key': 'keymap', 'value': jsonEncode(map.map((k, v) => MapEntry('$k', v)))});
}

/// Settings · Car · Car buttons: choose a job, then press the car's button for it.
/// Built-in buttons (station 1-6, RADIO, MEDIA, PHONE, SETTINGS, TONE, BACK, the knob)
/// keep working; a button set here replaces what it did before.
class CarKeysPage extends StatefulWidget {
  const CarKeysPage({super.key});
  @override
  State<CarKeysPage> createState() => _CarKeysPageState();
}

class _CarKeysPageState extends State<CarKeysPage> {
  String? _learning;
  String? _note;

  @override
  void initState() {
    super.initState();
    Bridge.call<Map<Object?, Object?>>('settingsGet').then((m) {
      if (m == null || !mounted) return;
      CarKeys.load(m['keymap'] as String?);
      setState(() {});
    });
  }

  @override
  void dispose() {
    CarKeys.capture = null;
    super.dispose();
  }

  void _learn(String action) {
    setState(() {
      _learning = action;
      _note = null;
    });
    CarKeys.capture = (code) {
      if (CarKeys.knob.contains(code)) {
        setState(() => _note = 'That is the knob; it stays as it is. Press another button.');
        return;
      }
      CarKeys.capture = null;
      CarKeys.map[code] = action;
      CarKeys.save();
      Bridge.chime();
      setState(() {
        _learning = null;
        _note = 'Button $code now opens ${CarKeys.actions.firstWhere((a) => a.$1 == action).$2}.';
      });
    };
  }

  void _clear(String action) {
    CarKeys.map.removeWhere((_, a) => a == action);
    CarKeys.save();
    setState(() => _note = null);
  }

  @override
  Widget build(BuildContext context) {
    return ListView(padding: const EdgeInsets.only(top: 8, bottom: 24), children: [
      Container(
        margin: const EdgeInsets.fromLTRB(10, 6, 10, 10),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
        decoration: Mib.glass(),
        child: Text(
          _learning != null
              ? 'Press the button on the car for "${CarKeys.actions.firstWhere((a) => a.$1 == _learning).$2}" now…'
              : (_note ?? 'Tap a job, then press the car button for it. Hold a job to clear its buttons.'),
          style: Mib.text(23, color: _learning != null ? Mib.white : const Color(0xFFDADADA)),
        ),
      ),
      for (final a in CarKeys.actions)
        Pressable(
          onTap: () => _learning == a.$1 ? setState(() { _learning = null; CarKeys.capture = null; }) : _learn(a.$1),
          onLongPress: () => _clear(a.$1),
          child: Container(
            height: 74,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: BoxDecoration(
              color: _learning == a.$1 ? const Color(0x22FFFFFF) : null,
              border: const Border(bottom: BorderSide(color: Color(0x40FFFFFF))),
            ),
            child: Row(children: [
              Icon(a.$3, size: 34, color: Mib.white),
              const SizedBox(width: 22),
              Expanded(child: Text(a.$2, style: Mib.text(27))),
              Text(
                _learning == a.$1
                    ? 'waiting…'
                    : (() {
                        final codes = [for (final e in CarKeys.map.entries) if (e.value == a.$1) e.key];
                        return codes.isEmpty ? 'no button set' : codes.map((c) => 'button $c').join(', ');
                      })(),
                style: Mib.text(22, color: Mib.grey),
              ),
            ]),
          ),
        ),
    ]);
  }
}
