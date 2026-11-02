import 'package:flutter/services.dart';

/// Talks to MMI's Kotlin side (car buttons, radio, Bluetooth, media, settings).
/// Every call is safe without the host (tests, previews): it just does nothing.
class Bridge {
  Bridge._();
  static const _channel = MethodChannel('mmi');

  static Future<T?> call<T>(String method, [Map<String, Object?>? args]) async {
    try {
      return await _channel.invokeMethod<T>(method, args);
    } catch (_) {
      return null;
    }
  }

  /// Glass tap sound for touches; detent tick for knob steps; chime when a page opens.
  static void tap() => call('sound', {'kind': 'tap'});
  static void tick() => call('sound', {'kind': 'tick'});
  static void chime() => call('sound', {'kind': 'open'});
}
