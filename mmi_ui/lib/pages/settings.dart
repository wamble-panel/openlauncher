import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../live.dart';
import '../perf.dart';
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
  @override
  String? get liveTopic => 'settings';
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
          toggle('Open CarPlay / Android Auto when the phone connects', 'phonelinkAuto'),
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
          // The car the unit is set up for (vendor car list, read only).
          MibRow(
            label: 'Car',
            value: (v('carName', null) as String?) ?? 'Not found in the car list',
            sub: v('carBox', null) == null ? null : 'CAN box: ${v('carBox', '')}',
          ),
          const MibCaption('Car data', top: 18),
          MibSegmented(
            options: const ['Automatic', 'Opel / GM', 'Car service only'],
            selected: const ['AUTO', 'GM', 'CAR_SERVICE_ONLY'].indexOf('${v('carDecoder', 'AUTO')}').clamp(0, 2),
            onSelect: (i) => set('carDecoder', const ['AUTO', 'GM', 'CAR_SERVICE_ONLY'][i]),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(10, 8, 10, 14),
            child: Text(
              'Automatic reads the car from the unit\'s car list. MMI\'s own Opel / GM decoding '
              '(doors, tyres, range, oil life) is used only on those cars; every other car gets its '
              'data from the unit\'s car service.',
              style: Mib.text(19, color: Mib.grey),
            ),
          ),
          MibRow(
            label: 'Car picture',
            value: v('carPicture', null) != null ? 'Your photo' : (v('carDefaultPicture', true) == true ? 'Astra J' : 'None'),
            sub: 'Your own: put car.png in the MMI folder (unit storage)',
          ),
          open('Car buttons', 'carkeys', icon: MibIcons.tune, sub: 'give unknown steering-wheel or dash buttons a job'),
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
          open('Software update', 'update', icon: MibIcons.sync),
          open('Storage', 'update', icon: MibIcons.storage),
          toggle('OpenGL ES graphics', 'gles', sub: 'try if pages stutter; used from the next start'),
          MibRow(label: 'Classic MMI', sub: 'switch back to the previous screens', trailing: MibToggle(value: false, onChanged: (_) => Bridge.call('classic'))),
          MibRow(label: 'Version', value: '${v('version', '')}'),
          open('System tuning', 'tuning', icon: MibIcons.build, sub: 'root speed-up, disable vendor apps, firmware backup'),
          open('Unit health', 'health', icon: MibIcons.device_thermostat, sub: 'chip temperatures, speed and memory'),
          open('Vendor apps', 'vendor', icon: MibIcons.apps, sub: 'which ones MMI replaces; disable them one by one'),
          const _DevSend(),
        ],
      _ => <Widget>[],
    };
    return SettingsList(children: rows);
  }
}

/// Sends the unit's car software and system facts to the developer's private repo
/// (one tap; GitHub asks for a one-time code on the first send). Shows progress.
class _DevSend extends StatefulWidget {
  const _DevSend();
  @override
  State<_DevSend> createState() => _DevSendState();
}

class _DevSendState extends State<_DevSend> with Live<_DevSend> {
  @override
  String get liveMethod => 'devStatus';
  @override
  Duration get liveEvery => const Duration(seconds: 1);

  @override
  Widget build(BuildContext context) {
    final running = live?['running'] == true;
    return MibRow(
      label: running ? 'Sending unit data…' : 'Send unit data to developer',
      sub: (live?['status'] as String?) ?? 'Car software and system details, to your private repo',
      icon: MibIcons.sync,
      height: 96,
      onTap: running ? null : () async {
        await Bridge.call('devSend');
        refresh();
      },
    );
  }
}

/// Settings · Vendor apps: every vendor app on the unit, whether MMI does its job
/// yet, and a tap to Android's page for it (Disable there; it can be switched back).
class VendorAppsPage extends StatefulWidget {
  const VendorAppsPage({super.key});
  @override
  State<VendorAppsPage> createState() => _VendorAppsPageState();
}

class _VendorAppsPageState extends State<VendorAppsPage> with Live<VendorAppsPage> {
  @override
  String get liveMethod => 'vendorGet';
  @override
  Duration get liveEvery => const Duration(seconds: 3);

  static const _label = {'safe': 'MMI replaces it', 'test': 'Test in the car first', 'keep': 'Keep for now'};
  static const _color = {'safe': Color(0xFF5BD35B), 'test': Color(0xFFF0A030), 'keep': Color(0xFFA6A6A6)};

  @override
  Widget build(BuildContext context) {
    final apps = ((live?['apps'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    return ListView(padding: const EdgeInsets.only(top: 8, bottom: 24), children: [
      for (final a in apps)
        Pressable(
          onTap: () => Bridge.call('vendorOpen', {'pkg': a['pkg']}),
          child: Container(
            height: 84,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0x40FFFFFF)))),
            child: Row(children: [
              Container(width: 12, height: 12, decoration: BoxDecoration(shape: BoxShape.circle, color: _color[a['status']])),
              const SizedBox(width: 20),
              Expanded(
                child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('${a['name']}', style: Mib.text(26, color: a['enabled'] == true ? Mib.white : Mib.grey)),
                  Text('${a['note']}', style: Mib.text(19, color: Mib.grey), maxLines: 1, overflow: TextOverflow.ellipsis),
                ]),
              ),
              Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                Text(_label[a['status']] ?? '', style: Mib.text(20, color: _color[a['status']] ?? Mib.grey)),
                Text(a['enabled'] == true ? 'On' : 'Disabled', style: Mib.text(19, color: Mib.grey)),
              ]),
              const SizedBox(width: 12),
              const Icon(MibIcons.chevron_right, size: 32, color: Mib.white),
            ]),
          ),
        ),
    ]);
  }
}

/// Settings · Unit health: the A133's three temperature sensors, CPU speed per core,
/// GPU speed, free memory and MMI's own share. The chip is made for up to 110 °C
/// inside (70 °C around it); it slows itself down when it gets close.
class HealthPage extends StatefulWidget {
  const HealthPage({super.key});
  @override
  State<HealthPage> createState() => _HealthPageState();
}

class _HealthPageState extends State<HealthPage> with Live<HealthPage> {
  @override
  String get liveMethod => 'healthGet';
  @override
  Duration get liveEvery => const Duration(seconds: 2);

  static String _label(String raw) {
    final n = raw.toLowerCase();
    if (n.contains('cpu')) return 'CPU';
    if (n.contains('gpu')) return 'GPU';
    if (n.contains('ddr') || n.contains('dram') || n.contains('mem')) return 'Memory';
    return raw;
  }

  @override
  Widget build(BuildContext context) {
    final temps = ((live?['temps'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    final cores = ((live?['cores'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    final total = (live?['ramTotalMb'] as num?)?.toInt() ?? 0;
    final free = (live?['ramFreeMb'] as num?)?.toInt() ?? 0;
    final hottest = temps.fold<double>(0, (m, t) => (t['c'] as num).toDouble() > m ? (t['c'] as num).toDouble() : m);
    final verdict = hottest >= 100 ? ('Hot: the chip is slowing itself down to cool off', const Color(0xFFE5484D))
        : hottest >= 85 ? ('Warm: fine, but close to where it slows down', const Color(0xFFF0A030))
        : ('Cool: full speed available', const Color(0xFF5BD35B));
    return ListView(padding: const EdgeInsets.only(top: 14, bottom: 24, left: 8), children: [
      if (temps.isNotEmpty) Padding(padding: const EdgeInsets.only(bottom: 14), child: Text(verdict.$1, style: Mib.text(25, color: verdict.$2))),
      SizedBox(
        height: 150,
        child: Row(children: [
          for (final t in temps.take(4)) ...[
            Expanded(child: _Gauge(_label('${t['name']}'), (t['c'] as num).toDouble())),
            const SizedBox(width: 14),
          ],
          if (temps.isEmpty) Expanded(child: Center(child: Text('The unit does not show its temperatures to apps.', style: Mib.text(22, color: Mib.grey)))),
        ]),
      ),
      const SizedBox(height: 18),
      MibRow(label: 'CPU speed', value: '${cores.map((c) => c['cur'] == null ? '--' : '${c['cur']}').join(' · ')} MHz',
          sub: cores.isNotEmpty && cores.first['max'] != null ? 'up to ${cores.first['max']} MHz per core (4 × Cortex-A53)' : null),
      if (live?['gpuMhz'] != null) MibRow(label: 'GPU speed', value: '${live!['gpuMhz']} MHz', sub: 'PowerVR GE8300'),
      MibRow(label: 'Memory free', value: '$free of $total MB', sub: 'MMI uses ${live?['mmiMb'] ?? '--'} MB'),
      MibRow(label: 'On for', value: '${live?['uptimeMin'] ?? '--'} min'),
      _Smoothness(),
    ]);
  }
}

class _Gauge extends StatelessWidget {
  const _Gauge(this.label, this.c);
  final String label;
  final double c;
  @override
  Widget build(BuildContext context) {
    final color = c >= 100 ? const Color(0xFFE5484D) : c >= 85 ? const Color(0xFFF0A030) : Mib.white;
    return Container(
      decoration: Mib.glass(),
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: Mib.text(22, color: Mib.grey)),
        const Spacer(),
        Text('${c.round()} °C', style: Mib.text(44, color: color, height: 1.0)),
        const SizedBox(height: 10),
        ClipRRect(
          borderRadius: BorderRadius.circular(2),
          child: SizedBox(
            height: 5,
            child: Stack(children: [
              const Positioned.fill(child: ColoredBox(color: Color(0xFF3A3A3A))),
              FractionallySizedBox(widthFactor: (c / 110).clamp(0.0, 1.0), child: ColoredBox(color: color)),
            ]),
          ),
        ),
      ]),
    );
  }
}

/// Smoothness measured on this unit over the last 10 seconds of movement.
class _Smoothness extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final s = FrameMonitor.stats();
    final frames = s['frames'] ?? 0;
    if (frames < 10) {
      return const MibRow(label: 'Smoothness', value: 'move something', sub: 'swipe Home or drag a slider, then look again');
    }
    final slow = (s['slowPct'] ?? 0).toDouble();
    final verdict = slow < 3 ? 'Smooth' : slow < 12 ? 'Mostly smooth' : 'Stuttering';
    return MibRow(
      label: 'Smoothness',
      value: '$verdict · ${slow.toStringAsFixed(0)} % slow frames',
      sub: 'average ${(s['avgMs'] ?? 0).toStringAsFixed(1)} ms per frame, worst ${(s['worstMs'] ?? 0).toStringAsFixed(0)} ms (16.7 ms = 60 fps)',
    );
  }
}

/// Settings · System tuning: on a rooted unit, does the whole speed job from here
/// (compile MMI to native, kill vendor pop-ups, disable vendor apps, faster
/// animations), can turn on Wi-Fi adb, and backs up the partitions to a USB stick
/// for the firmware. All reversible. On a unit without root it says so.
class TuningPage extends StatefulWidget {
  const TuningPage({super.key});
  @override
  State<TuningPage> createState() => _TuningPageState();
}

class _TuningPageState extends State<TuningPage> {
  Map<Object?, Object?>? st;
  List<String> output = const [];
  bool busy = false;
  bool wifiAdb = true;

  @override
  void initState() {
    super.initState();
    _status();
  }

  Future<void> _status() async {
    final m = await Bridge.call<Map<Object?, Object?>>('optimizeStatus');
    if (mounted) setState(() => st = m);
  }

  Future<void> _run(String method, [Map<String, Object?>? args]) async {
    setState(() { busy = true; output = const ['Working…']; });
    final r = await Bridge.call<List<Object?>>(method, args);
    if (!mounted) return;
    setState(() { busy = false; output = (r ?? const []).cast<String>(); });
    _status();
  }

  @override
  Widget build(BuildContext context) {
    final rooted = st?['rooted'] == true;
    final optimized = st?['optimized'] == true;
    final wifiOn = st?['wifiAdb'] == true;
    return ListView(padding: const EdgeInsets.only(top: 14, bottom: 24, left: 8), children: [
      Container(
        padding: const EdgeInsets.all(18),
        decoration: Mib.glass(),
        child: Row(children: [
          Icon(rooted ? MibIcons.verified_user : MibIcons.info, size: 40, color: rooted ? const Color(0xFF5BD35B) : const Color(0xFFF0A030)),
          const SizedBox(width: 18),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(st == null ? 'Checking…' : rooted ? 'Root available' : 'No root on this unit', style: Mib.text(27)),
            Text(
              st == null ? '' : rooted
                  ? 'MMI can do the full tune-up itself — no laptop needed.'
                  : 'MMI can\'t add root (no app can). Root needs the stock image, same as the firmware.',
              style: Mib.text(20, color: Mib.grey),
            ),
          ])),
        ]),
      ),
      if (rooted) ...[
        const MibCaption('Speed tune-up'),
        MibRow(label: 'Turn on Wi-Fi adb too', sub: 'lets a laptop connect over Wi-Fi, no cable', trailing: MibToggle(value: wifiAdb, onChanged: (v) => setState(() => wifiAdb = v))),
        const SizedBox(height: 10),
        _Button(optimized ? 'Re-run optimize' : 'Optimize now', MibIcons.speed, busy ? null : () => _run('optimizeRun', {'wifiAdb': wifiAdb})),
        if (optimized) _Button('Undo optimize', MibIcons.restart_alt, busy ? null : () => _run('optimizeUndo')),
        const MibCaption('Firmware backup'),
        Padding(padding: const EdgeInsets.symmetric(horizontal: 10), child: Text('Insert a USB stick, then back up the partitions for building the firmware.', style: Mib.text(20, color: Mib.grey))),
        const SizedBox(height: 10),
        _Button('Back up partitions to USB', MibIcons.storage, busy ? null : () => _run('backupPartitions')),
        if (wifiOn) Padding(padding: const EdgeInsets.only(left: 10, top: 14), child: Text('Wi-Fi adb is ON. On a laptop on the same network: adb connect <unit-ip>:5555', style: Mib.text(19, color: const Color(0xFF5BD35B)))),
      ],
      if (output.isNotEmpty) ...[
        const SizedBox(height: 18),
        Container(
          padding: const EdgeInsets.all(16),
          decoration: Mib.glass(),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            for (final line in output) Text(line, style: Mib.text(20, color: line.startsWith('✗') ? const Color(0xFFE5484D) : Mib.white)),
          ]),
        ),
      ],
    ]);
  }
}

class _Button extends StatelessWidget {
  const _Button(this.label, this.icon, this.onTap);
  final String label;
  final IconData icon;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 10),
        child: Pressable(
          onTap: onTap ?? () {},
          child: Opacity(
            opacity: onTap == null ? 0.5 : 1,
            child: Container(
              height: 72,
              padding: const EdgeInsets.symmetric(horizontal: 22),
              decoration: BoxDecoration(border: Border.all(color: const Color(0xAAFFFFFF))),
              child: Row(children: [Icon(icon, size: 32, color: Mib.white), const SizedBox(width: 18), Text(label, style: Mib.text(25))]),
            ),
          ),
        ),
      );
}
