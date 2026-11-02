import 'dart:async';
import 'package:flutter/widgets.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';

/// Start-up screen (design 36): your car fades in over black, a thin line fills
/// underneath, "MMI" small at the bottom; then everything fades into the home screen.
class Splash extends StatefulWidget {
  const Splash({super.key, required this.onDone});
  final VoidCallback onDone;
  @override
  State<Splash> createState() => _SplashState();
}

class _SplashState extends State<Splash> with SingleTickerProviderStateMixin {
  late final AnimationController _t = AnimationController(vsync: this, duration: const Duration(milliseconds: 2600))
    ..forward().whenComplete(() => widget.onDone());

  @override
  void dispose() {
    _t.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _t,
      builder: (context, _) {
        final t = _t.value * 2600;
        final car = Curves.easeOut.transform((t / 700).clamp(0.0, 1.0));
        final line = Curves.easeInOutCubic.transform(((t - 300) / 1700).clamp(0.0, 1.0));
        final out = 1 - Curves.easeIn.transform(((t - 2200) / 400).clamp(0.0, 1.0));
        return Opacity(
          opacity: out,
          child: ColoredBox(
            color: Mib.black,
            child: Stack(children: [
              Positioned(
                left: 0,
                right: 0,
                top: 172,
                height: 340,
                child: Opacity(
                  opacity: car,
                  child: Transform.scale(scale: 0.96 + 0.04 * car, child: Image.asset('assets/images/boot_car.webp', fit: BoxFit.contain)),
                ),
              ),
              Positioned(
                left: 384,
                width: 512,
                top: 548,
                height: 3,
                child: CustomPaint(painter: _Line(line)),
              ),
              Positioned(left: 0, right: 0, top: 712, child: Center(child: Text('MMI', style: Mib.text(20, color: Mib.grey).copyWith(letterSpacing: 2)))),
            ]),
          ),
        );
      },
    );
  }
}

class _Line extends CustomPainter {
  _Line(this.v);
  final double v;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFF2E2E2E));
    canvas.drawRect(Rect.fromLTWH(0, 0, size.width * v, size.height), Paint()..color = Mib.white);
  }

  @override
  bool shouldRepaint(_Line old) => old.v != v;
}

/// Speed camera ahead (on every page): camera, kind and distance, the limit sign;
/// red when faster than the limit.
class CameraBanner extends StatelessWidget {
  const CameraBanner({super.key, required this.cam});
  final Map<Object?, Object?> cam;
  @override
  Widget build(BuildContext context) {
    final over = cam['over'] == true;
    final limit = (cam['limit'] as num?)?.toInt() ?? 0;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 10),
      decoration: BoxDecoration(
        color: over ? const Color(0xF0B71C1C) : const Color(0xF0101214),
        border: Border.all(color: const Color(0x88FFFFFF)),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        const Icon(MibIcons.photo_camera, size: 38, color: Mib.white),
        const SizedBox(width: 16),
        Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Text(cam['user'] == true ? 'Marked camera' : 'Speed camera', style: Mib.text(23)),
          Text('${cam['distance'] ?? ''}', style: Mib.text(19, color: const Color(0xCCFFFFFF))),
        ]),
        if (limit > 0) ...[const SizedBox(width: 18), LimitSign(limit)],
      ]),
    );
  }
}

/// Round speed-limit sign: red ring, white disc, black number.
class LimitSign extends StatelessWidget {
  const LimitSign(this.limit, {super.key, this.size = 54});
  final int limit;
  final double size;
  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(shape: BoxShape.circle, color: Mib.white, border: Border.all(color: const Color(0xFFD7262D), width: size * 0.11)),
        child: Center(child: Text('$limit', style: Mib.text(size * 0.38, color: Mib.black, weight: FontWeight.w500))),
      );
}

/// Volume pop-up: speaker, "Volume", a thin level line and the step, for two seconds.
class VolumePopup extends StatelessWidget {
  const VolumePopup({super.key, required this.level, required this.max});
  final int level, max;
  @override
  Widget build(BuildContext context) {
    return Container(
      width: 560,
      height: 76,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      decoration: BoxDecoration(color: const Color(0xF00B0C0E), border: Border.all(color: const Color(0x88FFFFFF))),
      child: Row(children: [
        Icon(level == 0 ? MibIcons.volume_off : MibIcons.volume_up, size: 38, color: Mib.white),
        const SizedBox(width: 18),
        Expanded(
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Text('Volume', style: Mib.text(20)),
            const SizedBox(height: 8),
            TweenAnimationBuilder<double>(
              tween: Tween(end: max <= 0 ? 0 : level / max),
              duration: const Duration(milliseconds: 120),
              builder: (context, v, _) => SizedBox(height: 4, width: double.infinity, child: CustomPaint(painter: _Level(v))),
            ),
          ]),
        ),
        const SizedBox(width: 18),
        SizedBox(width: 36, child: Text('$level', style: Mib.text(24), textAlign: TextAlign.right)),
      ]),
    );
  }
}

class _Level extends CustomPainter {
  _Level(this.v);
  final double v;
  @override
  void paint(Canvas canvas, Size size) {
    final y = size.height / 2;
    canvas.drawLine(Offset(0, y), Offset(size.width, y), Paint()..color = const Color(0x55FFFFFF)..strokeWidth = 2);
    canvas.drawLine(Offset(0, y), Offset(size.width * v, y), Paint()..color = Mib.white..strokeWidth = 3);
  }

  @override
  bool shouldRepaint(_Level old) => old.v != v;
}

/// Shows [child] while [visible], fading in and out.
class Fade extends StatelessWidget {
  const Fade({super.key, required this.visible, required this.child});
  final bool visible;
  final Widget child;
  @override
  Widget build(BuildContext context) => IgnorePointer(
        ignoring: !visible,
        child: AnimatedOpacity(opacity: visible ? 1 : 0, duration: Duration(milliseconds: visible ? 180 : 300), child: child),
      );
}

/// A timer that hides something again after [after].
class Hide {
  Timer? _t;
  void after(Duration d, VoidCallback f) {
    _t?.cancel();
    _t = Timer(d, f);
  }

  void cancel() => _t?.cancel();
}
