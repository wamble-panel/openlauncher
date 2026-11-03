import 'dart:ui' show PlatformDispatcher;
import 'package:flutter/widgets.dart';
import 'perf.dart';
import 'shell/shell.dart';

/// The host opens a page by route name, e.g. "/sound".
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Picture cache sized for a 2 GB unit (Flutter's default is 100 MB).
  PaintingBinding.instance.imageCache.maximumSizeBytes = 32 << 20;
  FrameMonitor.start();
  final uri = Uri.parse(PlatformDispatcher.instance.defaultRouteName);
  final page = uri.path.replaceFirst('/', '');
  runApp(MmiApp(initialPage: page.isEmpty ? 'home' : page, splash: uri.queryParameters['splash'] == '1'));
}

/// MMI's screens. The host starts it on a page given by name (default: home).
class MmiApp extends StatelessWidget {
  const MmiApp({super.key, this.initialPage = 'home', this.initialCall, this.splash = false});
  final bool splash;
  final String initialPage;
  final Map<Object?, Object?>? initialCall;

  @override
  Widget build(BuildContext context) {
    return WidgetsApp(
      color: const Color(0xFF000000),
      debugShowCheckedModeBanner: false,
      textStyle: const TextStyle(fontFamily: 'Roboto', color: Color(0xFFFFFFFF)),
      builder: (context, _) => FitScreen(child: MmiShell(initialPage: initialPage, initialCall: initialCall, splash: splash)),
    );
  }
}

/// Every page is laid out for the 1280 × 800 designs. On a wider screen (1024 × 600,
/// 1280 × 720, 1920 × 720) the interface is scaled to fit the height and the width
/// follows the screen. On a taller, squarer screen (1024 × 768) it is scaled to fit
/// the width instead and centred, so pages keep their designed shape: nothing is cut
/// off or squeezed into tall, narrow tiles. Touches are scaled back automatically.
class FitScreen extends StatelessWidget {
  const FitScreen({super.key, required this.child});
  final Widget child;
  static const designWidth = 1280.0;
  static const designHeight = 800.0;

  @override
  Widget build(BuildContext context) {
    final mq = MediaQuery.of(context);
    final size = mq.size;
    if (size.height <= 0 || size.width <= 0) return child;
    final squarer = size.width / size.height < designWidth / designHeight;
    if (!squarer && (size.height - designHeight).abs() < 1) return child;
    final logical = squarer ? const Size(designWidth, designHeight) : Size(size.width * designHeight / size.height, designHeight);
    return ColoredBox(
      color: const Color(0xFF000000),
      child: Center(
        child: FittedBox(
          fit: BoxFit.contain,
          child: SizedBox.fromSize(
            size: logical,
            child: MediaQuery(data: mq.copyWith(size: logical), child: child),
          ),
        ),
      ),
    );
  }
}
