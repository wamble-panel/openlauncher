import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import '../bridge.dart';

/// Touch that feels like glass: a soft press-in, a light sheen from the finger that
/// fades, and the glass tap sound. Every Pressable is also a knob stop: turning the
/// knob moves a white outline through them and pressing the knob taps the outlined one.
class Pressable extends StatefulWidget {
  const Pressable({
    super.key,
    required this.onTap,
    required this.child,
    this.sound = true,
    this.radius = 0,
    this.focusable = true,
    this.onLongPress,
    this.autofocus = false,
  });
  final VoidCallback onTap;
  final VoidCallback? onLongPress;
  final Widget child;
  final bool sound;
  final double radius;
  final bool focusable;
  final bool autofocus;

  @override
  State<Pressable> createState() => _PressableState();
}

class _PressableState extends State<Pressable>
    with SingleTickerProviderStateMixin {
  late final AnimationController _press = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 70),
    reverseDuration: const Duration(milliseconds: 320),
  );
  Offset _at = Offset.zero;
  bool _focused = false;

  @override
  void dispose() {
    _press.dispose();
    super.dispose();
  }

  void _fire() {
    if (widget.sound) Bridge.tap();
    widget.onTap();
  }

  Future<void> _knobPress() async {
    final box = context.findRenderObject() as RenderBox?;
    _at = box == null ? Offset.zero : box.size.center(Offset.zero);
    await _press.forward();
    _press.reverse();
    _fire();
  }

  @override
  Widget build(BuildContext context) {
    final body = GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: (d) {
        _at = d.localPosition;
        _press.forward();
      },
      onTapCancel: () => _press.reverse(),
      onTapUp: (_) => _press.reverse(),
      onTap: _fire,
      onLongPress: widget.onLongPress == null
          ? null
          : () {
              _press.reverse();
              Bridge.chime();
              widget.onLongPress!();
            },
      child: AnimatedBuilder(
        animation: _press,
        child: widget.child,
        builder: (context, child) {
          final g = _press.value;
          return Transform.scale(
            scale: 1 - 0.035 * g,
            child: CustomPaint(
              foregroundPainter: g > 0.01 || _focused
                  ? _Sheen(_at, g, _focused, widget.radius)
                  : null,
              child: child,
            ),
          );
        },
      ),
    );
    if (!widget.focusable) return body;
    return Actions(
      actions: {
        ActivateIntent: CallbackAction<ActivateIntent>(
          onInvoke: (_) => _knobPress(),
        ),
      },
      child: Focus(
        autofocus: widget.autofocus,
        onFocusChange: (f) => setState(() => _focused = f),
        onKeyEvent: (node, e) {
          if (e is KeyDownEvent &&
              (e.logicalKey == LogicalKeyboardKey.enter ||
                  e.logicalKey == LogicalKeyboardKey.select)) {
            _knobPress();
            return KeyEventResult.handled;
          }
          return KeyEventResult.ignored;
        },
        child: body,
      ),
    );
  }
}

class _Sheen extends CustomPainter {
  _Sheen(this.at, this.g, this.focused, this.radius);
  final Offset at;
  final double g;
  final bool focused;
  final double radius;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    if (g > 0.01) {
      final r = size.longestSide * (0.45 + 0.45 * g);
      canvas.drawRRect(
        RRect.fromRectAndRadius(rect, Radius.circular(radius)),
        Paint()
          ..shader = RadialGradient(
            colors: [
              Color.fromRGBO(255, 255, 255, 0.2 * g),
              Color.fromRGBO(255, 255, 255, 0.06 * g),
              const Color(0x00FFFFFF),
            ],
          ).createShader(Rect.fromCircle(center: at, radius: r)),
      );
    }
    if (focused) {
      // Knob outline: bright white edge with a faint glow, as on the designs' selected tile.
      final rr = RRect.fromRectAndRadius(
        rect.deflate(1.5),
        Radius.circular(radius),
      );
      canvas.drawRRect(
        rr,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 7
          ..color = const Color(0x22FFFFFF),
      );
      canvas.drawRRect(
        rr,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3
          ..color = const Color(0xFFFFFFFF),
      );
    }
  }

  @override
  bool shouldRepaint(_Sheen old) =>
      old.g != g || old.at != at || old.focused != focused;
}
