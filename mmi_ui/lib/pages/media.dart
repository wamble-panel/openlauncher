import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';

/// Media · Now playing (design 32): own header (apps, Source, device over "Now
/// playing", sound settings), title / artist / album with the cover on the right,
/// position bar with elapsed | remaining, back / play-pause / next and More.
class NowPlayingPage extends StatefulWidget {
  const NowPlayingPage({super.key, required this.onOpen, required this.onBack});
  final ValueChanged<String> onOpen;
  final VoidCallback onBack;
  @override
  State<NowPlayingPage> createState() => _NowPlayingPageState();
}

class _NowPlayingPageState extends State<NowPlayingPage> with Live<NowPlayingPage> {
  @override
  String get liveMethod => 'mediaGet';
  @override
  String? get liveTopic => 'media';
  @override
  Duration get liveEvery => const Duration(seconds: 5);

  Object? _artId;
  // The position moves on locally between updates from the car side.
  late final Timer _tick = Timer.periodic(const Duration(seconds: 1), (_) {
    if (live?['playing'] == true && _drag == null) setState(() => _sinceUpdate += 1000);
  });
  int _sinceUpdate = 0;
  Object? _lastPos;

  @override
  void dispose() {
    _tick.cancel();
    super.dispose();
  }
  Uint8List? _art;
  ui.Image? _glow;
  double? _drag;

  /// The cover shrunk to 40 px and blurred once (a few hundred pixels of work), shown
  /// stretched behind the page: the soft colour glow of Audi / Apple Music without
  /// any live blur, so nothing extra is drawn per frame.
  static Future<ui.Image?> _bakeGlow(Uint8List bytes) async {
    try {
      final codec = await ui.instantiateImageCodec(bytes, targetWidth: 40, targetHeight: 40);
      final tiny = (await codec.getNextFrame()).image;
      final rec = ui.PictureRecorder();
      Canvas(rec).drawImage(tiny, Offset.zero, Paint()..imageFilter = ui.ImageFilter.blur(sigmaX: 4, sigmaY: 4, tileMode: TileMode.clamp));
      final out = await rec.endRecording().toImage(40, 40);
      tiny.dispose();
      return out;
    } catch (_) {
      return null;
    }
  }

  @override
  Future<void> refresh() async {
    await super.refresh();
    final id = live?['artId'];
    if (id != null && id != _artId) {
      _artId = id;
      final bytes = await Bridge.call<Uint8List>('mediaArt');
      final glow = bytes == null ? null : await _bakeGlow(bytes);
      if (mounted) {
        setState(() {
          _art = bytes;
          _glow = glow;
        });
      }
    } else if (id == null && _art != null && live != null) {
      setState(() {
        _art = null;
        _glow = null;
        _artId = null;
      });
    }
  }

  String _t(int ms) {
    final s = (ms / 1000).round().abs();
    return '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}';
  }

  void _cmd(String c) async {
    await Bridge.call('media', {'cmd': c});
    refresh();
  }

  @override
  Widget build(BuildContext context) {
    final title = (live?['title'] as String?) ?? '';
    final artist = (live?['artist'] as String?) ?? '';
    final album = (live?['album'] as String?) ?? '';
    final playing = live?['playing'] == true;
    final dur = ((live?['dur'] ?? 0) as num).toInt();
    if (live?['pos'] != _lastPos) {
      _lastPos = live?['pos'];
      _sinceUpdate = 0;
    }
    final raw = ((live?['pos'] ?? 0) as num).toInt() + (_tick.isActive ? _sinceUpdate : 0);
    final pos = dur > 0 ? raw.clamp(0, dur) : raw;
    final source = (live?['source'] as String?) ?? 'Media';
    final v = _drag ?? (dur > 0 ? (pos / dur).clamp(0.0, 1.0) : 0.0);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      // Header.
      Container(
        height: 96,
        decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Mib.line))),
        child: Row(children: [
          _HeadBox(width: 94, onTap: () => widget.onOpen('apps'), child: const Icon(MibIcons.apps, size: 48, color: Mib.white)),
          _HeadBox(
            width: 163,
            onTap: () => widget.onOpen('source'),
            child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              const Icon(MibIcons.mobile_share, size: 34, color: Mib.white),
              const SizedBox(width: 12),
              Text('Source', style: Mib.text(23)),
            ]),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(left: 26),
              child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(source, style: Mib.text(21, color: Mib.grey)),
                Text('Now playing', style: Mib.text(34)),
              ]),
            ),
          ),
          Pressable(
            onTap: () => widget.onOpen('sound'),
            child: Container(
              width: 88,
              height: 96,
              decoration: const BoxDecoration(border: Border(left: BorderSide(color: Mib.line))),
              child: const Center(child: Icon(MibIcons.settings, size: 42, color: Mib.white)),
            ),
          ),
        ]),
      ),
      if (live != null && title.isEmpty)
        Expanded(
          child: Center(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Text('Nothing is playing', style: Mib.text(32)),
              const SizedBox(height: 10),
              Text('Choose a source to start music.', style: Mib.text(24, color: Mib.grey)),
              const SizedBox(height: 28),
              MibButton(label: 'Source', icon: MibIcons.mobile_share, onTap: () => widget.onOpen('source'), width: 220),
            ]),
          ),
        )
      else
        Expanded(
          child: Stack(children: [
            // Album glow: cross-fades when the song changes.
            Positioned.fill(
              child: RepaintBoundary(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 700),
                  child: _glow == null ? const SizedBox.expand() : _Glow(key: ValueKey(_artId), image: _glow!),
                ),
              ),
            ),
            Padding(
            padding: const EdgeInsets.only(left: 18, top: 26),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              SizedBox(
                height: 266,
                child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 40),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(title, style: Mib.text(48), maxLines: 1, overflow: TextOverflow.ellipsis),
                        const SizedBox(height: 18),
                        Text(artist, style: Mib.text(33, color: const Color(0xFFBDBDBD)), maxLines: 1, overflow: TextOverflow.ellipsis),
                        const SizedBox(height: 10),
                        Text(album, style: Mib.text(33, color: const Color(0xFFBDBDBD)), maxLines: 1, overflow: TextOverflow.ellipsis),
                      ]),
                    ),
                  ),
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 400),
                    child: Container(
                      key: ValueKey(_artId),
                      width: 263,
                      height: 266,
                      decoration: BoxDecoration(border: Border.all(color: const Color(0xCCFFFFFF), width: 1.5), color: const Color(0xFF111111)),
                      child: _art != null
                          ? Image.memory(_art!, fit: BoxFit.cover, gaplessPlayback: true, cacheWidth: 263)
                          : const Center(child: Icon(MibIcons.music_note, size: 110, color: Color(0x66FFFFFF))),
                    ),
                  ),
                  const SizedBox(width: 4),
                ]),
              ),
              const SizedBox(height: 18),
              Row(children: [
                const Spacer(flex: 63),
                Text(dur > 0 ? '${_t(pos)}   |   -${_t(dur - pos)}' : (playing ? 'Playing' : 'Paused'), style: Mib.text(24)),
                const Spacer(flex: 30),
              ]),
              const SizedBox(height: 6),
              LayoutBuilder(builder: (context, c) {
                double at(Offset p) => (p.dx / c.maxWidth).clamp(0.0, 1.0);
                return GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onHorizontalDragUpdate: dur > 0 ? (d) => setState(() => _drag = at(d.localPosition)) : null,
                  onHorizontalDragEnd: dur > 0
                      ? (_) {
                          Bridge.call('media', {'cmd': 'seek', 'ms': (_drag! * dur).round()});
                          setState(() => _drag = null);
                        }
                      : null,
                  child: RepaintBoundary(child: SizedBox(height: 40, child: CustomPaint(painter: _Position(v)))),
                );
              }),
              const SizedBox(height: 28),
              Row(children: [
                const Spacer(flex: 27),
                Container(
                  decoration: BoxDecoration(border: Border.all(color: const Color(0xAAFFFFFF))),
                  child: Row(children: [
                    _Ctl(Transport.prev, () => _cmd('prev')),
                    _Ctl(playing ? Transport.pause : Transport.play, () => _cmd('toggle'), divider: true),
                    _Ctl(Transport.next, () => _cmd('next'), divider: true),
                  ]),
                ),
                const Spacer(flex: 20),
                Pressable(
                  onTap: () => widget.onOpen('source'),
                  child: Padding(
                    padding: const EdgeInsets.all(8),
                    child: Row(children: [
                      const Icon(MibIcons.more_horiz, size: 40, color: Mib.white),
                      const SizedBox(width: 18),
                      Text('More', style: Mib.text(25)),
                    ]),
                  ),
                ),
              ]),
            ]),
          ),
          ]),
        ),
    ]);
  }
}

/// The baked glow, stretched smooth (bilinear) at reduced strength, darkened towards
/// the text on the left and the bottom so white text stays easy to read.
class _Glow extends StatelessWidget {
  const _Glow({super.key, required this.image});
  final ui.Image image;
  @override
  Widget build(BuildContext context) => Stack(fit: StackFit.expand, children: [
        RawImage(image: image, fit: BoxFit.cover, filterQuality: FilterQuality.medium, opacity: const AlwaysStoppedAnimation(0.6)),
        const DecoratedBox(
          decoration: BoxDecoration(gradient: LinearGradient(colors: [Color(0xE6000000), Color(0x99000000), Color(0x40000000)], stops: [0, 0.45, 1])),
        ),
        const DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0x00000000), Color(0xCC000000)], stops: [0.4, 1]),
          ),
        ),
      ]);
}

class _HeadBox extends StatelessWidget {
  const _HeadBox({required this.width, required this.onTap, required this.child});
  final double width;
  final VoidCallback onTap;
  final Widget child;
  @override
  Widget build(BuildContext context) => Pressable(
        onTap: onTap,
        child: Container(
          width: width,
          height: 96,
          decoration: const BoxDecoration(border: Border(right: BorderSide(color: Mib.line))),
          child: Center(child: child),
        ),
      );
}

class _Ctl extends StatelessWidget {
  const _Ctl(this.icon, this.onTap, {this.divider = false});
  final Transport icon;
  final VoidCallback onTap;
  final bool divider;
  @override
  Widget build(BuildContext context) => Pressable(
        onTap: onTap,
        child: Container(
          width: 163,
          height: 73,
          decoration: divider ? const BoxDecoration(border: Border(left: BorderSide(color: Color(0xAAFFFFFF)))) : null,
          child: Center(child: TransportIcon(icon, size: 52)),
        ),
      );
}

class _Position extends CustomPainter {
  _Position(this.v);
  final double v;
  @override
  void paint(Canvas canvas, Size size) {
    final y = size.height / 2;
    final x = size.width * v;
    canvas.drawLine(Offset(0, y), Offset(size.width, y), Paint()..color = const Color(0xFF4A4A4A)..strokeWidth = 4..strokeCap = StrokeCap.round);
    canvas.drawLine(Offset(0, y), Offset(x, y), Paint()..color = Mib.white..strokeWidth = 4..strokeCap = StrokeCap.round);
    canvas.drawCircle(Offset(x, y), 11, Paint()..color = Mib.black);
    canvas.drawCircle(Offset(x, y), 11, Paint()..color = Mib.white..style = PaintingStyle.stroke..strokeWidth = 2.4);
  }

  @override
  bool shouldRepaint(_Position old) => old.v != v;
}

/// Media · Source: where the music comes from (Bluetooth, USB, radio, music apps).
class SourcePage extends StatefulWidget {
  const SourcePage({super.key, required this.onOpen});
  final ValueChanged<String> onOpen;
  @override
  State<SourcePage> createState() => _SourcePageState();
}

class _SourcePageState extends State<SourcePage> with Live<SourcePage> {
  @override
  String get liveMethod => 'sourcesGet';
  @override
  String? get liveTopic => 'media';
  @override
  Duration get liveEvery => const Duration(seconds: 5);

  @override
  Widget build(BuildContext context) {
    final list = ((live?['sources'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    IconData icon(String k) => switch (k) {
          'bt' => MibIcons.bluetooth,
          'usb' => MibIcons.usb,
          'radio' => MibIcons.cell_tower,
          _ => MibIcons.queue_music,
        };
    return ListView(padding: const EdgeInsets.only(top: 12), children: [
      MibRow(label: 'Radio', icon: MibIcons.cell_tower, onTap: () => widget.onOpen('radio')),
      MibRow(label: 'AUX input', icon: MibIcons.headphones, onTap: () => Bridge.call('aux')),
      for (final s in list)
        MibRow(
          label: '${s['label']}',
          sub: s['playing'] == true ? 'Playing' : null,
          icon: icon('${s['kind']}'),
          onTap: () async {
            // USB opens its library; every other source starts and shows Now playing.
            if (s['key'] == 'usb') {
              widget.onOpen('usb');
              return;
            }
            await Bridge.call('source', {'key': s['key']});
            widget.onOpen('music');
          },
        ),
    ]);
  }
}
