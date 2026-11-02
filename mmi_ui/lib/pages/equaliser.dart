import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';

/// What each band is, what it sounds like, and what too much / too little does.
const bandInfo = [
  ('Sub-bass', '31 Hz', 'The deep rumble under kick drums and synth bass. Felt more than heard.',
      'Too much: car door speakers strain and rattle.', 'Too little: hardly noticeable in a car.'),
  ('Bass', '63 Hz', 'The punch of the kick drum and the body of the bass guitar.',
      'Too much: boomy, everything shakes.', 'Too little: music sounds thin.'),
  ('Upper bass', '125 Hz', 'Warmth and weight. Cars already add a lot here.',
      'Too much: muddy and booming.', 'Too little: cold, small sound.'),
  ('Low mids', '250 Hz', 'Fullness of voices, guitars and piano.',
      'Too much: muddy, "boxy".', 'Too little: hollow.'),
  ('Mids', '500 Hz', 'The body of most instruments.',
      'Too much: honky, like a cardboard box.', 'Too little: distant.'),
  ('Centre', '1 kHz', 'Where voices and speech sit.',
      'Too much: nasal, like a telephone.', 'Too little: voices get lost.'),
  ('Upper mids', '2 kHz', 'Clarity and attack: words become easy to follow.',
      'Too much: harsh and tiring on long drives.', 'Too little: dull, muffled.'),
  ('Presence', '4 kHz', 'Definition: consonants, guitar edges, snare crack.',
      'Too much: sharp, piercing.', 'Too little: everything sounds far away.'),
  ('Brilliance', '8 kHz', 'Cymbals, hi-hats and fine detail.',
      'Too much: hissy, sharp "s" sounds.', 'Too little: dark, no sparkle.'),
  ('Air', '16 kHz', 'Openness and sparkle at the very top.',
      'Too much: hiss on old recordings.', 'Too little: little change over road noise.'),
];

/// Settings · Sound · Equaliser: ten bands as one curve you drag, the tone under your
/// finger explained, presets with what they are for, Loudness and the master switch.
class EqualiserPage extends StatefulWidget {
  const EqualiserPage({super.key});
  @override
  State<EqualiserPage> createState() => _EqualiserPageState();
}

class _EqualiserPageState extends State<EqualiserPage> {
  List<double> gains = List.filled(10, 0);
  List<double> effective = List.filled(10, 0);
  List<Map<Object?, Object?>> presets = const [];
  String preset = 'Natural';
  bool loudness = true, enabled = true;
  String engine = 'dynamics';
  int band = 5;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final m = await Bridge.call<Map<Object?, Object?>>('soundGet');
    if (m == null || !mounted) return;
    setState(() {
      gains = (m['gains'] as List).map((e) => (e as num).toDouble()).toList();
      effective = (m['effective'] as List).map((e) => (e as num).toDouble()).toList();
      presets = (m['presets'] as List).cast<Map<Object?, Object?>>();
      preset = m['preset'] as String;
      loudness = m['loudness'] == true;
      enabled = m['enabled'] == true;
      engine = m['engine'] as String;
    });
  }

  void _drag(Offset p, Size size, {bool pick = false}) {
    final step = size.width / 10;
    if (pick) band = (p.dx / step).floor().clamp(0, 9);
    final db = ((0.5 - (p.dy - 20) / (size.height - 40)) * 24).clamp(-12.0, 12.0);
    final rounded = (db * 2).round() / 2;
    if (rounded == gains[band] && !pick) return;
    setState(() {
      gains[band] = rounded;
      preset = 'Custom';
    });
    Bridge.call('soundSet', {'band': band, 'db': rounded});
  }

  @override
  Widget build(BuildContext context) {
    final info = bandInfo[band];
    return Padding(
      padding: const EdgeInsets.only(left: 8, top: 18, bottom: 16),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // Curve and the explanation of the chosen tone.
        SizedBox(
          width: 742,
          child: Column(children: [
            Container(
              height: 352,
              decoration: Mib.glass(),
              padding: const EdgeInsets.fromLTRB(46, 12, 14, 8),
              child: Column(children: [
                Expanded(
                  child: LayoutBuilder(builder: (context, c) {
                    final size = Size(c.maxWidth, c.maxHeight);
                    return GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onPanStart: (d) => _drag(d.localPosition, size, pick: true),
                      onPanUpdate: (d) => _drag(d.localPosition, size),
                      onPanEnd: (_) => _load(),
                      onTapDown: (d) => _drag(d.localPosition, size, pick: true),
                      onTapUp: (_) => _load(),
                      child: CustomPaint(size: size, painter: _Curve(gains, effective, band, loudness && enabled)),
                    );
                  }),
                ),
                const SizedBox(height: 6),
                Row(children: [
                  for (var i = 0; i < 10; i++)
                    Expanded(
                      child: Text(bandInfo[i].$2.replaceAll(' Hz', '').replaceAll(' kHz', 'k'),
                          textAlign: TextAlign.center, style: Mib.text(18, color: i == band ? Mib.white : Mib.grey)),
                    ),
                ]),
              ]),
            ),
            const SizedBox(height: 16),
            Container(
              height: 196,
              decoration: Mib.glass(),
              padding: const EdgeInsets.fromLTRB(22, 14, 22, 12),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Text(info.$1, style: Mib.text(30)),
                  const SizedBox(width: 14),
                  Text(info.$2, style: Mib.text(24, color: Mib.grey)),
                  const Spacer(),
                  Text('${gains[band] > 0 ? '+' : ''}${gains[band].toStringAsFixed(1)} dB', style: Mib.text(30)),
                  const SizedBox(width: 18),
                  MibIconBox(icon: MibIcons.remove, width: 54, height: 48, size: 30, onTap: () => _nudge(-0.5)),
                  const SizedBox(width: 8),
                  MibIconBox(icon: MibIcons.add, width: 54, height: 48, size: 30, onTap: () => _nudge(0.5)),
                ]),
                const SizedBox(height: 10),
                Text(info.$3, style: Mib.text(23)),
                const SizedBox(height: 8),
                Text(info.$4, style: Mib.text(20, color: const Color(0xFFF0A030))),
                const SizedBox(height: 2),
                Text(info.$5, style: Mib.text(20, color: Mib.grey)),
              ]),
            ),
          ]),
        ),
        const SizedBox(width: 18),
        // Presets, Loudness, on / off.
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Expanded(
              child: Container(
                decoration: Mib.glass(),
                padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  Text('Presets', style: Mib.text(24)),
                  const SizedBox(height: 6),
                  Expanded(
                    child: ListView(padding: EdgeInsets.zero, children: [
                      for (final p in presets) _presetRow(p),
                      _presetRow({'name': 'Custom', 'about': 'Your own curve: drag the points.'}),
                    ]),
                  ),
                ]),
              ),
            ),
            const SizedBox(height: 16),
            Container(
              decoration: Mib.glass(),
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Column(children: [
                MibRow(
                  label: 'Loudness',
                  sub: 'fuller sound at low volume',
                  height: 78,
                  trailing: MibToggle(value: loudness, onChanged: (v) {
                    setState(() => loudness = v);
                    Bridge.call('soundLoudness', {'on': v}).then((_) => _load());
                  }),
                ),
                MibRow(
                  label: 'Equaliser',
                  sub: engine == 'none' ? 'not available on this unit' : (engine == 'dynamics' ? '10 bands, limiter on' : 'mapped to the unit\'s bands'),
                  height: 78,
                  trailing: MibToggle(value: enabled, onChanged: (v) {
                    setState(() => enabled = v);
                    Bridge.call('soundEnabled', {'on': v});
                  }),
                ),
              ]),
            ),
          ]),
        ),
      ]),
    );
  }

  void _nudge(double d) {
    final v = (gains[band] + d).clamp(-12.0, 12.0);
    setState(() {
      gains[band] = v;
      preset = 'Custom';
    });
    Bridge.call('soundSet', {'band': band, 'db': v}).then((_) => _load());
  }

  Widget _presetRow(Map<Object?, Object?> p) {
    final on = p['name'] == preset;
    return Pressable(
      onTap: () async {
        setState(() => preset = '${p['name']}');
        await Bridge.call('soundPreset', {'name': p['name']});
        _load();
      },
      child: Container(
        margin: const EdgeInsets.only(bottom: 6),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        decoration: BoxDecoration(
          color: on ? const Color(0xFF6E6E6E) : const Color(0x00000000),
          border: Border.all(color: const Color(0x55FFFFFF)),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('${p['name']}', style: Mib.text(22)),
          Text('${p['about']}', style: Mib.text(16, color: on ? const Color(0xFFE0E0E0) : Mib.grey), maxLines: 2, overflow: TextOverflow.ellipsis),
        ]),
      ),
    );
  }
}

/// The curve: grid at 0 / ±6 / ±12 dB, your bands as a smooth white line with points,
/// what is actually applied (with loudness) as a thin grey line, the chosen band lit.
class _Curve extends CustomPainter {
  _Curve(this.g, this.eff, this.band, this.showEffective);
  final List<double> g, eff;
  final int band;
  final bool showEffective;

  double _y(Size s, double db) => 20 + (s.height - 40) * (0.5 - db / 24);
  double _x(Size s, int i) => s.width / 10 * (i + 0.5);

  Path _path(Size s, List<double> v) {
    final pts = [for (var i = 0; i < 10; i++) Offset(_x(s, i), _y(s, v[i]))];
    final path = Path()..moveTo(0, pts.first.dy)..lineTo(pts.first.dx, pts.first.dy);
    for (var i = 0; i < 9; i++) {
      final p0 = i == 0 ? pts[0] : pts[i - 1], p1 = pts[i], p2 = pts[i + 1], p3 = i + 2 < 10 ? pts[i + 2] : pts[9];
      path.cubicTo(p1.dx + (p2.dx - p0.dx) / 6, p1.dy + (p2.dy - p0.dy) / 6, p2.dx - (p3.dx - p1.dx) / 6, p2.dy - (p3.dy - p1.dy) / 6, p2.dx, p2.dy);
    }
    path.lineTo(s.width, pts.last.dy);
    return path;
  }

  @override
  void paint(Canvas canvas, Size s) {
    final grid = Paint()..color = const Color(0x22FFFFFF)..strokeWidth = 1;
    for (final db in [-12.0, -6.0, 0.0, 6.0, 12.0]) {
      final y = _y(s, db);
      canvas.drawLine(Offset(0, y), Offset(s.width, y), db == 0 ? (Paint()..color = const Color(0x55FFFFFF)..strokeWidth = 1.2) : grid);
      final tp = TextPainter(text: TextSpan(text: db == 0 ? '0' : '${db > 0 ? '+' : ''}${db.round()}', style: Mib.text(15, color: Mib.grey)), textDirection: TextDirection.ltr)..layout();
      tp.paint(canvas, Offset(-tp.width - 10, y - tp.height / 2));
    }
    // The chosen band's column.
    canvas.drawRect(Rect.fromLTWH(s.width / 10 * band, 0, s.width / 10, s.height), Paint()..color = const Color(0x14FFFFFF));
    if (showEffective) {
      canvas.drawPath(_path(s, eff), Paint()..color = const Color(0x66FFFFFF)..style = PaintingStyle.stroke..strokeWidth = 1.5);
    }
    final curve = _path(s, g);
    // Soft fill under the curve, to the 0 dB line.
    final fill = Path.from(curve)..lineTo(s.width, _y(s, 0))..lineTo(0, _y(s, 0))..close();
    canvas.drawPath(fill, Paint()..color = const Color(0x18FFFFFF));
    canvas.drawPath(curve, Paint()..color = Mib.white..style = PaintingStyle.stroke..strokeWidth = 3..strokeCap = StrokeCap.round);
    for (var i = 0; i < 10; i++) {
      final c = Offset(_x(s, i), _y(s, g[i]));
      canvas.drawCircle(c, i == band ? 13 : 9, Paint()..color = i == band ? Mib.white : const Color(0xFFB4B4B4));
      canvas.drawCircle(c, i == band ? 13 : 9, Paint()..color = const Color(0xFF000000)..style = PaintingStyle.stroke..strokeWidth = 1.5);
    }
  }

  @override
  bool shouldRepaint(_Curve o) => true;
}
