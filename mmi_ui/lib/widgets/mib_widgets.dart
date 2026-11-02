import 'package:flutter/widgets.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import 'pressable.dart';

/// Page header: small grey section over a large white title, back box on the right,
/// thin line underneath (96 px tall).
class MibHeader extends StatelessWidget {
  const MibHeader({super.key, this.section, required this.title, this.onBack, this.trailing = const []});
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
              Text(title, style: Mib.text(38), maxLines: 1, overflow: TextOverflow.ellipsis),
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
