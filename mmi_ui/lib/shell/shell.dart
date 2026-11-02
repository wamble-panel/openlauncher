import 'dart:async';
import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/pressable.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/overlays.dart';
import '../pages/apps.dart';
import '../pages/bluetooth.dart';
import '../pages/car.dart';
import '../pages/equaliser.dart';
import '../pages/weather.dart';
import '../pages/help.dart';
import '../pages/home.dart';
import '../pages/media.dart';
import '../pages/phone.dart';
import '../pages/radio.dart';
import '../pages/settings.dart';
import '../pages/sound.dart';

/// Every MMI page: header section and title, and the rail cell it lights.
enum MmiPage {
  home(null, '', 'home'),
  radio('Radio', 'FM', 'radio'),
  music(null, '', 'music'),
  source('Media', 'Source', 'music'),
  apps('Media', 'Apps', 'music'),
  calls('Telephone', 'Call list', 'phone'),
  dial('Telephone', 'Dial number', 'phone'),
  settings(null, 'Settings', null),
  general('Settings', 'General', null),
  display('Settings', 'Display', null),
  sound('Settings', 'Sound', null),
  connections('Settings', 'Bluetooth & connections', null),
  btsearch(null, '', null),
  navset('Settings', 'Navigation', null),
  carset('Settings', 'Car', null),
  language('Settings', 'Language & keyboard', null),
  system('Settings', 'System & updates', null),
  carinfo('Car', 'Car information', null),
  weather(null, 'Weather', null),
  help(null, 'Help', null),
  vendor('Settings', 'Vendor apps', null),
  equaliser('Sound', 'Equaliser', null),
  health('Settings', 'Unit health', null);

  const MmiPage(this.section, this.title, this.rail);
  final String? section;
  final String title;
  final String? rail;

  /// Pages that draw their own header.
  bool get ownHeader => this == music || this == btsearch || this == home;
}

const _railKeys = ['home', 'radio', 'music', 'phone', 'nav'];
const _railIcons = [MibIcons.home, MibIcons.cell_tower, MibIcons.music_note, MibIcons.call, MibIcons.near_me];

/// The frame around every page: top bar across the top, five-cell rail on the left,
/// pages cross-fading in the rest. Back goes to the page before; the car's knob and
/// buttons arrive from the host; a call takes over the page area until it ends.
class MmiShell extends StatefulWidget {
  const MmiShell({super.key, this.initialPage = 'home', this.initialCall, this.splash = false});
  final bool splash;
  final String initialPage;
  final Map<Object?, Object?>? initialCall;

  @override
  State<MmiShell> createState() => _MmiShellState();
}

class _MmiShellState extends State<MmiShell> {
  late MmiPage _page = MmiPage.values.firstWhere((p) => p.name == widget.initialPage, orElse: () => MmiPage.home);
  final _history = <MmiPage>[];
  String _selected = '';
  late Map<Object?, Object?>? _call = widget.initialCall;
  final _scope = FocusScopeNode(debugLabel: 'mmi');
  late bool _splash = widget.splash;
  Map<Object?, Object?>? _camera;
  Map<Object?, Object?>? _volume;
  final _volumeHide = Hide();

  @override
  void initState() {
    super.initState();
    Bridge.listen((call) async {
      switch (call.method) {
        case 'key':
          _onKey((call.arguments as Map)['code'] as int);
        case 'call':
          final m = call.arguments as Map?;
          setState(() => _call = m == null || m['state'] == 'none' ? null : m);
        case 'camera':
          final m = call.arguments as Map?;
          if (m != null && _camera == null) Bridge.chime();
          setState(() => _camera = m);
        case 'volume':
          setState(() => _volume = call.arguments as Map?);
          _volumeHide.after(const Duration(seconds: 2), () {
            if (mounted) setState(() => _volume = null);
          });
        case 'changed':
          Bridge.changes.add('${call.arguments}');
        case 'splash':
          setState(() => _splash = true);
        case 'trim':
          // Android is short of memory: drop decoded pictures (they reload when shown).
          PaintingBinding.instance.imageCache.clear();
          PaintingBinding.instance.imageCache.clearLiveImages();
        case 'page':
          _openKey('${call.arguments}');
      }
      return null;
    });
  }

  @override
  void dispose() {
    _volumeHide.cancel();
    _scope.dispose();
    super.dispose();
  }

  void _open(MmiPage p, {bool remember = true}) {
    if (p == _page) return;
    Bridge.chime();
    setState(() {
      if (p == MmiPage.home) {
        _history.clear();
      } else if (remember) {
        _history.add(_page);
      }
      _page = p;
    });
  }

  void _back() {
    if (_history.isEmpty) {
      _open(MmiPage.home);
    } else {
      Bridge.chime();
      setState(() => _page = _history.removeLast());
    }
  }

  /// Opens a page by key: home tiles, rail cells, settings tiles, car buttons.
  void _openKey(String key) {
    if (key.startsWith('classic:')) {
      Bridge.call('open', {'page': key.substring(8)});
      return;
    }
    final page = switch (key) {
      'phone' => MmiPage.calls,
      'eq' => MmiPage.sound,
      'tone' => MmiPage.sound,
      'phonelink' => null,
      _ => MmiPage.values.where((p) => p.name == key).firstOrNull,
    };
    if (page != null) {
      _open(page);
    } else {
      Bridge.call('open', {'key': key});
    }
  }

  // ── Car knob and buttons ───────────────────────────────────────────────────

  void _onKey(int code) {
    switch (code) {
      case 6 || 19:
        _turn(-1);
      case 5 || 21:
        _turn(1);
      case 66 || 37:
        _press();
      case 18:
        _back();
      case 16:
        _open(MmiPage.radio);
      case 33:
        _open(MmiPage.music);
      case 32:
        _open(MmiPage.calls);
      case 90:
        _open(MmiPage.settings);
      case 24:
        _open(MmiPage.sound);
    }
  }

  void _turn(int dir) {
    Bridge.tick();
    if (_call != null) {
      _moveFocus(dir);
      return;
    }
    switch (_page) {
      case MmiPage.radio:
        Bridge.call('radioStep', {'up': dir > 0});
      case MmiPage.home:
        final i = homeTiles.indexWhere((t) => t.key == _selected);
        setState(() => _selected = homeTiles[i < 0 ? (dir > 0 ? 0 : homeTiles.length - 1) : (i + dir) % homeTiles.length].key);
      default:
        _moveFocus(dir);
    }
  }

  void _moveFocus(int dir) {
    if (_scope.focusedChild == null) {
      dir > 0 ? _scope.nextFocus() : _scope.previousFocus();
    } else {
      dir > 0 ? _scope.nextFocus() : _scope.previousFocus();
    }
  }

  void _press() {
    if (_page == MmiPage.home && _call == null) {
      if (_selected.isNotEmpty) {
        final k = _selected;
        setState(() => _selected = '');
        _openKey(k);
      }
      return;
    }
    final ctx = FocusManager.instance.primaryFocus?.context;
    if (ctx != null) Actions.maybeInvoke(ctx, const ActivateIntent());
  }

  // ── Layout ────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final k = refScale(size.width);
    final top = topBarH * k;
    final rail = railW * k;
    final key = _call != null ? 'call' : _page.name;
    return ColoredBox(
      color: Mib.black,
      child: FocusScope(
        node: _scope,
        child: Stack(children: [
          // Separate repaint areas: a ticking clock or a pressed button redraws only its own
          // part, not the whole screen (less work for the GE8300 and the memory bus).
          Positioned(left: 0, top: 0, right: 0, height: top, child: RepaintBoundary(child: Image.asset('assets/images/home_topbar.webp', fit: BoxFit.fill, cacheWidth: size.width.round()))),
          if (_page != MmiPage.home || _call != null) Positioned(right: 28, top: 0, height: top, child: const RepaintBoundary(child: Center(child: _Clock()))),
          Positioned(left: 0, top: 0, width: rail, bottom: 0, child: RepaintBoundary(child: _Rail(k: k, active: _call != null ? 'phone' : _page.rail, onOpen: _openKey))),
          Positioned(
            left: rail,
            top: top,
            right: 0,
            bottom: 0,
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 260),
              reverseDuration: const Duration(milliseconds: 160),
              switchInCurve: Curves.easeOutCubic,
              child: KeyedSubtree(key: ValueKey(key), child: RepaintBoundary(child: _call != null ? InCallPage(call: _call!) : _pageView())),
            ),
          ),
          // Speed camera banner and volume pop-up, over every page.
          Positioned(
            left: rail,
            right: 0,
            top: top + 14,
            child: Center(child: Fade(visible: _camera != null, child: _camera == null ? const SizedBox() : CameraBanner(cam: _camera!))),
          ),
          Positioned(
            left: rail,
            right: 0,
            bottom: 40,
            child: Center(
              child: Fade(
                visible: _volume != null,
                child: _volume == null ? const SizedBox(width: 560, height: 76) : VolumePopup(level: (_volume!['level'] as num).toInt(), max: (_volume!['max'] as num).toInt()),
              ),
            ),
          ),
          if (_splash) Positioned.fill(child: Splash(onDone: () => setState(() => _splash = false))),
        ]),
      ),
    );
  }

  Widget _pageView() {
    if (_page == MmiPage.home) {
      return HomePage(selected: _selected, onOpen: (key) {
        setState(() => _selected = '');
        _openKey(key);
      });
    }
    final body = switch (_page) {
      MmiPage.music => NowPlayingPage(onOpen: _openKey, onBack: _back),
      MmiPage.btsearch => BtSearchPage(onBack: _back),
      MmiPage.radio => const RadioPage(),
      MmiPage.source => SourcePage(onOpen: _openKey),
      MmiPage.apps => const AppsPage(),
      MmiPage.calls => CallListPage(onDial: () => _open(MmiPage.dial)),
      MmiPage.dial => DialPage(onRecent: () => _open(MmiPage.calls)),
      MmiPage.settings => SettingsGrid(onOpen: _openKey),
      MmiPage.display => const DisplayPage(),
      MmiPage.sound => SoundPage(onFineTune: () => _open(MmiPage.equaliser)),
      MmiPage.equaliser => const EqualiserPage(),
      MmiPage.carinfo => const CarInfoPage(),
      MmiPage.weather => const WeatherPage(),
      MmiPage.help => HelpPage(onOpen: _openKey),
      MmiPage.vendor => const VendorAppsPage(),
      MmiPage.health => const HealthPage(),
      _ => CategoryPage(category: _page.name, onOpen: _openKey),
    };
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: _page.ownHeader
          ? body
          : Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              MibHeader(section: _page.section, title: _page.title, onBack: _back, trailing: _headerButtons()),
              Expanded(child: body),
            ]),
    );
  }

  List<Widget> _headerButtons() => switch (_page) {
        MmiPage.radio => [
            Padding(
              padding: const EdgeInsets.only(right: 14),
              child: MibButton(label: 'FM / AM', width: 150, height: 63, onTap: () async {
                final r = await Bridge.call<Map<Object?, Object?>>('radioGet');
                await Bridge.call('radioBand', {'am': r?['am'] != true});
                Bridge.changes.add('radio');
              }),
            ),
            Padding(padding: const EdgeInsets.only(right: 18), child: MibButton(label: 'Source', icon: MibIcons.radio, width: 174, height: 63, onTap: () => _open(MmiPage.source))),
          ],
        MmiPage.calls => [
            Padding(padding: const EdgeInsets.only(right: 18), child: MibButton(label: 'Dial', icon: MibIcons.dialpad, width: 150, height: 63, onTap: () => _open(MmiPage.dial))),
          ],
        _ => const [],
      };
}

/// Rail: five cells (Home, Radio, Media, Telephone, Navigation) with 2 px black gaps,
/// dark-to-grey gradient, top highlight and bright right edge; the active cell is lit.
class _Rail extends StatelessWidget {
  const _Rail({required this.k, required this.active, required this.onOpen});
  final double k;
  final String? active;
  final ValueChanged<String> onOpen;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      final railTop = 105 * k;
      final cellH = (c.maxHeight - railTop - 22 * k - 2 * k * 4) / 5;
      return Stack(children: [
        for (var i = 0; i < 5; i++)
          Positioned(
            left: 0,
            right: 0,
            top: railTop + (cellH + 2 * k) * i,
            height: cellH,
            child: Pressable(
              focusable: false,
              onTap: () => onOpen(_railKeys[i]),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 220),
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: _railKeys[i] == active
                        ? const [Color(0xFF2A2A2A), Color(0xFF4A4A4A), Color(0xFF6A6A6A)]
                        : const [Color(0xFF000000), Color(0xFF1E1E1E), Color(0xFF3A3A3A)],
                  ),
                  border: Border(
                    top: const BorderSide(color: Color(0xFFBBBBBB), width: 1),
                    right: BorderSide(color: _railKeys[i] == active ? Mib.white : const Color(0xFFD8D8D8), width: 2 * k),
                  ),
                ),
                child: Center(
                  child: _railIcons[i] == MibIcons.music_note
                      ? OutlineIcon(_railIcons[i], size: 76 * k, stroke: 1.4)
                      : Icon(_railIcons[i], size: 76 * k, color: Mib.white),
                ),
              ),
            ),
          ),
      ]);
    });
  }
}

class _Clock extends StatefulWidget {
  const _Clock();
  @override
  State<_Clock> createState() => _ClockState();
}

class _ClockState extends State<_Clock> {
  Timer? _timer;
  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 20), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = DateTime.now();
    return Text('${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}', style: Mib.text(30));
  }
}
