import 'package:flutter/widgets.dart' hide Text;
import '../i18n.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';

/// Phone Link: MMI's page for the phone. Apple CarPlay and Android Auto run through
/// the unit's licensed engine, hidden: MMI shows the connection and opens the phone's
/// screen only when a phone is connected (Home brings MMI back). Below, the Bluetooth
/// phone: name, battery, signal, and shortcuts to calls, keypad, music and pairing.
class PhoneLinkPage extends StatefulWidget {
  const PhoneLinkPage({super.key, required this.onOpen});
  final ValueChanged<String> onOpen;
  @override
  State<PhoneLinkPage> createState() => _PhoneLinkPageState();
}

class _PhoneLinkPageState extends State<PhoneLinkPage> with Live<PhoneLinkPage> {
  @override
  String get liveMethod => 'phoneLinkGet';
  @override
  String? get liveTopic => 'phonelink';
  Map<Object?, Object?>? phone;

  @override
  Future<void> refresh() async {
    await super.refresh();
    final p = await Bridge.call<Map<Object?, Object?>>('phoneGet');
    if (mounted && p != null && p.toString() != phone.toString()) setState(() => phone = p);
  }

  String _mode(String? m) => switch (m) {
        'carplay_wired' => 'Apple CarPlay · USB',
        'carplay_wireless' => 'Apple CarPlay · wireless',
        'auto_wired' => 'Android Auto · USB',
        'auto_wireless' => 'Android Auto · wireless',
        _ => m == null ? 'Phone connected' : m.replaceAll('_', ' '),
      };

  @override
  Widget build(BuildContext context) {
    final linked = live?['connected'] == true;
    final mode = live?['mode'] as String?;
    final available = live?['installed'] != false;
    final bt = phone?['connected'] == true;
    final ap = (live?['airplay'] as Map?) ?? const {};
    final apInstalled = ap['installed'] == true;
    final apShowing = ap['mirroring'] == true || ap['video'] == true;
    final apMusic = ap['audio'] == true;
    final apTitle = [ap['title'], ap['artist']].whereType<String>().where((t) => t.isNotEmpty).join(' · ');
    final name = (phone?['name'] as String?)?.trim();
    final battery = (phone?['battery'] as num?)?.toInt();
    final signal = (phone?['signal'] as num?)?.toInt();
    return Padding(
      padding: const EdgeInsets.only(left: 8, top: 22),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        // CarPlay / Android Auto.
        SizedBox(
            height: 150,
            child: Row(children: [
              if (available) ...[
              Expanded(child: _Card('Apple CarPlay', active: linked && (mode ?? '').startsWith('carplay'), onTap: linked ? () => Bridge.call('phoneLinkOpen') : null)),
              const SizedBox(width: 18),
              Expanded(child: _Card('Android Auto', active: linked && (mode ?? '').startsWith('auto'), onTap: linked ? () => Bridge.call('phoneLinkOpen') : null)),
              const SizedBox(width: 18),
              ],
              // Screen Mirroring: the iPhone's screen (parked) and music over Wi-Fi, by AirPlay.
              Expanded(
                child: _Card(
                  'Screen Mirroring',
                  icon: MibIcons.cast_connected,
                  active: apShowing || apMusic,
                  status: !apInstalled
                      ? 'Not installed · tap to install'
                      : apShowing
                          ? 'iPhone screen · tap to open'
                          : apMusic
                              ? (apTitle.isNotEmpty ? apTitle : 'Playing music')
                              : ap['running'] == true
                                  ? 'AirPlay · ready for iPhone'
                                  : 'Starting…',
                  onTap: () => Bridge.call('airplayOpen'),
                ),
              ),
            ]),
          ),
        const SizedBox(height: 16),
        // The phone.
        Container(
          height: 118,
          padding: const EdgeInsets.symmetric(horizontal: 24),
          decoration: Mib.glass(),
          child: Row(children: [
            Icon(MibIcons.mobile, size: 70, color: bt || linked ? Mib.white : const Color(0x66FFFFFF)),
            const SizedBox(width: 20),
            Expanded(
              child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(
                  linked ? _mode(mode) : bt ? (name?.isNotEmpty == true ? name! : 'Phone connected') : 'No phone connected',
                  style: Mib.text(30),
                ),
                const SizedBox(height: 6),
                if (bt && (battery != null || signal != null))
                  Row(children: [
                    if (battery != null) ...[_Battery(battery), const SizedBox(width: 26)],
                    if (signal != null) _Signal(signal),
                  ])
                else if (!bt && !linked)
                  Text('USB cable for CarPlay / Android Auto, or Bluetooth for calls and music.', style: Mib.text(20, color: Mib.grey)),
              ]),
            ),
            if (linked) MibButton(label: 'Open', icon: MibIcons.open_in_new, width: 160, onTap: () => Bridge.call('phoneLinkOpen')),
          ]),
        ),
        const SizedBox(height: 16),
        // Shortcuts.
        SizedBox(
          height: 128,
          child: Row(children: [
            Expanded(child: _Tile('Calls', MibIcons.history, () => widget.onOpen('calls'))),
            const SizedBox(width: 14),
            Expanded(child: _Tile('Keypad', MibIcons.dialpad, () => widget.onOpen('dial'))),
            const SizedBox(width: 14),
            Expanded(child: _Tile('Phone music', MibIcons.bluetooth, () async {
              await Bridge.call('source', {'key': 'bt'});
              widget.onOpen('music');
            })),
            const SizedBox(width: 14),
            Expanded(child: _Tile(bt ? 'Other phone' : 'Connect a phone', MibIcons.bluetooth_searching, () => widget.onOpen('btsearch'))),
          ]),
        ),
        const SizedBox(height: 6),
        if (available)
          MibRow(
            label: 'Open CarPlay / Android Auto when the phone connects',
            height: 70,
            trailing: MibToggle(value: live?['auto'] != false, onChanged: (v) async {
              await Bridge.call('settingsSet', {'key': 'phonelinkAuto', 'value': v});
              refresh();
            }),
          ),
      ]),
    );
  }
}

class _Card extends StatelessWidget {
  const _Card(this.title, {required this.active, this.onTap, this.status, this.icon = MibIcons.mobile_share});
  final String title;
  final bool active;
  final VoidCallback? onTap;
  final String? status;
  final IconData icon;
  @override
  Widget build(BuildContext context) => Pressable(
        // Only with a phone connected: never the engine's own start screen.
        onTap: onTap ?? () {},
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          decoration: BoxDecoration(
            border: Border.all(color: active ? Mib.white : const Color(0x80FFFFFF), width: active ? 2.5 : 1.2),
            gradient: const LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(0xFF0B0B0B), Color(0xFF000000)]),
          ),
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(icon, size: 56, color: active ? Mib.white : const Color(0xCCFFFFFF)),
            const SizedBox(width: 16),
            Flexible(
              child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(title, style: Mib.text(30), maxLines: 1, overflow: TextOverflow.ellipsis),
                Text(status ?? (active ? 'Connected · tap to open' : 'Not connected'),
                    style: Mib.text(19, color: active ? const Color(0xFF5BD35B) : Mib.grey), maxLines: 2, overflow: TextOverflow.ellipsis),
              ]),
            ),
          ]),
        ),
      );
}

class _Tile extends StatelessWidget {
  const _Tile(this.label, this.icon, this.onTap);
  final String label;
  final IconData icon;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Pressable(
        onTap: onTap,
        child: Container(
          decoration: BoxDecoration(border: Border.all(color: const Color(0x80FFFFFF), width: 1.2)),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(icon, size: 52, color: Mib.white),
            const SizedBox(height: 10),
            Text(label, style: Mib.text(22)),
          ]),
        ),
      );
}

/// Battery as the module reports it (0-5 or a percentage), drawn as a bar.
class _Battery extends StatelessWidget {
  const _Battery(this.v);
  final int v;
  @override
  Widget build(BuildContext context) {
    final f = v > 5 ? (v / 100).clamp(0.0, 1.0) : v / 5;
    return Row(children: [
      Container(
        width: 42,
        height: 20,
        padding: const EdgeInsets.all(2),
        decoration: BoxDecoration(border: Border.all(color: Mib.white, width: 1.5), borderRadius: BorderRadius.circular(3)),
        child: Align(alignment: Alignment.centerLeft, child: FractionallySizedBox(widthFactor: f, child: Container(color: f < 0.2 ? const Color(0xFFE5484D) : Mib.white))),
      ),
      const SizedBox(width: 10),
      Text(v > 5 ? '$v %' : 'Battery', style: Mib.text(20, color: Mib.grey)),
    ]);
  }
}

/// Signal bars (0-5).
class _Signal extends StatelessWidget {
  const _Signal(this.v);
  final int v;
  @override
  Widget build(BuildContext context) => Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
        for (var i = 1; i <= 5; i++)
          Container(margin: const EdgeInsets.only(right: 4), width: 8, height: 6.0 + i * 4, color: i <= v ? Mib.white : const Color(0x33FFFFFF)),
        const SizedBox(width: 8),
        Text('Signal', style: Mib.text(20, color: Mib.grey)),
      ]);
}
