import 'dart:ui' show PlatformDispatcher;
import 'package:flutter/widgets.dart';
import 'shell/shell.dart';

/// The host opens a page by route name, e.g. "/sound".
void main() {
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
      builder: (context, _) => MmiShell(initialPage: initialPage, initialCall: initialCall, splash: splash),
    );
  }
}
