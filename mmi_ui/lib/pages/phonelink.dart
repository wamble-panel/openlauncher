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
    return Padding(
      padding: const EdgeInsets.only(left: 8, top: 22),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        // CarPlay / Android Auto.
        SizedBox(
            height: 196,
            child: Row(children: [
              if (available) ...[
              Expanded(child: _Card('Apple CarPlay', active: linked && (mode ?? '').startsWith('carplay'), onTap: linked ? () => Bridge.call('phoneLinkOpen') : null)),
              const SizedBox(width: 18),
              Expanded(child: _Card('Android Auto', active: linked && (mode ?? '').startsWith('auto'), onTap: linked ? () => Bridge.call('phoneLinkOpen') : null)),
              const SizedBox(width: 18),
              ],
              // Screen Mirroring: the iPhone's screen (parked) and music over Wi-Fi, by MMI's
              // built-in AirPlay.
              Expanded(
                child: _Card(
                  'Screen Mirroring',
                  icon: MibIcons.cast_connected,
                  active: apShowing || apMusic,
                  status: !apInstalled
                      ? 'Not available in this build'
                      : apShowing
                          ? 'iPhone screen · tap to open'
                          : apMusic
                              ? (apTitle.isNotEmpty ? apTitle : 'Playing music')
                              : ap['running'] == true
                                  ? 'AirPlay · ready for iPhone'
                                  : 'Starting…',
                  onTap: apShowing ? () => Bridge.call('airplayOpen') : null,
                ),
              ),
            ]),
          ),
        const SizedBox(height: 20),
        // Shortcuts.
        SizedBox(
          height: 176,
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
        const SizedBox(height: 12),
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
