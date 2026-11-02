import 'dart:ui' show PlatformDispatcher;
import 'package:flutter/widgets.dart';
import 'shell/shell.dart';

/// The host opens a page by route name, e.g. "/sound".
void main() {
  final route = PlatformDispatcher.instance.defaultRouteName.replaceFirst('/', '');
  runApp(MmiApp(initialPage: route.isEmpty ? 'home' : route));
}

/// MMI's screens. The host starts it on a page given by name (default: home).
class MmiApp extends StatelessWidget {
  const MmiApp({super.key, this.initialPage = 'home'});
  final String initialPage;

  @override
  Widget build(BuildContext context) {
    return WidgetsApp(
      color: const Color(0xFF000000),
      debugShowCheckedModeBanner: false,
      textStyle: const TextStyle(fontFamily: 'Roboto', color: Color(0xFFFFFFFF)),
      builder: (context, _) => MmiShell(initialPage: initialPage),
    );
  }
}
