import 'dart:math' as math;
import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';

/// Settings · Searching… (design 31): phones found nearby, Tap to connect, a last
/// "Searching…" row while the search runs, the name phones see at the bottom.
class BtSearchPage extends StatefulWidget {
  const BtSearchPage({super.key, required this.onBack});
  final VoidCallback onBack;
  @override
  State<BtSearchPage> createState() => _BtSearchPageState();
}

class _BtSearchPageState extends State<BtSearchPage> with Live<BtSearchPage>, SingleTickerProviderStateMixin {
  @override
  String get liveMethod => 'btGet';
  @override
  Duration get liveEvery => const Duration(seconds: 2);
  @override
  String? get liveTopic => 'bt';
  late final AnimationController _spin = AnimationController(vsync: this, duration: const Duration(milliseconds: 1100))..repeat();

  @override
  void initState() {
    super.initState();
    Bridge.call('btScan');
  }

  @override
  void dispose() {
    _spin.dispose();
    Bridge.call('btStop');
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scanning = live?['scanning'] != false;
    final devices = ((live?['devices'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    final name = live?['name'] as String? ?? 'MMI';
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      MibHeader(
        section: 'Settings',
        title: scanning ? 'Searching…' : 'Phones nearby',
        onBack: widget.onBack,
        titleTrailing: scanning
            ? RepaintBoundary(child: RotationTransition(turns: _spin, child: const SizedBox(width: 44, height: 44, child: CustomPaint(painter: _Ring()))))
            : Pressable(onTap: () => Bridge.call('btScan'), child: const Padding(padding: EdgeInsets.all(6), child: Icon(MibIcons.refresh, size: 38, color: Mib.white))),
      ),
      Expanded(
        child: ListView(padding: const EdgeInsets.only(top: 10), children: [
          for (final d in devices)
            _PhoneRow(
              name: '${d['name']}',
              status: d['connected'] == true ? 'Connected' : (d['connecting'] == true ? 'Connecting…' : 'Tap to connect'),
              onTap: () => Bridge.call('btConnect', {'address': d['address']}),
            ),
          if (scanning) _PhoneRow(name: 'Searching…', status: '', grey: true),
        ]),
      ),
      Padding(padding: const EdgeInsets.only(left: 12, bottom: 54), child: Text('Visible to phones as: $name', style: Mib.text(22, color: Mib.grey))),
    ]);
  }
}

class _PhoneRow extends StatelessWidget {
  const _PhoneRow({required this.name, required this.status, this.onTap, this.grey = false});
  final String name, status;
  final VoidCallback? onTap;
  final bool grey;
  @override
  Widget build(BuildContext context) {
    final row = Container(
      height: 83,
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0x40FFFFFF)))),
      child: Row(children: [
        const SizedBox(width: 30),
        const Icon(MibIcons.mobile, size: 44, color: Mib.white),
        const SizedBox(width: 36),
        Expanded(child: Text(name, style: Mib.text(30, color: grey ? Mib.grey : Mib.white), maxLines: 1, overflow: TextOverflow.ellipsis)),
        Text(status, style: Mib.text(23, color: Mib.grey)),
        const SizedBox(width: 20),
      ]),
    );
    return onTap == null ? row : Pressable(onTap: onTap!, child: row);
  }
}

/// Spinner: a thin grey ring with a brighter arc.
class _Ring extends CustomPainter {
  const _Ring();
  @override
  void paint(Canvas canvas, Size size) {
    final r = Rect.fromLTWH(3, 3, size.width - 6, size.height - 6);
    canvas.drawArc(r, 0, math.pi * 2, false, Paint()..color = const Color(0xFF4A4A4A)..style = PaintingStyle.stroke..strokeWidth = 3);
    canvas.drawArc(r, -math.pi / 2, math.pi * 1.2, false,
        Paint()..color = const Color(0xFFD8D8D8)..style = PaintingStyle.stroke..strokeWidth = 3..strokeCap = StrokeCap.round);
  }

  @override
  bool shouldRepaint(_Ring old) => false;
}
