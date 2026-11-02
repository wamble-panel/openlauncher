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
  'healthGet': {
    'temps': [{'name': 'cpu_thermal_zone', 'c': 62.0}, {'name': 'gpu_thermal_zone', 'c': 58.0}, {'name': 'ddr_thermal_zone', 'c': 55.0}],
    'cores': [{'cur': 1416, 'max': 1608}, {'cur': 1416, 'max': 1608}, {'cur': 1008, 'max': 1608}, {'cur': 1008, 'max': 1608}],
    'gpuMhz': 504, 'ramTotalMb': 1964, 'ramFreeMb': 812, 'mmiMb': 186, 'uptimeMin': 42,
  },
  'soundGet': {
    'gains': [1.0, 1.0, -1.5, -2.0, -0.5, 0.0, 1.5, 1.5, 1.0, 0.5],
    'effective': [3.4, 3.0, -0.3, -1.6, -0.5, 0.0, 1.5, 1.9, 2.0, 1.7],
    'centres': [31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000],
    'preset': 'Natural', 'loudness': true, 'enabled': true, 'engine': 'dynamics',
    'presets': [
      {'name': 'Flat', 'about': 'Nothing changed: the recording as it is.'},
      {'name': 'Natural', 'about': 'Made for a car cabin: tames the boom cars add around 125–250 Hz and lifts voice clarity a little.'},
      {'name': 'Bass', 'about': 'Deeper, fuller low end without muddying voices.'},
      {'name': 'Rock', 'about': 'Punchy drums and bass, forward guitars, crisp cymbals.'},
      {'name': 'Voice', 'about': 'News, podcasts and calls: less rumble, clearer speech over road noise.'},
    ],
  },
  'vendorGet': {
    'apps': [
      {'pkg': 'com.dofun.variety', 'name': 'Vendor home screen', 'status': 'safe', 'note': 'MMI is the home screen', 'enabled': true},
      {'pkg': 'com.tw.radio', 'name': 'Radio', 'status': 'test', 'note': 'MMI is the whole radio; check tuning, names and car buttons first', 'enabled': true},
      {'pkg': 'com.tw.bt', 'name': 'Bluetooth', 'status': 'keep', 'note': 'Calls, phonebook and Bluetooth music', 'enabled': true},
      {'pkg': 'com.tw.video', 'name': 'Video', 'status': 'safe', 'note': 'Use any video app from Apps', 'enabled': false},
    ],
  },
  'ampGet': {'balance': 9, 'fader': 9, 'chip': 'other'},
  'carGet': {'speed': 0, 'rpm': 820, 'voltage': 14.1, 'coolant': 88, 'oilLife': 72, 'range': 412, 'consumption': 7.4, 'outside': 27, 'tyres': [230, 230, 220, 220]},
  'weatherGet': {
    'place': 'Nasr City', 'temp': 27, 'code': 1, 'wind': 14, 'hi': 31, 'lo': 19, 'updated': '12:30',
    'days': [
      {'day': 'Tue', 'code': 0, 'hi': 31, 'lo': 19}, {'day': 'Wed', 'code': 2, 'hi': 30, 'lo': 18}, {'day': 'Thu', 'code': 3, 'hi': 28, 'lo': 18},
      {'day': 'Fri', 'code': 61, 'hi': 25, 'lo': 17}, {'day': 'Sat', 'code': 0, 'hi': 29, 'lo': 18}, {'day': 'Sun', 'code': 1, 'hi': 30, 'lo': 19},
    ],
  },
  'eqGet': {'levels': [200, 200, 200, 200, 200], 'min': -1500, 'max': 1500, 'preset': 'Custom', 'speedVolume': 2},
  'settingsGet': {'brightness': 0.55, 'dayNight': 0, 'touchSounds': true, 'screenOff': false, 'clock24': true, 'canWrite': true, 'metric': true, 'cameraAlerts': true, 'version': '2.0.0'},
  'radioGet': {
    'am': false, 'freq': 104.2, 'name': 'Nile FM', 'genre': 'Pop', 'stereo': true, 'text': 'Now: Morning Show with Ahmed',
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
