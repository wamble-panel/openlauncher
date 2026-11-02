import 'dart:async';
import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/pressable.dart';
import '../widgets/mib_widgets.dart';
import '../pages/home.dart';
import '../pages/sound.dart';

/// Every MMI page by name. Pages not yet rebuilt in Flutter show a placeholder.
enum MmiPage {
  home(null, null),
  sound('Settings', 'Sound');

  const MmiPage(this.section, this.title);
  final String? section;
  final String? title;
}

const _railKeys = ['home', 'radio', 'music', 'phone', 'nav'];
const _railIcons = [MibIcons.home, MibIcons.cell_tower, MibIcons.music_note, MibIcons.call, MibIcons.near_me];

/// The frame around every page: top bar across the top, five-cell rail on the left,
/// pages cross-fading in the rest.
class MmiShell extends StatefulWidget {
  const MmiShell({super.key, this.initialPage = 'home'});
  final String initialPage;

  @override
  State<MmiShell> createState() => _MmiShellState();
}

class _MmiShellState extends State<MmiShell> {
  late MmiPage _page = MmiPage.values.firstWhere((p) => p.name == widget.initialPage, orElse: () => MmiPage.home);
  String _selected = '';

  void _open(MmiPage p) {
    if (p == _page) return;
    Bridge.chime();
    setState(() => _page = p);
  }

  void _openKey(String key) {
    switch (key) {
      case 'eq':
      case 'settings':
        _open(MmiPage.sound);
      case 'home':
        _open(MmiPage.home);
      default:
        Bridge.call('open', {'key': key});
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final k = refScale(size.width);
    final top = topBarH * k;
    final rail = railW * k;
    return ColoredBox(
      color: Mib.black,
      child: Stack(children: [
        Positioned(left: 0, top: 0, right: 0, height: top, child: Image.asset('assets/images/home_topbar.webp', fit: BoxFit.fill)),
        if (_page != MmiPage.home) Positioned(right: 28, top: 0, height: top, child: const Center(child: _Clock())),
        Positioned(left: 0, top: 0, width: rail, bottom: 0, child: _Rail(k: k, active: _page == MmiPage.home ? 'home' : null, onOpen: _openKey)),
        Positioned(
          left: rail,
          top: top,
          right: 0,
          bottom: 0,
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 260),
            reverseDuration: const Duration(milliseconds: 160),
            switchInCurve: Curves.easeOutCubic,
            child: KeyedSubtree(key: ValueKey(_page), child: _pageView()),
          ),
        ),
      ]),
    );
  }

  Widget _pageView() {
    if (_page == MmiPage.home) {
      return HomePage(selected: _selected, onOpen: (key) {
        setState(() => _selected = '');
        _openKey(key);
      });
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        MibHeader(section: _page.section, title: _page.title ?? '', onBack: () => _open(MmiPage.home)),
        Expanded(child: switch (_page) {
          MmiPage.sound => const SoundPage(),
          _ => const SizedBox(),
        }),
      ]),
    );
  }
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
              onTap: () => onOpen(_railKeys[i]),
              child: Container(
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
