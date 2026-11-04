import 'dart:io';
import 'package:flutter/widgets.dart' hide Text;
import '../i18n.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/pressable.dart';

/// Radio · FM (design 41): station panel with logo and Save, tuning scale 88–108 with
/// seek buttons, six favourites. Save fills the first empty favourite; holding a
/// favourite replaces it with the station playing now.
class RadioPage extends StatefulWidget {
  const RadioPage({super.key});
  @override
  State<RadioPage> createState() => _RadioPageState();
}

class _RadioPageState extends State<RadioPage> with Live<RadioPage> {
  @override
  String get liveMethod => 'radioGet';
  @override
  String? get liveTopic => 'radio';
  @override
  Duration get liveEvery => const Duration(seconds: 5);

  @override
  void initState() {
    super.initState();
    // Like an OEM radio: opening Radio plays the radio (last station).
    Bridge.call('radioResume');
  }

  bool get am => live?['am'] == true;
  double get freq => ((live?['freq'] ?? 98.5) as num).toDouble();
  List<Map<Object?, Object?>> get presets =>
      ((live?['presets'] as List?) ?? const []).cast<Map<Object?, Object?>>();

  String fmt(double f) => am ? '${f.round()} kHz' : '${f.toStringAsFixed(1)} MHz';

  Future<void> _do(String method, [Map<String, Object?>? args]) async {
    await Bridge.call(method, args);
    refresh();
  }

  void _save() {
    final i = List.generate(6, (i) => i).firstWhere((i) => i >= presets.length || ((presets[i]['freq'] ?? 0) as num) <= 0, orElse: () => -1);
    if (i >= 0) _do('radioSave', {'slot': i});
  }

  @override
  Widget build(BuildContext context) {
    final name = (live?['name'] as String?)?.trim();
    final genre = live?['genre'] as String?;
    final logo = live?['logo'] as String?;
    return Padding(
      padding: const EdgeInsets.only(left: 8, top: 28),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        // Station.
        Container(
          height: 157,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(border: Border.all(color: const Color(0x99FFFFFF))),
          child: Row(children: [
            Container(
              width: 148,
              decoration: BoxDecoration(border: Border.all(color: const Color(0x99FFFFFF))),
              child: logo != null && File(logo).existsSync()
                  ? Padding(padding: const EdgeInsets.all(10), child: Image.file(File(logo), fit: BoxFit.contain, cacheWidth: 128))
                  : const Center(child: Icon(MibIcons.cell_tower, size: 84, color: Mib.white)),
            ),
            const SizedBox(width: 34),
            Expanded(
              child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(name?.isNotEmpty == true ? name! : fmt(freq), style: Mib.text(42), maxLines: 1, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 4),
                Row(children: [
                  Text([if (name?.isNotEmpty == true) fmt(freq) else (am ? 'AM' : 'FM'), if (genre != null && genre.isNotEmpty) genre].join('   ·   '), style: Mib.text(26, color: Mib.grey)),
                  if (live?['stereo'] == true) ...[
                    const SizedBox(width: 16),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                      decoration: BoxDecoration(border: Border.all(color: Mib.grey)),
                      child: Text('ST', style: Mib.text(16, color: Mib.grey)),
                    ),
                  ],
                  if (live?['seeking'] == true) ...[const SizedBox(width: 16), Text('Searching…', style: Mib.text(22, color: Mib.white))],
                ]),
                if ((live?['text'] as String?)?.isNotEmpty == true) ...[
                  const SizedBox(height: 4),
                  Text('${live!['text']}', style: Mib.text(21, color: const Color(0xFFBDBDBD)), maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
              ]),
            ),
            Pressable(
              onTap: _save,
              child: Container(
                width: 176,
                height: 64,
                decoration: BoxDecoration(border: Border.all(color: const Color(0xAAFFFFFF))),
                child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                  const Icon(MibIcons.star, size: 36, color: Mib.white),
                  const SizedBox(width: 18),
                  Text('Save', style: Mib.text(24)),
                ]),
              ),
            ),
            const SizedBox(width: 12),
          ]),
        ),
        const SizedBox(height: 26),
        // Tuning scale.
        SizedBox(
          height: 82,
          // The tuning scale reads low to high, left to right, in every language.
          child: Directionality(textDirection: TextDirection.ltr, child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            _Seek(up: false, onTap: () => _do('radioSeek', {'up': false}), onLong: () => _do('radioScan')),
            Expanded(
              child: LayoutBuilder(builder: (context, c) {
                final lo = am ? 531.0 : 87.5, hi = am ? 1602.0 : 108.0;
                void tune(Offset p) {
                  final x = ((p.dx - 30) / (c.maxWidth - 60)).clamp(0.0, 1.0);
                  final f = lo + (hi - lo) * x;
                  setState(() => live = {...?live, 'freq': am ? (f / 9).round() * 9.0 : (f * 10).round() / 10});
                }
                return GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTapDown: (d) => tune(d.localPosition),
                  onHorizontalDragUpdate: (d) => tune(d.localPosition),
                  onTapUp: (_) => _do('radioTune', {'am': am, 'freq': freq}),
                  onHorizontalDragEnd: (_) => _do('radioTune', {'am': am, 'freq': freq}),
                  child: CustomPaint(painter: _Scale((freq - lo) / (hi - lo), am ? '531' : '88', am ? '1602' : '108'), size: Size.infinite),
                );
              }),
            ),
            _Seek(up: true, onTap: () => _do('radioSeek', {'up': true}), onLong: () => _do('radioScan')),
          ])),
        ),
        Padding(padding: const EdgeInsets.only(top: 8, bottom: 8), child: Text('Favourites', style: Mib.text(26))),
        for (var row = 0; row < 2; row++) ...[
          if (row > 0) const SizedBox(height: 13),
          SizedBox(
            height: 93,
            child: Row(children: [
              for (var col = 0; col < 3; col++) ...[
                if (col > 0) const SizedBox(width: 13),
                Expanded(child: _favourite(row * 3 + col)),
              ],
            ]),
          ),
        ],
      ]),
    );
  }

  Widget _favourite(int i) {
    final p = i < presets.length ? presets[i] : null;
    final f = ((p?['freq'] ?? 0) as num).toDouble();
    final empty = f <= 0;
    final on = !empty && (f - freq).abs() < 0.05;
    final name = p?['name'] as String?;
    return Pressable(
      onTap: () => empty ? _do('radioSave', {'slot': i}) : _do('radioTune', {'am': f > 200, 'freq': f}),
      onLongPress: () => _do('radioSave', {'slot': i}),
      child: Container(
        decoration: BoxDecoration(
          border: Border(
            left: BorderSide(color: on ? Mib.white : const Color(0x99FFFFFF), width: on ? 5 : 1),
            top: const BorderSide(color: Color(0x99FFFFFF)),
            right: const BorderSide(color: Color(0x99FFFFFF)),
            bottom: const BorderSide(color: Color(0x99FFFFFF)),
          ),
        ),
        child: Row(children: [
          SizedBox(width: 68, child: Center(child: Text('${i + 1}', style: Mib.text(30, color: Mib.grey)))),
          Expanded(
            child: empty
                ? Text('Empty – press Save', style: Mib.text(25))
                : Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(name?.isNotEmpty == true ? name! : (f > 200 ? 'AM' : 'FM'), style: Mib.text(26), maxLines: 1, overflow: TextOverflow.ellipsis),
                    Text(f > 200 ? '${f.round()} kHz' : '${f.toStringAsFixed(1)} MHz', style: Mib.text(21, color: Mib.grey)),
                  ]),
          ),
        ]),
      ),
    );
  }
}

/// Seek button: a bar and two chevrons (|<< and >>|).
class _Seek extends StatelessWidget {
  const _Seek({required this.up, required this.onTap, this.onLong});
  final bool up;
  final VoidCallback onTap;
  final VoidCallback? onLong;
  @override
  Widget build(BuildContext context) => Pressable(
        onTap: onTap,
        onLongPress: onLong,
        child: SizedBox(width: 60, height: 60, child: CustomPaint(painter: _SeekPainter(up))),
      );
}

class _SeekPainter extends CustomPainter {
  _SeekPainter(this.up);
  final bool up;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    if (up) {
      canvas.translate(size.width, 0);
      canvas.scale(-1, 1);
    }
    final p = Paint()
      ..color = Mib.white
      ..strokeWidth = 2.6
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round;
    final y = 26.0, h = 11.0;
    canvas.drawLine(Offset(4, y - h - 1), Offset(4, y + h + 1), p);
    for (final x in [12.0, 28.0]) {
      canvas.drawPath(Path()..moveTo(x + 13, y - h)..lineTo(x, y)..lineTo(x + 13, y + h), p);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_SeekPainter old) => false;
}

/// Tuning scale: ticks every half step (taller every other one), the station marker as a
/// bright double line, end labels under the ends.
class _Scale extends CustomPainter {
  _Scale(this.at, this.lo, this.hi);
  final double at;
  final String lo, hi;
  @override
  void paint(Canvas canvas, Size size) {
    const l = 30.0;
    final r = size.width - 30;
    const top = 4.0, bottom = 52.0;
    for (var i = 0; i <= 40; i++) {
      final x = l + (r - l) * i / 40;
      final tall = i % 2 == 0;
      canvas.drawLine(Offset(x, tall ? top + 8 : top + 14), Offset(x, tall ? bottom : bottom - 10),
          Paint()..color = Color(tall ? 0xFF9A9A9A : 0xFF5E5E5E)..strokeWidth = 1.3);
    }
    final mx = l + (r - l) * at.clamp(0, 1);
    canvas.drawRect(Rect.fromLTRB(mx - 2.5, top, mx + 2.5, bottom + 2), Paint()..color = const Color(0x40FFFFFF));
    canvas.drawLine(Offset(mx, top), Offset(mx, bottom + 2), Paint()..color = Mib.white..strokeWidth = 3.4);
    void label(String s, double x) {
      final tp = TextPainter(text: TextSpan(text: s, style: Mib.text(19, color: Mib.grey)), textDirection: TextDirection.ltr)..layout();
      tp.paint(canvas, Offset(x - tp.width / 2, bottom + 6));
    }
    label(lo, l);
    label(hi, r);
  }

  @override
  bool shouldRepaint(_Scale old) => old.at != at || old.lo != lo;
}
