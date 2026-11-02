import 'package:flutter/services.dart';

/// Talks to MMI's Kotlin side (car buttons, radio, Bluetooth, media, settings).
/// Every call is safe without the host (tests, previews): it just does nothing.
class Bridge {
  Bridge._();
  static const _channel = MethodChannel('mmi');

  /// Sample answers for the render tests (no car attached), keyed by method.
  static Map<String, Object?>? demo;

  static Future<T?> call<T>(String method, [Map<String, Object?>? args]) async {
    if (demo != null) return demo![method] as T?;
    try {
      return await _channel.invokeMethod<T>(method, args);
    } catch (_) {
      return null;
    }
  }

  /// Car buttons and other pushes from the host (knob turns, page keys).
  static void listen(Future<dynamic> Function(MethodCall call) handler) => _channel.setMethodCallHandler(handler);

  /// Glass tap sound for touches; detent tick for knob steps; chime when a page opens.
  static void tap() => call('sound', {'kind': 'tap'});
  static void tick() => call('sound', {'kind': 'tick'});
  static void chime() => call('sound', {'kind': 'open'});
}
