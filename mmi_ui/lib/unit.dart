import 'package:flutter/foundation.dart';
import 'bridge.dart';

/// What this head unit is and what MMI does on it (host: UnitProfile), found by
/// itself at start. Per feature: 'mmi' (MMI's own page), 'app' (the unit's own app,
/// opened by MMI), 'system' (the unit does it by itself) or 'none' (hidden).
/// Until the host answers, everything counts as MMI's own (the owner's TW unit).
final unit = ValueNotifier<Map<Object?, Object?>>(const {});

Future<void> loadUnit() async {
  final m = await Bridge.call<Map<Object?, Object?>>('unitGet');
  if (m != null) unit.value = m;
}

Map<Object?, Object?>? _feature(String key) => (unit.value['features'] as Map?)?[key] as Map?;

String featureMode(String key) => _feature(key)?['mode'] as String? ?? 'mmi';
String? featurePkg(String key) => _feature(key)?['pkg'] as String?;

/// The feature behind a home tile / rail cell / page key.
String? featureFor(String key) => switch (key) {
      'radio' => 'radio',
      'phone' || 'calls' || 'dial' => 'phone',
      'car' || 'carinfo' => 'carinfo',
      'phonelink' => 'phonelink',
      'climate' => 'climate',
      _ => null,
    };

/// Whether a tile for [key] is shown: hidden only when the unit cannot do it at all.
bool keyShown(String key) {
  final f = featureFor(key);
  if (f == null) return true;
  // The Car tile stays: Car information shows whatever the unit reports.
  if (f == 'carinfo') return true;
  return featureMode(f) != 'none';
}
