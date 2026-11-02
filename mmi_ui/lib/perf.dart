import 'dart:ui' show FrameTiming, FramePhase;
import 'package:flutter/scheduler.dart';

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
