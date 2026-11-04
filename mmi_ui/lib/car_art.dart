import 'package:flutter/foundation.dart';

/// The built-in car picture for this unit's car: 'opel', 'sunny' or 'none' (the host
/// decides from the car list or the driver's choice in Settings › Car). Set at start
/// from settingsGet and whenever the choice changes.
final carArt = ValueNotifier<String>('opel');

/// The start-up / Car information picture, or null for none.
String? bootCarAsset(String art) => switch (art) {
      'sunny' => 'assets/images/boot_car_sunny.webp',
      'none' => null,
      _ => 'assets/images/boot_car.webp',
    };

/// The Home CAR tile picture (a car is always shown there).
String carTileAsset(String art) => art == 'sunny' ? 'assets/images/home_art_car_sunny.webp' : 'assets/images/home_art_car.webp';
