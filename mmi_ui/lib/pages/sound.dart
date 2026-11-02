import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';

// Settings · Sound, placed to the design (design px ÷ 1.239; x from the content edge,
// y from under the header).
const _bands = ['Bass', 'Low mid', 'Mid', 'High mid', 'Treble'];
const _presets = ['Flat', 'Rock', 'Pop', 'Jazz', 'Voice', 'Custom'];

class SoundPage extends StatefulWidget {
  const SoundPage({super.key});
  @override
  State<SoundPage> createState() => _SoundPageState();
}

class _SoundPageState extends State<SoundPage> {
  // Levels in millibels per visible band, the unit's range, the chosen preset.
  List<int> _levels = [200, 200, 200, 200, 200];
  int _min = -1500, _max = 1500;
  String _preset = 'Custom';
  int _speedVolume = 2;

  @override
  void initState() {
    super.initState();
    _load();
  }

  // Balance (0 left … 18 right) and fader (0 front … 18 rear), 9 = centre.
  int _bal = 9, _fad = 9;

  void _place(Offset p, Size size) => setState(() {
        _bal = (p.dx / size.width * 18).round().clamp(0, 18);
        _fad = (p.dy / size.height * 18).round().clamp(0, 18);
      });

  void _step(int dx, int dy) {
    setState(() {
      _bal = (_bal + dx).clamp(0, 18);
      _fad = (_fad + dy).clamp(0, 18);
    });
    _sendAmp();
  }

  void _sendAmp() => Bridge.call('ampSet', {'balance': _bal, 'fader': _fad});

  Widget _arrow(double left, double top, IconData icon, VoidCallback onTap) => Positioned(
        left: left - 8,
        top: top - 8,
        child: Pressable(onTap: onTap, child: Padding(padding: const EdgeInsets.all(8), child: Icon(icon, size: 32, color: const Color(0xCCFFFFFF)))),
      );

  Future<void> _load() async {
    final amp = await Bridge.call<Map<Object?, Object?>>('ampGet');
    if (amp != null && mounted) {
      setState(() {
        _bal = (amp['balance'] as num).toInt();
        _fad = (amp['fader'] as num).toInt();
      });
    }
    final m = await Bridge.call<Map<Object?, Object?>>('eqGet');
    if (m == null || !mounted) return;
    setState(() {
      _levels = (m['levels'] as List).cast<int>();
      _min = m['min'] as int;
      _max = m['max'] as int;
      _preset = m['preset'] as String;
      _speedVolume = m['speedVolume'] as int;
    });
  }

  void _setBand(int i, double v) {
    final level = (_min + v * (_max - _min)).round();
    setState(() {
      _levels[i] = level;
      _preset = 'Custom';
    });
    Bridge.call('eqSet', {'band': i, 'level': level});
  }

  Future<void> _choose(String name) async {
    setState(() => _preset = name);
    await Bridge.call('eqPreset', {'name': name});
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(children: [
      // Equaliser panel.
      Positioned(
        left: 12, top: 19, width: 654, height: 333,
        child: Container(
          decoration: Mib.glass(),
          child: Stack(children: [
            Positioned(left: 19, top: 14, child: Text('Equaliser', style: Mib.text(24))),
            for (var i = 0; i < 5; i++)
              Positioned(
                left: 79.0 + i * 125.6 - 50, top: 44, width: 100, bottom: 10,
                child: Column(children: [
                  Text('${_levels[i] >= 0 ? '+' : ''}${(_levels[i] / 100).round()}', style: Mib.text(20)),
                  const SizedBox(height: 6),
                  Expanded(child: _VerticalSlider(value: (_levels[i] - _min) / (_max - _min), onChanged: (v) => _setBand(i, v))),
                  const SizedBox(height: 10),
                  Text(_bands[i], style: Mib.text(20)),
                ]),
              ),
          ]),
        ),
      ),
      // Presets panel.
      Positioned(
        left: 687, top: 19, width: 424, height: 333,
        child: Container(
          decoration: Mib.glass(),
          child: Stack(children: [
            Positioned(left: 19, top: 14, child: Text('Presets', style: Mib.text(24))),
            for (var i = 0; i < 6; i++)
              Positioned(
                left: 19.0 + (i % 2) * 203, top: 59.0 + (i ~/ 2) * 88, width: 188, height: 72,
                child: Pressable(
                  onTap: () => _choose(_presets[i]),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(4),
                      border: Border.all(color: const Color(0x55FFFFFF)),
                      gradient: _presets[i] == _preset
                          ? const LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0xFF8C8C8C), Color(0xFF5E5E5E)])
                          : const LinearGradient(colors: [Color(0xFF050505), Color(0xFF050505)]),
                    ),
                    alignment: Alignment.center,
                    child: Text(_presets[i], style: Mib.text(25)),
                  ),
                ),
              ),
          ]),
        ),
      ),
      // Balance / fader (shown as the car's amplifier has it).
      Positioned(
        left: 12, top: 380, width: 428, height: 204,
        child: Container(
          decoration: Mib.glass(),
          child: Stack(children: [
            Positioned(left: 20, top: 14, child: Text('Balance / fader', style: Mib.text(24))),
            Positioned(left: 147, top: 60, width: 133, height: 112, child: ShaderMask(
                // Soft edges so the seats melt into the glass like the design.
                shaderCallback: (r) => const RadialGradient(radius: 0.62, colors: [Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0x00FFFFFF)], stops: [0, 0.7, 1]).createShader(r),
                blendMode: BlendMode.dstIn,
                child: Image.asset('assets/images/sound_seats.webp', fit: BoxFit.contain),
              )),
            // Where the sound is centred: drag the dot over the seats, or tap the arrows.
            Positioned(
              left: 147, top: 60, width: 133, height: 112,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onPanUpdate: (d) => _place(d.localPosition, const Size(133, 112)),
                onTapDown: (d) => _place(d.localPosition, const Size(133, 112)),
                onPanEnd: (_) => _sendAmp(),
                onTapUp: (_) => _sendAmp(),
                child: Stack(children: [
                  Positioned(
                    left: 133 * _bal / 18 - 9,
                    top: 112 * _fad / 18 - 9,
                    child: Container(
                      width: 18,
                      height: 18,
                      decoration: const BoxDecoration(
                        shape: BoxShape.circle,
                        color: Mib.white,
                        border: Border.fromBorderSide(BorderSide(color: Color(0x88000000), width: 1.5)),
                      ),
                    ),
                  ),
                ]),
              ),
            ),
            _arrow(100, 100, MibIcons.chevron_left, () => _step(-1, 0)),
            _arrow(296, 100, MibIcons.chevron_right, () => _step(1, 0)),
            _arrow(197, 34, MibIcons.expand_less, () => _step(0, -1)),
            _arrow(197, 168, MibIcons.expand_more, () => _step(0, 1)),
          ]),
        ),
      ),
      // Speed-dependent volume.
      Positioned(
        left: 462, top: 380, width: 649, height: 204,
        child: Container(
          decoration: Mib.glass(),
          child: Stack(children: [
            Positioned(left: 19, top: 14, child: Text('Speed-dependent volume', style: Mib.text(24))),
            Positioned(
              left: 23, top: 76, width: 606,
              child: MibSegmented(
                options: const ['Off', 'Low', 'Mid', 'High'],
                selected: _speedVolume,
                height: 77,
                onSelect: (i) {
                  setState(() => _speedVolume = i);
                  Bridge.call('speedVolume', {'level': i});
                },
              ),
            ),
          ]),
        ),
      ),
    ]);
  }
}

/// Vertical slider: thin dark track, white fill from the bottom, round silver knob.
class _VerticalSlider extends StatelessWidget {
  const _VerticalSlider({required this.value, required this.onChanged});
  final double value;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      void at(Offset p) => onChanged((1 - (p.dy - 14) / (c.maxHeight - 20)).clamp(0.0, 1.0));
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: (d) => at(d.localPosition),
        onVerticalDragUpdate: (d) => at(d.localPosition),
        child: CustomPaint(size: Size(c.maxWidth, c.maxHeight), painter: _SliderPainter(value.clamp(0.0, 1.0))),
      );
    });
  }
}

class _SliderPainter extends CustomPainter {
  _SliderPainter(this.v);
  final double v;

  @override
  void paint(Canvas canvas, Size size) {
    final cx = size.width / 2;
    const top = 14.0;
    final bottom = size.height - 6;
    final y = bottom - (bottom - top) * v;
    final track = Paint()
      ..strokeWidth = 5.5
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(Offset(cx, top), Offset(cx, bottom), track..color = const Color(0xFF3C3C3C));
    canvas.drawLine(Offset(cx, y), Offset(cx, bottom), track..color = Mib.white);
    canvas.drawCircle(Offset(cx, y + 2), 15, Paint()..color = const Color(0x66000000));
    canvas.drawCircle(
      Offset(cx, y),
      13.5,
      Paint()..shader = const RadialGradient(center: Alignment(-0.3, -0.3), colors: [Color(0xFFF2F2F2), Color(0xFF9A9A9A)]).createShader(Rect.fromCircle(center: Offset(cx, y), radius: 13.5)),
    );
  }

  @override
  bool shouldRepaint(_SliderPainter old) => old.v != v;
}
