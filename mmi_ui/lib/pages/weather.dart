import 'package:flutter/widgets.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';

/// Weather where the car is: now (big temperature, sky, wind, today's high / low)
/// and the next days as a row of glass panels. From Open-Meteo, refreshed every
/// half hour while online; the last answer stays when offline.
class WeatherPage extends StatefulWidget {
  const WeatherPage({super.key});
  @override
  State<WeatherPage> createState() => _WeatherPageState();
}

IconData weatherIcon(int code) => switch (code) {
      0 => MibIcons.sunny,
      1 || 2 || 3 => MibIcons.partly_cloudy_day,
      45 || 48 => MibIcons.cloud,
      >= 51 && <= 67 || >= 80 && <= 82 => MibIcons.rainy,
      >= 71 && <= 77 || 85 || 86 => MibIcons.ac_unit,
      >= 95 => MibIcons.thunderstorm,
      _ => MibIcons.cloud,
    };

String weatherText(int code) => switch (code) {
      0 => 'Clear',
      1 => 'Mostly clear',
      2 => 'Partly cloudy',
      3 => 'Cloudy',
      45 || 48 => 'Fog',
      >= 51 && <= 57 => 'Drizzle',
      >= 61 && <= 67 || >= 80 && <= 82 => 'Rain',
      >= 71 && <= 77 || 85 || 86 => 'Snow',
      >= 95 => 'Thunderstorm',
      _ => '',
    };

class _WeatherPageState extends State<WeatherPage> with Live<WeatherPage> {
  @override
  String get liveMethod => 'weatherGet';
  @override
  Duration get liveEvery => const Duration(seconds: 20);

  @override
  Widget build(BuildContext context) {
    final w = live;
    if (w == null || w['temp'] == null) {
      return Center(
        child: Text(w?['message'] as String? ?? 'Loading the weather…', style: Mib.text(26, color: Mib.grey), textAlign: TextAlign.center),
      );
    }
    final code = (w['code'] as num).toInt();
    final days = ((w['days'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    return Padding(
      padding: const EdgeInsets.only(left: 8, top: 30),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        SizedBox(
          height: 250,
          child: Row(children: [
            Icon(weatherIcon(code), size: 180, color: Mib.white),
            const SizedBox(width: 40),
            Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${(w['temp'] as num).round()}°', style: Mib.text(110, height: 1.0)),
              Text(weatherText(code), style: Mib.text(32)),
            ]),
            const Spacer(),
            Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
              if (w['place'] != null) Text('${w['place']}', style: Mib.text(30)),
              const SizedBox(height: 10),
              Text('High ${(w['hi'] as num?)?.round() ?? '--'}°   Low ${(w['lo'] as num?)?.round() ?? '--'}°', style: Mib.text(25, color: const Color(0xFFDADADA))),
              const SizedBox(height: 6),
              Text('Wind ${(w['wind'] as num?)?.round() ?? '--'} km/h', style: Mib.text(25, color: const Color(0xFFDADADA))),
              const SizedBox(height: 6),
              Text('Updated ${w['updated'] ?? ''}', style: Mib.text(20, color: Mib.grey)),
            ]),
            const SizedBox(width: 12),
          ]),
        ),
        const SizedBox(height: 34),
        SizedBox(
          height: 190,
          child: Row(children: [
            for (var i = 0; i < days.length && i < 6; i++) ...[
              if (i > 0) const SizedBox(width: 16),
              Expanded(
                child: Container(
                  decoration: Mib.glass(),
                  child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                    Text('${days[i]['day']}', style: Mib.text(24)),
                    const SizedBox(height: 8),
                    Icon(weatherIcon((days[i]['code'] as num).toInt()), size: 62, color: Mib.white),
                    const SizedBox(height: 8),
                    Text('${(days[i]['hi'] as num).round()}°  ${(days[i]['lo'] as num).round()}°', style: Mib.text(24, color: const Color(0xFFDADADA))),
                  ]),
                ),
              ),
            ],
          ]),
        ),
      ]),
    );
  }
}
