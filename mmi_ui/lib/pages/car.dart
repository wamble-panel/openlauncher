import 'dart:io';
import 'package:flutter/widgets.dart' hide Text;
import '../car_art.dart';
import '../i18n.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';

/// Car · Car information: the car's values in two columns around its picture, speed
/// and rpm under it, tyres and doors underneath. Works on any car: only values this
/// car actually reports are shown (no rows of "--"), the car's name comes from the
/// unit's car list, and the picture is your own photo (MMI/car.png) or the Astra J.
class CarInfoPage extends StatefulWidget {
  const CarInfoPage({super.key});
  @override
  State<CarInfoPage> createState() => _CarInfoPageState();
}

class _CarInfoPageState extends State<CarInfoPage> with Live<CarInfoPage> {
  @override
  String get liveMethod => 'carGet';
  @override
  String? get liveTopic => 'car';

  // Speed and rpm stream from the car only while this page is open.
  @override
  void initState() {
    super.initState();
    Bridge.call('carStream', {'on': true});
  }

  @override
  void dispose() {
    Bridge.call('carStream', {'on': false});
    super.dispose();
  }

  String? f(String key, String Function(num v) fmt) {
    final v = live?[key];
    return v is num ? fmt(v) : null;
  }

  @override
  Widget build(BuildContext context) {
    final tyres = ((live?['tyres'] as List?) ?? const []).cast<num>();
    final tyresText = ((live?['tyresText'] as List?) ?? const []).cast<String>();
    final hasTyres = tyres.length == 4 || tyresText.length == 4;
    final doors = live?['doors'] as String?;
    final doorsKnown = live?['doorsKnown'] != false;
    final name = live?['carName'] as String?;
    final own = live?['carPicture'] as String?;
    // Only what this car reports; the first three on the left, the rest on the right.
    final items = [
      (MibIcons.battery_5_bar, 'Battery', f('voltage', (v) => '${v.toStringAsFixed(1)} V')),
      (MibIcons.thermostat, 'Coolant', f('coolant', (v) => '${v.round()} °C')),
      (MibIcons.oil_barrel, 'Oil life', f('oilLife', (v) => '${v.round()} %')),
      (MibIcons.local_gas_station, 'Range', f('range', (v) => '${v.round()} km')),
      (MibIcons.speed, 'Avg consumption', f('consumption', (v) => '${v.toStringAsFixed(1)} L/100')),
      (MibIcons.device_thermostat, 'Outside', f('outside', (v) => '${v.round()} °C')),
    ].where((i) => i.$3 != null).toList();
    // With fewer values, split them evenly so the page stays balanced.
    final half = items.length > 3 ? 3 : (items.length + 1) ~/ 2;
    final left = items.take(half).toList();
    final right = items.skip(half).take(3).toList();
    final Widget? picture = own != null && File(own).existsSync()
        ? Image.file(File(own), fit: BoxFit.contain, cacheWidth: 600)
        : switch (bootCarAsset(live?['carArt'] as String? ?? (live?['carDefaultPicture'] == false ? 'none' : carArt.value))) {
            null => null,
            final a => Image.asset(a, fit: BoxFit.contain, cacheWidth: 600),
          };
    Widget column(List<(IconData, String, String?)> list) =>
        SizedBox(width: 250, child: Column(children: [for (final i in list) _Info(i.$1, i.$2, i.$3)]));
    return Column(children: [
      const SizedBox(height: 22),
      if (name != null) Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(name, style: Mib.text(24, color: const Color(0xFFDADADA)))),
      Expanded(
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (left.isNotEmpty) column(left),
          Expanded(
            child: Column(children: [
              if (picture != null) Expanded(child: Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: picture)),
              // No picture: speed and rpm larger, in the middle.
              if (picture == null) const Spacer(),
              Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                _Big(f('speed', (v) => '${v.round()}') ?? '--', 'km/h', size: picture == null ? 76 : 44),
                if (live?['rpm'] is num) ...[SizedBox(width: picture == null ? 90 : 60), _Big(f('rpm', (v) => '${v.round()}')!, 'rpm', size: picture == null ? 76 : 44)],
              ]),
              if (live?['odometer'] is num)
                Padding(padding: const EdgeInsets.only(top: 6), child: Text('Odometer ${(live!['odometer'] as num).round()} km', style: Mib.text(21, color: Mib.grey))),
              if (live?['handbrake'] == true) Text('Handbrake on', style: Mib.text(21, color: const Color(0xFFE5484D))),
              if (picture == null) const Spacer(),
            ]),
          ),
          if (right.isNotEmpty) column(right),
        ]),
      ),
      if (hasTyres || doorsKnown) ...[
        Container(height: 1, decoration: const BoxDecoration(gradient: LinearGradient(colors: [Color(0x00FFFFFF), Color(0x55FFFFFF), Color(0x00FFFFFF)]))),
        SizedBox(
          height: 92,
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            if (hasTyres)
              for (var i = 0; i < 4; i++) ...[
                Text('${const ['FL', 'FR', 'RL', 'RR'][i]} ${tyres.length == 4 ? '${(tyres[i] / 100).toStringAsFixed(1)} bar' : tyresText[i]}', style: Mib.text(23, color: const Color(0xFFDADADA))),
                if (i < 3 || doorsKnown) Text('   |   ', style: Mib.text(23, color: const Color(0x88FFFFFF))),
              ],
            if (doorsKnown) ...[
              const Icon(MibIcons.directions_car, size: 32, color: Color(0xFFDADADA)),
              const SizedBox(width: 12),
              Text('Doors: ${doors ?? 'all closed'}', style: Mib.text(23, color: const Color(0xFFDADADA))),
            ],
          ]),
        ),
      ],
    ]);
  }
}

class _Info extends StatelessWidget {
  const _Info(this.icon, this.label, this.value);
  final IconData icon;
  final String label;
  final String? value;
  @override
  Widget build(BuildContext context) => Container(
        height: 99,
        margin: const EdgeInsets.only(bottom: 22),
        padding: const EdgeInsets.symmetric(horizontal: 18),
        decoration: Mib.glass(radius: 2),
        child: Row(children: [
          Icon(icon, size: 44, color: Mib.white),
          const SizedBox(width: 18),
          Expanded(
            child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label, style: Mib.text(19, color: const Color(0xFFDADADA)), maxLines: 1),
              Text(value ?? '--', style: Mib.text(27), maxLines: 1, overflow: TextOverflow.ellipsis),
            ]),
          ),
        ]),
      );
}

class _Big extends StatelessWidget {
  const _Big(this.value, this.unit, {this.size = 44});
  final String value, unit;
  final double size;
  @override
  Widget build(BuildContext context) => Column(children: [
        Text(value, style: Mib.text(size, height: 1.0)),
        Text(unit, style: Mib.text(19, color: Mib.grey)),
      ]);
}
