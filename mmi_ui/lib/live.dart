import 'dart:async';
import 'package:flutter/widgets.dart';
import 'bridge.dart';

/// Keeps a page in step with the car: asks the host for [method] now and every
/// [every] while the page is open, and rebuilds when the answer changes.
mixin Live<T extends StatefulWidget> on State<T> {
  Timer? _timer;
  Map<Object?, Object?>? live;

  String get liveMethod;
  Duration get liveEvery => const Duration(seconds: 1);

  @override
  void initState() {
    super.initState();
    refresh();
    _timer = Timer.periodic(liveEvery, (_) => refresh());
  }

  Future<void> refresh() async {
    final m = await Bridge.call<Map<Object?, Object?>>(liveMethod);
    if (m == null || !mounted) return;
    if (m.toString() != live.toString()) setState(() => live = m);
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
}
