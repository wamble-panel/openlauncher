import 'package:flutter/widgets.dart';

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
      TextStyle(fontFamily: 'Roboto', fontSize: size, color: color, fontWeight: weight, height: height, decoration: TextDecoration.none);

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
