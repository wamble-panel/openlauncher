import 'package:flutter/widgets.dart' hide Text;
import '../i18n.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/pressable.dart';

/// Media · Apps (design 38): 5 × 3 rounded tiles with one outline symbol each. The
/// first page is the fixed set; every other installed app follows on the next pages.
/// The car side finds the installed app for each tile.
class AppsPage extends StatefulWidget {
  const AppsPage({super.key});
  @override
  State<AppsPage> createState() => _AppsPageState();
}

const _fixed = [
  ('browser', 'Browser', MibIcons.language),
  ('files', 'Files', MibIcons.folder),
  ('music', 'Music', MibIcons.music_note),
  ('video', 'Video', MibIcons.play_arrow),
  ('camera', 'Camera', MibIcons.photo_camera),
  ('calculator', 'Calculator', MibIcons.calculate),
  ('calendar', 'Calendar', MibIcons.calendar_month),
  ('clock', 'Clock', MibIcons.schedule),
  ('gallery', 'Gallery', MibIcons.image),
  ('maps', 'Maps', MibIcons.place),
  ('weather', 'Weather', MibIcons.partly_cloudy_day),
  ('settings', 'Settings', MibIcons.settings),
  ('store', 'Store', MibIcons.shopping_cart),
  ('notes', 'Notes', MibIcons.description),
  ('recorder', 'Recorder', MibIcons.mic),
];

class _AppsPageState extends State<AppsPage> with Live<AppsPage> {
  @override
  String get liveMethod => 'appsGet';
  @override
  Duration get liveEvery => const Duration(seconds: 10);

  @override
  Widget build(BuildContext context) {
    final found = ((live?['found'] as List?) ?? const []).cast<String>().toSet();
    final others = ((live?['others'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    final pages = <List<(String, String, IconData, bool)>>[
      [for (final f in _fixed) (f.$1, f.$2, f.$3, live == null || found.contains(f.$1))],
      for (var i = 0; i < others.length; i += 15)
        [for (final o in others.skip(i).take(15)) ('pkg:${o['pkg']}', '${o['label']}', MibIcons.apps, true)],
    ];
    return Padding(
      padding: const EdgeInsets.only(left: 33, right: 8, top: 37),
      child: PageView(
        children: [
          for (final page in pages)
            Wrap(spacing: 57, runSpacing: 42, children: [for (final t in page) _AppTile(t)]),
        ],
      ),
    );
  }
}

class _AppTile extends StatelessWidget {
  const _AppTile(this.t);
  final (String, String, IconData, bool) t;
  @override
  Widget build(BuildContext context) {
    final color = t.$4 ? Mib.white : const Color(0x55FFFFFF);
    return Pressable(
      radius: 8,
      onTap: () => Bridge.call('launch', {'key': t.$1}),
      child: Container(
        width: 167,
        height: 147,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0x80FFFFFF), width: 1.2),
          gradient: const LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0xFF0D0D0D), Color(0xFF020202)]),
        ),
        child: Column(children: [
          const SizedBox(height: 22),
          Icon(t.$3, size: 62, color: color),
          const Spacer(),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: Text(t.$2, style: Mib.text(23, color: color), maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
          const SizedBox(height: 16),
        ]),
      ),
    );
  }
}
