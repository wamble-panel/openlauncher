import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';

/// Settings: eight category tiles, 4 × 2 (design: 323 × 280 px tiles, 24 px gaps).
class SettingsGrid extends StatelessWidget {
  const SettingsGrid({super.key, required this.onOpen});
  final ValueChanged<String> onOpen;

  static const tiles = [
    ('general', 'General', MibIcons.tune),
    ('display', 'Display &\nbrightness', MibIcons.light_mode),
    ('sound', 'Sound', MibIcons.volume_up),
    ('connections', 'Bluetooth &\nconnections', MibIcons.bluetooth),
    ('navset', 'Navigation', MibIcons.near_me),
    ('carset', 'Car', MibIcons.directions_car),
    ('language', 'Language &\nkeyboard', MibIcons.language),
    ('system', 'System &\nupdates', MibIcons.settings_suggest),
  ];

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 42, left: 8),
      child: Column(children: [
        for (var row = 0; row < 2; row++) ...[
          if (row > 0) const SizedBox(height: 33),
          SizedBox(
            height: 222,
            child: Row(children: [
              for (var col = 0; col < 4; col++) ...[
                if (col > 0) const SizedBox(width: 19),
                Expanded(child: _Tile(tiles[row * 4 + col], onOpen)),
              ],
            ]),
          ),
        ],
      ]),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile(this.t, this.onOpen);
  final (String, String, IconData) t;
  final ValueChanged<String> onOpen;
  @override
  Widget build(BuildContext context) {
    return Pressable(
      onTap: () => onOpen(t.$1),
      child: Container(
        decoration: BoxDecoration(
          border: Border.all(color: const Color(0x80FFFFFF), width: 1.2),
          gradient: const LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(0xFF0B0B0B), Color(0xFF000000)]),
        ),
        child: Column(children: [
          const SizedBox(height: 40),
          Icon(t.$3, size: 76, color: Mib.white),
          Expanded(child: Center(child: Text(t.$2, textAlign: TextAlign.center, style: Mib.text(25, height: 1.12)))),
          const SizedBox(height: 14),
        ]),
      ),
    );
  }
}

/// Scrolling settings list under a header.
class SettingsList extends StatelessWidget {
  const SettingsList({super.key, required this.children});
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => ListView(padding: const EdgeInsets.only(top: 12, bottom: 24), children: children);
}

/// Shared state of the settings pages, read from and written to the car side.
mixin SettingsState<T extends StatefulWidget> on State<T>, Live<T> {
  @override
  String get liveMethod => 'settingsGet';
  Object? v(String key, Object? or) => live?[key] ?? or;
  bool b(String key) => v(key, false) == true;
  void set(String key, Object value) {
    setState(() => live = {...?live, key: value});
    Bridge.call('settingsSet', {'key': key, 'value': value});
  }
}

/// Settings · Display (design 39).
class DisplayPage extends StatefulWidget {
  const DisplayPage({super.key});
  @override
  State<DisplayPage> createState() => _DisplayPageState();
}

class _DisplayPageState extends State<DisplayPage> with Live<DisplayPage>, SettingsState<DisplayPage> {
  @override
  Widget build(BuildContext context) {
    final canWrite = v('canWrite', true) == true;
    return SettingsList(children: [
      if (!canWrite)
        MibRow(label: 'Allow brightness control', sub: 'one-time permission', onTap: () => Bridge.call('system', {'what': 'write_settings'}))
      else ...[
        const MibCaption('Brightness', top: 14),
        MibStepSlider(value: (v('brightness', 0.55) as num).toDouble(), onChanged: (x) => set('brightness', x)),
        const MibCaption('Day / night mode', top: 28),
        MibSegmented(options: const ['Auto', 'Day', 'Night'], selected: (v('dayNight', 0) as num).toInt(), onSelect: (i) => set('dayNight', i)),
        const SizedBox(height: 18),
      ],
      MibRow(label: 'Touch sounds', trailing: MibToggle(value: b('touchSounds'), onChanged: (x) => set('touchSounds', x))),
      MibRow(label: 'Screen off while parked', trailing: MibToggle(value: b('screenOff'), onChanged: (x) => set('screenOff', x))),
      MibRow(label: 'Clock format', value: b('clock24') ? '24 h' : '12 h', onTap: () => set('clock24', !b('clock24'))),
    ]);
  }
}

/// The other categories: rows backed by the car side; pages not yet in Flutter open classic.
class CategoryPage extends StatefulWidget {
  const CategoryPage({super.key, required this.category, required this.onOpen});
  final String category;
  final ValueChanged<String> onOpen;
  @override
  State<CategoryPage> createState() => _CategoryPageState();
}

class _CategoryPageState extends State<CategoryPage> with Live<CategoryPage>, SettingsState<CategoryPage> {
  Widget toggle(String label, String key, {String? sub}) =>
      MibRow(label: label, sub: sub, trailing: MibToggle(value: b(key), onChanged: (x) => set(key, x)));
  Widget open(String label, String key, {String? value, IconData? icon, String? sub}) =>
      MibRow(label: label, value: value, sub: sub, icon: icon, onTap: () => widget.onOpen(key));
  Widget system(String label, String what, {String? sub}) =>
      MibRow(label: label, sub: sub, onTap: () => Bridge.call('system', {'what': what}));

  @override
  Widget build(BuildContext context) {
    final rows = switch (widget.category) {
      'general' => [
          MibRow(label: 'Units', value: b('metric') ? 'km, km/h' : 'mi, mph', onTap: () => set('metric', !b('metric'))),
          toggle('Speed camera alerts', 'cameraAlerts', sub: 'on every page, also without the map'),
          toggle('Call bar on the map', 'callBar'),
          toggle('Keep music playing in reverse', 'keepMusicReverse'),
          toggle('Touch sounds', 'touchSounds'),
        ],
      'connections' => [
          open('Connect mobile device', 'btsearch', icon: MibIcons.bluetooth_searching),
          open('Phone mirroring', 'classic:BT_HUB', icon: MibIcons.mobile_share),
          MibRow(label: 'Wi-Fi', icon: MibIcons.wifi, onTap: () => Bridge.call('system', {'what': 'wifi'})),
          MibRow(label: 'Bluetooth (system)', icon: MibIcons.bluetooth, onTap: () => Bridge.call('system', {'what': 'bluetooth'})),
        ],
      'navset' => [
          toggle('Voice guidance', 'voice'),
          toggle('Speed camera alerts', 'cameraAlerts'),
          open('Maps and offline data', 'classic:NAV'),
        ],
      'carset' => [
          open('Car information', 'carinfo', icon: MibIcons.directions_car),
          open('Diagnostics', 'classic:DIAGNOSTICS', icon: MibIcons.build),
          open('Climate', 'classic:DASHBOARD', icon: MibIcons.mode_fan),
        ],
      'language' => [
          system('Language', 'language', sub: 'system language and region'),
          system('Keyboard', 'keyboard'),
          system('Date and time', 'date'),
        ],
      'system' => [
          open('Software update', 'classic:SYSTEM_SET', icon: MibIcons.sync),
          open('Storage', 'classic:SYSTEM_SET', icon: MibIcons.storage),
          MibRow(label: 'Classic MMI', sub: 'switch back to the previous screens', trailing: MibToggle(value: false, onChanged: (_) => Bridge.call('classic'))),
          MibRow(label: 'Version', value: '${v('version', '')}'),
        ],
      _ => <Widget>[],
    };
    return SettingsList(children: rows);
  }
}
