import 'package:flutter/widgets.dart' hide Text;
import '../i18n.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import 'pressable.dart';

/// Page header: small grey section over a large white title, back box on the right,
/// thin line underneath (96 px tall).
class MibHeader extends StatelessWidget {
  const MibHeader({super.key, this.section, required this.title, this.onBack, this.trailing = const [], this.titleTrailing});
  final Widget? titleTrailing;
  final String? section;
  final String title;
  final VoidCallback? onBack;
  final List<Widget> trailing;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 96,
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Mib.line))),
      child: Row(children: [
        Expanded(
          child: Padding(
            padding: const EdgeInsets.only(left: 16),
            child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (section != null) Text(section!, style: Mib.text(21, color: Mib.grey)),
              Row(children: [
                Flexible(child: Text(title, style: Mib.text(38), maxLines: 1, overflow: TextOverflow.ellipsis)),
                if (titleTrailing != null) ...[const SizedBox(width: 28), titleTrailing!],
              ]),
            ]),
          ),
        ),
        ...trailing,
        if (onBack != null)
          Pressable(
            onTap: onBack!,
            child: Container(
              width: 88,
              height: 96,
              decoration: const BoxDecoration(border: Border(left: BorderSide(color: Mib.line))),
              child: const Center(child: Icon(MibIcons.arrow_back, size: 40, color: Mib.white)),
            ),
          ),
      ]),
    );
  }
}

/// Segmented control: equal outlined cells, the chosen one lit flat grey.
class MibSegmented extends StatelessWidget {
  const MibSegmented({super.key, required this.options, required this.selected, required this.onSelect, this.height = 58});
  final List<String> options;
  final int selected;
  final ValueChanged<int> onSelect;
  final double height;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: height,
      decoration: Mib.outlined(radius: 4, color: const Color(0x55FFFFFF)),
      child: Row(children: [
        for (var i = 0; i < options.length; i++)
          Expanded(
            child: Pressable(
              onTap: () => onSelect(i),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 160),
                decoration: BoxDecoration(
                  color: i == selected ? const Color(0xFF6E6E6E) : const Color(0x00000000),
                  border: i > 0 ? const Border(left: BorderSide(color: Color(0x55FFFFFF))) : null,
                ),
                alignment: Alignment.center,
                child: Text(options[i], style: Mib.text(26)),
              ),
            ),
          ),
      ]),
    );
  }
}

/// On / off switch from the designs: grey pill with a white knob when on, dark with a
/// grey knob when off; the knob slides.
class MibToggle extends StatelessWidget {
  const MibToggle({super.key, required this.value, required this.onChanged});
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Pressable(
      radius: 20,
      onTap: () => onChanged(!value),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        width: 80,
        height: 40,
        decoration: BoxDecoration(
          color: value ? const Color(0xFF8A8A8A) : const Color(0xFF050505),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: value ? const Color(0xFFBDBDBD) : const Color(0xFF6A6A6A), width: 1.5),
        ),
        child: AnimatedAlign(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          alignment: value ? Alignment.centerRight : Alignment.centerLeft,
          child: Container(
            margin: const EdgeInsets.all(4),
            width: 30,
            height: 30,
            decoration: BoxDecoration(shape: BoxShape.circle, color: value ? Mib.white : const Color(0xFF9A9A9A)),
          ),
        ),
      ),
    );
  }
}

/// List row: label on the left, [trailing] (value, chevron, switch) on the right, a
/// thin line underneath. Tappable when [onTap] is given.
class MibRow extends StatelessWidget {
  const MibRow({super.key, required this.label, this.value, this.trailing, this.onTap, this.icon, this.height = 81, this.sub});
  final String label;
  final String? value;
  final String? sub;
  final IconData? icon;
  final Widget? trailing;
  final VoidCallback? onTap;
  final double height;

  @override
  Widget build(BuildContext context) {
    final row = Container(
      height: height,
      padding: const EdgeInsets.symmetric(horizontal: 10),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0x40FFFFFF)))),
      child: Row(children: [
        if (icon != null) ...[Icon(icon, size: 34, color: Mib.white), const SizedBox(width: 22)],
        Expanded(
          child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: Mib.text(27), maxLines: 1, overflow: TextOverflow.ellipsis),
            if (sub != null) Text(sub!, style: Mib.text(19, color: Mib.grey), maxLines: 1, overflow: TextOverflow.ellipsis),
          ]),
        ),
        if (value != null) Text(value!, style: Mib.text(27)),
        ?trailing,
        if (onTap != null && trailing == null) ...[const SizedBox(width: 14), const Icon(MibIcons.chevron_right, size: 34, color: Mib.white)],
      ]),
    );
    return onTap == null ? row : Pressable(onTap: onTap!, child: row);
  }
}

/// Section label above a control ("Brightness", "Favourites").
class MibCaption extends StatelessWidget {
  const MibCaption(this.text, {super.key, this.top = 22, this.bottom = 12});
  final String text;
  final double top, bottom;
  @override
  Widget build(BuildContext context) =>
      Padding(padding: EdgeInsets.only(left: 10, top: top, bottom: bottom), child: Text(text, style: Mib.text(27)));
}

/// Outlined square button with one symbol (−, +, backspace).
class MibIconBox extends StatelessWidget {
  const MibIconBox({super.key, required this.icon, required this.onTap, this.width = 63, this.height = 57, this.size = 36});
  final IconData icon;
  final VoidCallback onTap;
  final double width, height, size;
  @override
  Widget build(BuildContext context) => Pressable(
        onTap: onTap,
        child: Container(width: width, height: height, decoration: Mib.outlined(color: const Color(0xAAFFFFFF)), child: Center(child: Icon(icon, size: size, color: Mib.white))),
      );
}

/// Outlined button with a symbol and a word (Save, Source).
class MibButton extends StatelessWidget {
  const MibButton({super.key, required this.label, this.icon, required this.onTap, this.width, this.height = 64, this.fill});
  final String label;
  final IconData? icon;
  final VoidCallback onTap;
  final double? width;
  final double height;
  final Color? fill;
  @override
  Widget build(BuildContext context) => Pressable(
        onTap: onTap,
        child: Container(
          width: width,
          height: height,
          padding: const EdgeInsets.symmetric(horizontal: 20),
          decoration: BoxDecoration(color: fill, border: Border.all(color: const Color(0xAAFFFFFF))),
          child: Row(mainAxisSize: MainAxisSize.min, mainAxisAlignment: MainAxisAlignment.center, children: [
            if (icon != null) ...[Icon(icon, size: 34, color: Mib.white), const SizedBox(width: 14)],
            Text(label, style: Mib.text(24)),
          ]),
        ),
      );
}

/// Horizontal slider from the Display design: − box, white fill up to a grey knob,
/// thin grey rest of the track, + box; Min / Max underneath.
class MibStepSlider extends StatelessWidget {
  const MibStepSlider({super.key, required this.value, required this.onChanged, this.step = 0.05, this.minLabel = 'Min', this.maxLabel = 'Max'});
  final double value;
  final ValueChanged<double> onChanged;
  final double step;
  final String minLabel, maxLabel;

  @override
  Widget build(BuildContext context) {
    // Sliders run low to high, left to right, in every language.
    return Directionality(textDirection: TextDirection.ltr, child: Column(children: [
      Row(children: [
        MibIconBox(icon: MibIcons.remove, onTap: () => onChanged((value - step).clamp(0, 1))),
        Expanded(
          child: LayoutBuilder(builder: (context, c) {
            void at(Offset p) => onChanged(((p.dx - 32) / (c.maxWidth - 64)).clamp(0.0, 1.0));
            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTapDown: (d) => at(d.localPosition),
              onHorizontalDragUpdate: (d) => at(d.localPosition),
              child: SizedBox(height: 57, child: CustomPaint(painter: _TrackPainter(value))),
            );
          }),
        ),
        MibIconBox(icon: MibIcons.add, onTap: () => onChanged((value + step).clamp(0, 1))),
      ]),
      Padding(
        padding: const EdgeInsets.fromLTRB(87, 6, 87, 0),
        child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          Text(minLabel, style: Mib.text(20, color: Mib.grey)),
          Text(maxLabel, style: Mib.text(20, color: Mib.grey)),
        ]),
      ),
    ]));
  }
}

class _TrackPainter extends CustomPainter {
  _TrackPainter(this.v);
  final double v;
  @override
  void paint(Canvas canvas, Size size) {
    final y = size.height / 2;
    const l = 32.0;
    final r = size.width - 32;
    final x = l + (r - l) * v;
    canvas.drawLine(Offset(l, y), Offset(r, y), Paint()..color = const Color(0xFF6A6A6A)..strokeWidth = 1.5);
    canvas.drawLine(Offset(l, y), Offset(x, y), Paint()..color = Mib.white..strokeWidth = 4..strokeCap = StrokeCap.round);
    canvas.drawCircle(Offset(x, y + 1.5), 16.5, Paint()..color = const Color(0x77000000));
    canvas.drawCircle(Offset(x, y), 15, Paint()..color = const Color(0xFFB4B4B4));
  }

  @override
  bool shouldRepaint(_TrackPainter old) => old.v != v;
}
