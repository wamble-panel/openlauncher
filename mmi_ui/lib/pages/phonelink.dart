import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';

/// Phone Link: MMI's own page for CarPlay and Android Auto. Shows whether a phone is
/// connected and how; only when one is, it opens the phone's screen (which the unit's
/// licensed engine draws full screen). Home comes back here to MMI.
class PhoneLinkPage extends StatefulWidget {
  const PhoneLinkPage({super.key});
  @override
  State<PhoneLinkPage> createState() => _PhoneLinkPageState();
}

class _PhoneLinkPageState extends State<PhoneLinkPage> with Live<PhoneLinkPage> {
  @override
  String get liveMethod => 'phoneLinkGet';
  @override
  String? get liveTopic => 'phonelink';

  String _mode(String? m) => switch (m) {
        'carplay_wired' => 'Apple CarPlay · USB',
        'carplay_wireless' => 'Apple CarPlay · wireless',
        'auto_wired' => 'Android Auto · USB',
        'auto_wireless' => 'Android Auto · wireless',
        _ => m == null ? 'Phone connected' : m.replaceAll('_', ' '),
      };

  @override
  Widget build(BuildContext context) {
    final connected = live?['connected'] == true;
    final installed = live?['installed'] != false;
    final mode = live?['mode'] as String?;
    return Padding(
      padding: const EdgeInsets.only(left: 8, top: 26),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        SizedBox(
          height: 250,
          child: Row(children: [
            Expanded(child: _Card('Apple CarPlay', 'iPhone', active: connected && (mode ?? '').startsWith('carplay'), connected: connected)),
            const SizedBox(width: 20),
            Expanded(child: _Card('Android Auto', 'Android phone', active: connected && (mode ?? '').startsWith('auto'), connected: connected)),
          ]),
        ),
        const SizedBox(height: 22),
        Container(
          height: 96,
          padding: const EdgeInsets.symmetric(horizontal: 22),
          decoration: Mib.glass(),
          child: Row(children: [
            Container(width: 14, height: 14, decoration: BoxDecoration(shape: BoxShape.circle, color: connected ? const Color(0xFF5BD35B) : const Color(0xFF6A6A6A))),
            const SizedBox(width: 18),
            Expanded(
              child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(!installed ? 'Phone link not available on this unit' : connected ? _mode(mode) : 'No phone connected', style: Mib.text(27)),
                Text(
                  connected ? 'Tap Open to show your phone\'s screen; Home brings you back.' : 'Connect with the USB cable, or wirelessly: pair your phone with the unit over Bluetooth once.',
                  style: Mib.text(19, color: Mib.grey),
                ),
              ]),
            ),
            if (connected) MibButton(label: 'Open', icon: MibIcons.open_in_new, width: 170, onTap: () => Bridge.call('phoneLinkOpen')),
          ]),
        ),
        const SizedBox(height: 10),
        MibRow(
          label: 'Open by itself when the phone connects',
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
  const _Card(this.title, this.sub, {required this.active, required this.connected});
  final String title, sub;
  final bool active, connected;
  @override
  Widget build(BuildContext context) => Pressable(
        // Only with a phone connected: never the engine's own start screen.
        onTap: () {
          if (connected) Bridge.call('phoneLinkOpen');
        },
        child: Container(
          decoration: BoxDecoration(
            border: Border.all(color: active ? Mib.white : const Color(0x80FFFFFF), width: active ? 2.5 : 1.2),
            gradient: const LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(0xFF0B0B0B), Color(0xFF000000)]),
          ),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(MibIcons.mobile_share, size: 88, color: active ? Mib.white : const Color(0xCCFFFFFF)),
            const SizedBox(height: 16),
            Text(title, style: Mib.text(30)),
            Text(active ? 'Connected' : sub, style: Mib.text(21, color: active ? const Color(0xFF5BD35B) : Mib.grey)),
          ]),
        ),
      );
}
