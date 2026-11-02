import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/pressable.dart';

/// Phone Link: MMI's own page for the connected phone (no CarPlay / Android Auto
/// engine). The phone's name, battery and signal as the Bluetooth module reports them,
/// what it is playing, and shortcuts to calls, the keypad, its music and pairing.
class PhoneLinkPage extends StatefulWidget {
  const PhoneLinkPage({super.key, required this.onOpen});
  final ValueChanged<String> onOpen;
  @override
  State<PhoneLinkPage> createState() => _PhoneLinkPageState();
}

class _PhoneLinkPageState extends State<PhoneLinkPage> with Live<PhoneLinkPage> {
  @override
  String get liveMethod => 'phoneGet';
  @override
  String? get liveTopic => 'phone';

  @override
  Widget build(BuildContext context) {
    final connected = live?['connected'] == true;
    final name = (live?['name'] as String?)?.trim();
    final battery = (live?['battery'] as num?)?.toInt();
    final signal = (live?['signal'] as num?)?.toInt();
    final music = live?['music'] as String?;
    return Padding(
      padding: const EdgeInsets.only(left: 8, top: 24),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        // The phone.
        Container(
          height: 190,
          padding: const EdgeInsets.symmetric(horizontal: 30),
          decoration: Mib.glass(),
          child: Row(children: [
            Icon(MibIcons.mobile, size: 120, color: connected ? Mib.white : const Color(0x66FFFFFF)),
            const SizedBox(width: 28),
            Expanded(
              child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(connected ? (name?.isNotEmpty == true ? name! : 'Phone connected') : 'No phone connected', style: Mib.text(38)),
                const SizedBox(height: 8),
                if (connected)
                  Row(children: [
                    if (battery != null) ...[_Battery(battery), const SizedBox(width: 28)],
                    if (signal != null) _Signal(signal),
                  ])
                else
                  Text('Turn on Bluetooth on your phone and connect it to the unit.', style: Mib.text(22, color: Mib.grey)),
                if (connected && music != null && music.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text('Playing: $music', style: Mib.text(21, color: const Color(0xFFBDBDBD)), maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
              ]),
            ),
          ]),
        ),
        const SizedBox(height: 22),
        // Shortcuts.
        SizedBox(
          height: 170,
          child: Row(children: [
            Expanded(child: _Tile('Calls', MibIcons.history, () => widget.onOpen('calls'))),
            const SizedBox(width: 18),
            Expanded(child: _Tile('Keypad', MibIcons.dialpad, () => widget.onOpen('dial'))),
            const SizedBox(width: 18),
            Expanded(child: _Tile('Phone music', MibIcons.bluetooth, () async {
              await Bridge.call('source', {'key': 'bt'});
              widget.onOpen('music');
            })),
            const SizedBox(width: 18),
            Expanded(child: _Tile(connected ? 'Other phone' : 'Connect a phone', MibIcons.bluetooth_searching, () => widget.onOpen('btsearch'))),
          ]),
        ),
      ]),
    );
  }
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
          decoration: BoxDecoration(
            border: Border.all(color: const Color(0x80FFFFFF), width: 1.2),
            gradient: const LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(0xFF0B0B0B), Color(0xFF000000)]),
          ),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(icon, size: 64, color: Mib.white),
            const SizedBox(height: 14),
            Text(label, style: Mib.text(24)),
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
        width: 46,
        height: 22,
        padding: const EdgeInsets.all(2),
        decoration: BoxDecoration(border: Border.all(color: Mib.white, width: 1.5), borderRadius: BorderRadius.circular(3)),
        child: Align(alignment: Alignment.centerLeft, child: FractionallySizedBox(widthFactor: f, child: Container(color: f < 0.2 ? const Color(0xFFE5484D) : Mib.white))),
      ),
      const SizedBox(width: 10),
      Text(v > 5 ? '$v %' : 'Battery', style: Mib.text(21, color: Mib.grey)),
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
        Text('Signal', style: Mib.text(21, color: Mib.grey)),
      ]);
}
