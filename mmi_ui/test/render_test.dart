// Renders every Flutter page at the car screen's size (1280 × 800, mdpi) into
// goldens/<page>.png with sample data, to compare with the designs:
//   flutter test --update-goldens
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mmi_ui/bridge.dart';
import 'package:mmi_ui/main.dart';
import 'package:mmi_ui/shell/shell.dart';

Future<void> _fonts() async {
  final roboto = FontLoader('Roboto')
    ..addFont(rootBundle.load('assets/fonts/Roboto-400.ttf'))
    ..addFont(rootBundle.load('assets/fonts/Roboto-500.ttf'));
  await roboto.load();
  await (FontLoader('MibSymbols')..addFont(rootBundle.load('assets/fonts/MibSymbols.ttf'))).load();
}

final _demo = <String, Object?>{
  'eqGet': {'levels': [200, 200, 200, 200, 200], 'min': -1500, 'max': 1500, 'preset': 'Custom', 'speedVolume': 2},
  'settingsGet': {'brightness': 0.55, 'dayNight': 0, 'touchSounds': true, 'screenOff': false, 'clock24': true, 'canWrite': true, 'metric': true, 'cameraAlerts': true, 'version': '2.0.0'},
  'radioGet': {
    'am': false, 'freq': 104.2, 'name': 'Nile FM', 'genre': 'Pop',
    'presets': [
      {'freq': 104.2, 'name': 'Nile FM'}, {'freq': 95.0, 'name': 'Mega FM'}, {'freq': 92.7, 'name': 'Radio Hits'},
      {'freq': 98.8, 'name': 'Nogoum'}, {'freq': 100.6, 'name': 'Shaabi FM'},
    ],
  },
  'appsGet': {'found': ['browser', 'files', 'music', 'video', 'camera', 'calculator', 'calendar', 'clock', 'gallery', 'maps', 'weather', 'settings', 'store', 'notes', 'recorder'], 'others': []},
  'callsGet': {
    'allowed': true,
    'calls': [
      {'name': 'Ahmed', 'number': '01001234567', 'type': 'in', 'when': '10:42'},
      {'name': 'Mum', 'number': '0100', 'type': 'out', 'when': 'Yesterday'},
      {'name': 'Unknown', 'number': '0122', 'type': 'missed', 'when': 'Yesterday'},
      {'name': 'Omar', 'number': '0111', 'type': 'in', 'when': 'Mon'},
      {'name': 'Work', 'number': '0222', 'type': 'out', 'when': 'Sun'},
      {'name': 'Sara', 'number': '0155', 'type': 'missed', 'when': 'Sat'},
      {'name': 'Garage', 'number': '0233', 'type': 'in', 'when': 'Fri'},
    ],
  },
  'mediaGet': {'title': 'Blinding Lights', 'artist': 'The Weeknd', 'album': 'After Hours', 'playing': true, 'pos': 80000, 'dur': 202000, 'source': 'My iPhone'},
  'sourcesGet': {'sources': [{'key': 'bt', 'label': 'Bluetooth audio · My iPhone', 'kind': 'bt', 'playing': true}, {'key': 'usb', 'label': 'USB', 'kind': 'usb'}]},
  'btGet': {
    'scanning': true, 'name': 'MMI 1280',
    'devices': [
      {'name': 'Galaxy S24', 'address': '1'}, {'name': 'iPhone 15', 'address': '2'},
      {'name': 'Pixel 8', 'address': '3'}, {'name': 'OnePlus 12', 'address': '4'},
    ],
  },
};

Future<void> _render(WidgetTester tester, Widget app, String name) async {
  tester.view.physicalSize = const Size(1280, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(app);
  await tester.runAsync(() async {
    for (final e in find.byType(Image).evaluate()) {
      await precacheImage((e.widget as Image).image, e);
    }
  });
  for (var i = 0; i < 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
  await expectLater(find.byType(MmiApp), matchesGoldenFile('../goldens/$name.png'));
  await tester.pumpWidget(const SizedBox());
}

void main() {
  setUpAll(() async {
    await _fonts();
    Bridge.demo = _demo;
  });
  for (final page in MmiPage.values) {
    testWidgets('render ${page.name}', (tester) => _render(tester, MmiApp(initialPage: page.name), page.name));
  }
  testWidgets('render boot', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(const MmiApp(splash: true));
    await tester.runAsync(() async {
      for (final e in find.byType(Image).evaluate()) {
        await precacheImage((e.widget as Image).image, e);
      }
    });
    await tester.pump(const Duration(milliseconds: 1400));
    await expectLater(find.byType(MmiApp), matchesGoldenFile('../goldens/boot.png'));
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('render call', (tester) => _render(
      tester,
      MmiApp(initialCall: {'state': 'active', 'name': 'Ahmed', 'number': '0100 123 4567', 'since': DateTime.now().millisecondsSinceEpoch - 151000}),
      'call'));
}
