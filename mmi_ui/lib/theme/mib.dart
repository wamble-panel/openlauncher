import 'package:flutter/widgets.dart' hide Text;
import '../i18n.dart';

/// The MMI look: pure black, white Roboto Regular, grey secondary text, thin
/// fading lines. Sizes on the 1280 × 800 screen are logical pixels (1 : 1 at mdpi).
class Mib {
  Mib._();

  static const black = Color(0xFF000000);
  static const white = Color(0xFFFFFFFF);
  static const grey = Color(0xFFA6A6A6);
  static const line = Color(0x55FFFFFF);
  static const outline = Color(0x88FFFFFF);

  static TextStyle text(double size, {Color color = white, FontWeight weight = FontWeight.w400, double height = 1.2}) =>
      TextStyle(
        fontFamily: 'Roboto',
        fontFamilyFallback: const ['PlexArabic'],
        fontSize: size,
        color: color,
        fontWeight: weight,
        height: height,
        decoration: TextDecoration.none,
      );

  /// Frosted panel from the designs: faint light gradient, outline brighter at the top.
  static BoxDecoration glass({double radius = 6}) => BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        gradient: const LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0x14FFFFFF), Color(0x05FFFFFF)]),
        border: Border.all(color: const Color(0x3AFFFFFF)),
      );

  /// Plain outlined box (tiles, keys, buttons).
  static BoxDecoration outlined({double radius = 0, Color color = outline, double width = 1}) =>
      BoxDecoration(borderRadius: BorderRadius.circular(radius), border: Border.all(color: color, width: width));
}

/// Reference-screen scale: logical px per pixel of the 1920-wide home reference.
double refScale(double width) => width / 1920;

/// A symbol drawn as an outline: for glyphs that only exist solid (music note),
/// so the rail keeps one thin-line style.
class OutlineIcon extends StatelessWidget {
  const OutlineIcon(this.icon, {super.key, required this.size, this.color = const Color(0xFFFFFFFF), this.stroke = 1.6});
  final IconData icon;
  final double size;
  final Color color;
  final double stroke;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: size,
      child: Center(
        child: Text(
          String.fromCharCode(icon.codePoint),
          textHeightBehavior: const TextHeightBehavior(applyHeightToFirstAscent: false, applyHeightToLastDescent: false),
          style: TextStyle(
            fontFamily: icon.fontFamily,
            fontSize: size,
            height: 1,
            foreground: Paint()
              ..style = PaintingStyle.stroke
              ..strokeWidth = stroke
              ..strokeJoin = StrokeJoin.round
              ..color = color,
          ),
        ),
      ),
    );
  }
}

/// Solid transport symbols (play, pause, previous, next) drawn as shapes, like the
/// designs; the outline symbol font only has them hollow.
enum Transport { play, pause, prev, next }

class TransportIcon extends StatelessWidget {
  const TransportIcon(this.kind, {super.key, this.size = 44, this.color = const Color(0xFFFFFFFF)});
  final Transport kind;
  final double size;
  final Color color;
  @override
  Widget build(BuildContext context) => SizedBox.square(dimension: size, child: CustomPaint(painter: _TransportPainter(kind, color)));
}

class _TransportPainter extends CustomPainter {
  _TransportPainter(this.kind, this.color);
  final Transport kind;
  final Color color;
  @override
  void paint(Canvas canvas, Size s) {
    final p = Paint()..color = color;
    final w = s.width, h = s.height;
    switch (kind) {
      case Transport.pause:
        canvas.drawRect(Rect.fromLTWH(w * 0.27, h * 0.2, w * 0.15, h * 0.6), p);
        canvas.drawRect(Rect.fromLTWH(w * 0.58, h * 0.2, w * 0.15, h * 0.6), p);
      case Transport.play:
        canvas.drawPath(Path()..moveTo(w * 0.3, h * 0.18)..lineTo(w * 0.8, h * 0.5)..lineTo(w * 0.3, h * 0.82)..close(), p);
      case Transport.prev:
        canvas.drawRect(Rect.fromLTWH(w * 0.24, h * 0.24, w * 0.07, h * 0.52), p);
        canvas.drawPath(Path()..moveTo(w * 0.76, h * 0.24)..lineTo(w * 0.33, h * 0.5)..lineTo(w * 0.76, h * 0.76)..close(), p);
      case Transport.next:
        canvas.drawRect(Rect.fromLTWH(w * 0.69, h * 0.24, w * 0.07, h * 0.52), p);
        canvas.drawPath(Path()..moveTo(w * 0.24, h * 0.24)..lineTo(w * 0.67, h * 0.5)..lineTo(w * 0.24, h * 0.76)..close(), p);
    }
  }

  @override
  bool shouldRepaint(_TransportPainter old) => old.kind != kind || old.color != color;
}
