import 'package:flutter/widgets.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/pressable.dart';

/// One home tile: your artwork, an outline icon with an upper-case label, a coloured underline.
class HomeTile {
  const HomeTile(this.key, this.label, this.icon, this.art, this.underline);
  final String key;
  final String label;
  final IconData icon;
  final String art;
  final Color underline;
}

const _gold = Color(0xFFE8C25A), _amber = Color(0xFFF0A030), _silver = Color(0xFFD9DCE0);
const _blue = Color(0xFF3FA9F5), _lime = Color(0xFFD8E05A), _red = Color(0xFFE5304A);

const homeTiles = [
  HomeTile('radio', 'RADIO', MibIcons.cell_tower, 'radio', _gold),
  HomeTile('music', 'MEDIA', MibIcons.music_note, 'media', _amber),
  HomeTile('phone', 'TELEPHONE', MibIcons.call, 'telephone', _silver),
  HomeTile('nav', 'NAVIGATION', MibIcons.near_me, 'navigation', _blue),
  HomeTile('phonelink', 'PHONE APPS', MibIcons.mobile_share, 'phoneapps', _lime),
  HomeTile('car', 'CAR', MibIcons.directions_car, 'car', _red),
  HomeTile('settings', 'SETTINGS', MibIcons.settings, 'settings', _silver),
  HomeTile('help', 'HELP', MibIcons.info, 'help', _silver),
  HomeTile('video', 'VIDEO', MibIcons.movie, 'video', _amber),
  HomeTile('eq', 'EQUALISER', MibIcons.equalizer, 'equaliser', _gold),
  HomeTile('weather', 'WEATHER', MibIcons.partly_cloudy_day, 'weather', _blue),
  HomeTile('apps', 'APPS', MibIcons.apps, 'apps', _lime),
  HomeTile('browser', 'BROWSER', MibIcons.language, 'browser', _silver),
];

// Reference geometry (1920 × 720 home reference), scaled by width ÷ 1920; only the tile
// height grows on the taller 1280 × 800 screen.
const _tileTop = 116.0, _tileBottomGap = 62.0, _rowGap = 42.0, _stripH = 57.0, _artW = 348.0, _artH = 193.0;
const _tileX = [270.0, 672.0, 1074.0, 1477.0], _tileW = [352.0, 352.0, 353.0, 353.0];
const railW = 180.0, topBarH = 86.0;

/// Home: two swipeable pages of 4 × 2 tiles and the page dots, inside the frame
/// (right of the rail, below the top bar).
class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.selected, required this.onOpen});
  final String selected;
  final ValueChanged<String> onOpen;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _pager = PageController();
  int _page = 0;

  @override
  void didUpdateWidget(HomePage old) {
    super.didUpdateWidget(old);
    final i = homeTiles.indexWhere((t) => t.key == widget.selected);
    if (i >= 0 && i ~/ 8 != _page) _pager.animateToPage(i ~/ 8, duration: const Duration(milliseconds: 320), curve: Curves.easeOutCubic);
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      final k = refScale(MediaQuery.sizeOf(context).width);
      final h = c.maxHeight;
      final tileTop = (_tileTop - topBarH) * k;
      final tileH = (h - tileTop - _tileBottomGap * k - _rowGap * k) / 2;
      final pages = (homeTiles.length + 7) ~/ 8;
      return Stack(children: [
        PageView.builder(
          controller: _pager,
          itemCount: pages,
          onPageChanged: (p) => setState(() => _page = p),
          itemBuilder: (context, page) => Stack(children: [
            for (var i = 0; i < 8 && page * 8 + i < homeTiles.length; i++)
              Positioned(
                left: (_tileX[i % 4] - railW) * k,
                top: tileTop + (tileH + _rowGap * k) * (i ~/ 4),
                width: _tileW[i % 4] * k,
                height: tileH,
                child: _Tile(homeTiles[page * 8 + i], homeTiles[page * 8 + i].key == widget.selected, k, () => widget.onOpen(homeTiles[page * 8 + i].key)),
              ),
          ]),
        ),
        for (var i = 0; i < pages; i++)
          Positioned(
            left: ((i == 0 ? 1009.0 : 1062.0 + (i - 1) * 53) - railW) * k,
            top: h - 26 * k,
            width: (i == 0 ? 28 : 27) * k,
            height: 11 * k,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              decoration: BoxDecoration(color: i == _page ? Mib.white : const Color(0xFF7E7E7E), borderRadius: BorderRadius.circular(2 * k)),
            ),
          ),
      ]);
    });
  }

}

class _Tile extends StatelessWidget {
  const _Tile(this.tile, this.selected, this.k, this.onTap);
  final HomeTile tile;
  final bool selected;
  final double k;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Pressable(
      onTap: onTap,
      child: LayoutBuilder(builder: (context, c) {
        final stripH = _stripH * k;
        final artTop = ((c.maxHeight - stripH - _artH * k) / 2).clamp(0.0, double.infinity);
        return Container(
          foregroundDecoration: selected ? BoxDecoration(border: Border.all(color: const Color(0xD9FFFFFF), width: 3 * k)) : null,
          child: Stack(children: [
            // Side lines fading in from black at the top to grey at the bottom.
            for (final left in [true, false])
              Positioned(
                left: left ? 0 : null,
                right: left ? null : 0,
                top: 0,
                bottom: 0,
                width: 2 * k,
                child: const DecoratedBox(decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Mib.black, Color(0xFF969696)]))),
              ),
            Positioned(left: 0, right: 0, bottom: 0, height: 3 * k, child: ColoredBox(color: tile.underline)),
            Positioned(
              left: 2 * k,
              top: artTop,
              width: _artW * k,
              height: _artH * k,
              child: Image.asset('assets/images/home_art_${tile.art}.webp', fit: BoxFit.contain, filterQuality: FilterQuality.medium),
            ),
            Positioned(
              left: 0,
              right: 0,
              bottom: 6 * k,
              height: stripH - 6 * k,
              child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                Icon(tile.icon, size: 36 * k, color: Mib.white),
                SizedBox(width: 12 * k),
                Text(tile.label, style: Mib.text(34 * k), maxLines: 1),
              ]),
            ),
          ]),
        );
      }),
    );
  }
}
