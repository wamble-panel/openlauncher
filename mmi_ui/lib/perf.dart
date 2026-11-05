import 'dart:ui' show FrameTiming, FramePhase;
import 'package:flutter/scheduler.dart';

/// Measures smoothness on the unit itself over the last 10 seconds: frames drawn per
/// second while something moves, frame times (average, 50/95/99th percentile, worst),
/// the share of slow frames (over one 60 Hz frame, 16.7 ms) and whether the worst frame
/// was slow to build (Dart, the UI thread) or to draw (raster, the GPU side).
class FrameMonitor {
  FrameMonitor._();
  static final _frames = <(int, double, double, double)>[]; // (end µs, total, build, raster ms)
  static bool _started = false;

  /// Safe to call more than once (only one callback is ever registered).
  static void start() {
    if (_started) return;
    _started = true;
    SchedulerBinding.instance.addTimingsCallback(_onTimings);
  }

  static void _onTimings(List<FrameTiming> timings) {
    for (final t in timings) {
      _frames.add((
        t.timestampInMicroseconds(FramePhase.rasterFinish),
        t.totalSpan.inMicroseconds / 1000,
        t.buildDuration.inMicroseconds / 1000,
        t.rasterDuration.inMicroseconds / 1000,
      ));
    }
    final cutoff = (_frames.isEmpty ? 0 : _frames.last.$1) - 10000000;
    _frames.removeWhere((f) => f.$1 < cutoff);
  }

  static Map<String, num> stats() {
    if (_frames.length < 2) return {'frames': _frames.length};
    final totals = _frames.map((f) => f.$2).toList()..sort();
    double pct(double p) => totals[((totals.length - 1) * p).round()];
    final slow = totals.where((t) => t > 16.7).length;
    final avg = totals.reduce((a, b) => a + b) / totals.length;
    final worstFrame = _frames.reduce((a, b) => a.$2 >= b.$2 ? a : b);
    final seconds = (_frames.last.$1 - _frames.first.$1) / 1e6;
    return {
      'frames': _frames.length,
      'fps': seconds > 0 ? (_frames.length - 1) / seconds : 0,
      'slowPct': slow * 100 / _frames.length,
      'avgMs': avg,
      'p50Ms': pct(0.5),
      'p95Ms': pct(0.95),
      'p99Ms': pct(0.99),
      'worstMs': totals.last,
      'worstBuildMs': worstFrame.$3,
      'worstRasterMs': worstFrame.$4,
    };
  }
}
