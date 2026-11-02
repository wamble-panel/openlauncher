// Renders every Flutter page at the car screen's size (1280 × 800, mdpi) into
// goldens/<page>.png, to compare with the designs:  flutter test --update-goldens
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mmi_ui/main.dart';
import 'package:mmi_ui/shell/shell.dart';

Future<void> _fonts() async {
  final roboto = FontLoader('Roboto')
    ..addFont(rootBundle.load('assets/fonts/Roboto-400.ttf'))
    ..addFont(rootBundle.load('assets/fonts/Roboto-500.ttf'));
  await roboto.load();
  await (FontLoader('MibSymbols')..addFont(rootBundle.load('assets/fonts/MibSymbols.ttf'))).load();
}

void main() {
  setUpAll(_fonts);
  for (final page in MmiPage.values) {
    testWidgets('render ${page.name}', (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MmiApp(initialPage: page.name));
      await tester.runAsync(() async {
        for (final e in find.byType(Image).evaluate()) {
          await precacheImage((e.widget as Image).image, e);
        }
      });
      await tester.pumpAndSettle();
      await expectLater(find.byType(MmiApp), matchesGoldenFile('../goldens/${page.name}.png'));
    });
  }
}
