import 'package:flutter/widgets.dart';
import '../bridge.dart';

/// Touch that feels like glass: a soft press-in, a light sheen from the finger that
/// fades, and the glass tap sound.
class Pressable extends StatefulWidget {
  const Pressable({super.key, required this.onTap, required this.child, this.sound = true});
  final VoidCallback onTap;
  final Widget child;
  final bool sound;

  @override
  State<Pressable> createState() => _PressableState();
}

class _PressableState extends State<Pressable> with SingleTickerProviderStateMixin {
  late final AnimationController _press = AnimationController(vsync: this, duration: const Duration(milliseconds: 70), reverseDuration: const Duration(milliseconds: 320));
  Offset _at = Offset.zero;

  @override
  void dispose() {
    _press.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: (d) {
        _at = d.localPosition;
        _press.forward();
      },
      onTapCancel: () => _press.reverse(),
      onTapUp: (_) => _press.reverse(),
      onTap: () {
        if (widget.sound) Bridge.tap();
        widget.onTap();
      },
      child: AnimatedBuilder(
        animation: _press,
        child: widget.child,
        builder: (context, child) {
          final g = _press.value;
          return Transform.scale(
            scale: 1 - 0.035 * g,
            child: CustomPaint(foregroundPainter: g > 0.01 ? _Sheen(_at, g) : null, child: child),
          );
        },
      ),
    );
  }
}

class _Sheen extends CustomPainter {
  _Sheen(this.at, this.g);
  final Offset at;
  final double g;

  @override
  void paint(Canvas canvas, Size size) {
    final r = size.longestSide * (0.45 + 0.45 * g);
    canvas.drawRect(
      Offset.zero & size,
      Paint()
        ..shader = RadialGradient(colors: [Color.fromRGBO(255, 255, 255, 0.2 * g), Color.fromRGBO(255, 255, 255, 0.06 * g), const Color(0x00FFFFFF)])
            .createShader(Rect.fromCircle(center: at, radius: r)),
    );
  }

  @override
  bool shouldRepaint(_Sheen old) => old.g != g || old.at != at;
}
