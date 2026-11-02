import 'package:flutter/widgets.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';

/// Help: how to drive MMI with the car's controls, in short lines.
class HelpPage extends StatelessWidget {
  const HelpPage({super.key, required this.onOpen});
  final ValueChanged<String> onOpen;

  static const _tips = [
    (MibIcons.radio, 'Knob', 'Turn to move the white outline, press to choose. On Radio, turning tunes.'),
    (MibIcons.arrow_back, 'BACK', 'Goes to the page before. The home key always goes home.'),
    (MibIcons.cell_tower, 'RADIO · MEDIA · PHONE', 'Open their pages from anywhere.'),
    (MibIcons.equalizer, 'TONE', 'Opens Sound: equaliser, presets and speed-dependent volume.'),
    (MibIcons.star, 'Radio favourites', 'Save puts the station in the first free place; hold a favourite to replace it. Keys 1–6 tune them.'),
    (MibIcons.photo_camera, 'Speed cameras', 'Alerts show on every page, also without the map.'),
  ];

  @override
  Widget build(BuildContext context) {
    return ListView(padding: const EdgeInsets.only(top: 8, bottom: 24), children: [
      for (final t in _tips)
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 16),
          decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0x40FFFFFF)))),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Icon(t.$1, size: 36, color: Mib.white),
            const SizedBox(width: 22),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(t.$2, style: Mib.text(25)),
                const SizedBox(height: 4),
                Text(t.$3, style: Mib.text(21, color: Mib.grey)),
              ]),
            ),
          ]),
        ),
      MibRow(label: 'Software update', icon: MibIcons.sync, onTap: () => onOpen('classic:SYSTEM_SET')),
    ]);
  }
}
