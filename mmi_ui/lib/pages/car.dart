import 'package:flutter/widgets.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';

/// Car · Car information: battery, coolant and oil life on the left, range,
/// consumption and outside temperature on the right, your car in the middle, tyres
/// and doors underneath. Values the CAN box does not send show "--".
class CarInfoPage extends StatefulWidget {
  const CarInfoPage({super.key});
  @override
  State<CarInfoPage> createState() => _CarInfoPageState();
}

class _CarInfoPageState extends State<CarInfoPage> with Live<CarInfoPage> {
  @override
  String get liveMethod => 'carGet';

  String? f(String key, String Function(num v) fmt) {
    final v = live?[key];
    return v is num ? fmt(v) : null;
  }

  @override
  Widget build(BuildContext context) {
    final tyres = ((live?['tyres'] as List?) ?? const []).cast<num>();
    final doors = live?['doors'] as String?;
    return Column(children: [
      const SizedBox(height: 34),
      Expanded(
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
            width: 236,
            child: Column(children: [
              _Info(MibIcons.battery_5_bar, 'Battery', f('voltage', (v) => '${v.toStringAsFixed(1)} V')),
              _Info(MibIcons.thermostat, 'Coolant', f('coolant', (v) => '${v.round()} °C')),
              _Info(MibIcons.oil_barrel, 'Oil life', f('oilLife', (v) => '${v.round()} %')),
            ]),
          ),
          Expanded(child: Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Image.asset('assets/images/boot_car.webp', fit: BoxFit.contain))),
          SizedBox(
            width: 250,
            child: Column(children: [
              _Info(MibIcons.local_gas_station, 'Range', f('range', (v) => '${v.round()} km')),
              _Info(MibIcons.speed, 'Avg consumption', f('consumption', (v) => '${v.toStringAsFixed(1)} L/100')),
              _Info(MibIcons.device_thermostat, 'Outside', f('outside', (v) => '${v.round()} °C')),
            ]),
          ),
        ]),
      ),
      Container(height: 1, decoration: const BoxDecoration(gradient: LinearGradient(colors: [Color(0x00FFFFFF), Color(0x55FFFFFF), Color(0x00FFFFFF)]))),
      SizedBox(
        height: 92,
        child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          for (var i = 0; i < 4; i++) ...[
            Text('${const ['FL', 'FR', 'RL', 'RR'][i]} ${tyres.length == 4 ? '${(tyres[i] / 100).toStringAsFixed(1)} bar' : '--'}', style: Mib.text(23, color: const Color(0xFFDADADA))),
            Text('   |   ', style: Mib.text(23, color: const Color(0x88FFFFFF))),
          ],
          const Icon(MibIcons.directions_car, size: 32, color: Color(0xFFDADADA)),
          const SizedBox(width: 12),
          Text('Doors: ${doors ?? 'all closed'}', style: Mib.text(23, color: const Color(0xFFDADADA))),
        ]),
      ),
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
