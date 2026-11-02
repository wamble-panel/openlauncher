import 'dart:ui' show FrameTiming, FramePhase;
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';
import 'bridge.dart';

/// How much MMI asks of the A133. Modes:
///   smooth   - full animations and glass effects
///   balanced - shorter animations, effects on
///   eco      - minimal animations, flat panels (least GPU and memory traffic)
///   auto     - smooth when the chip is cool, balanced when warm, eco when hot
class Perf {
  Perf._();
  static final changes = ValueNotifier<int>(0);

  static String mode = 'auto';
  /// 'full', 'reduced' or 'off' (overrides the mode's own choice when set).
  static String animations = 'mode';
  static bool effectsSetting = true;
  static bool thermalProtection = true;
  /// 0 cool, 1 warm, 2 hot (from the car side's temperature check).
  static int heat = 0;

  static String get effective {
    if (mode != 'auto') return mode;
    if (!thermalProtection) return 'smooth';
    return heat >= 2 ? 'eco' : heat == 1 ? 'balanced' : 'smooth';
  }

  /// Multiplier for every animation duration (0 = instant).
  static double get motion {
    switch (animations) {
      case 'full':
        return 1;
      case 'reduced':
        return 0.5;
      case 'off':
        return 0;
    }
    return switch (effective) { 'eco' => 0.35, 'balanced' => 0.7, _ => 1.0 };
  }

  /// Glass gradients, sheen and glow.
  static bool get effects => effectsSetting && effective != 'eco';

  static Duration d(int ms) => Duration(milliseconds: (ms * motion).round());

  static Future<void> load() async {
    final m = await Bridge.call<Map<Object?, Object?>>('perfGet');
    if (m == null) return;
    mode = m['mode'] as String? ?? 'auto';
    animations = m['animations'] as String? ?? 'mode';
    effectsSetting = m['effects'] != false;
    thermalProtection = m['thermal'] != false;
    heat = (m['heat'] as num?)?.toInt() ?? 0;
    changes.value++;
  }

  static void set(String key, Object value) {
    switch (key) {
      case 'mode':
        mode = value as String;
      case 'animations':
        animations = value as String;
      case 'effects':
        effectsSetting = value as bool;
      case 'thermal':
        thermalProtection = value as bool;
    }
    changes.value++;
    Bridge.call('perfSet', {'key': key, 'value': value});
  }

  static void setHeat(int level) {
    if (level == heat) return;
    heat = level;
    changes.value++;
  }
}

/// Measures smoothness on the unit itself: frames per second while something moves,
/// share of slow frames (over 16.7 ms) and the worst one, over the last 10 seconds.
class FrameMonitor {
  FrameMonitor._();
  static final _frames = <(int, double)>[]; // (end time µs, total ms)

  static void start() => SchedulerBinding.instance.addTimingsCallback(_onTimings);

  static void _onTimings(List<FrameTiming> timings) {
    for (final t in timings) {
      _frames.add((t.timestampInMicroseconds(FramePhase.rasterFinish), t.totalSpan.inMicroseconds / 1000));
    }
    final cutoff = (_frames.isEmpty ? 0 : _frames.last.$1) - 10000000;
    _frames.removeWhere((f) => f.$1 < cutoff);
  }

  static Map<String, num> stats() {
    if (_frames.length < 2) return {'frames': _frames.length};
    final slow = _frames.where((f) => f.$2 > 16.7).length;
    final worst = _frames.map((f) => f.$2).reduce((a, b) => a > b ? a : b);
    final avg = _frames.map((f) => f.$2).reduce((a, b) => a + b) / _frames.length;
    return {'frames': _frames.length, 'slowPct': slow * 100 / _frames.length, 'worstMs': worst, 'avgMs': avg};
  }
}
