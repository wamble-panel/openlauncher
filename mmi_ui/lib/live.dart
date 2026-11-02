import 'dart:async';
import 'package:flutter/widgets.dart';
import 'bridge.dart';

/// Keeps a page in step with the car: asks the host for [liveMethod] when the page
/// opens and again whenever the host says [liveTopic] changed (pushed, no polling).
/// A slow [liveEvery] check stays as a safety net. Rebuilds only on a real change.
mixin Live<T extends StatefulWidget> on State<T> {
  Timer? _timer;
  StreamSubscription<String>? _sub;
  Map<Object?, Object?>? live;

  String get liveMethod;
  /// Host topic that triggers a refresh ("media", "radio", "car", "settings", "bt", "calls").
  String? get liveTopic => null;
  Duration get liveEvery => const Duration(seconds: 5);

  @override
  void initState() {
    super.initState();
    refresh();
    _timer = Timer.periodic(liveEvery, (_) => refresh());
    final topic = liveTopic;
    if (topic != null) _sub = Bridge.changes.stream.where((t) => t == topic).listen((_) => refresh());
  }

  Future<void> refresh() async {
    final m = await Bridge.call<Map<Object?, Object?>>(liveMethod);
    if (m == null || !mounted) return;
    if (m.toString() != live.toString()) setState(() => live = m);
  }

  @override
  void dispose() {
    _timer?.cancel();
    _sub?.cancel();
    super.dispose();
  }
}
