import 'dart:async';
import 'package:flutter/widgets.dart';
import 'bridge.dart';

/// Whether MMI's screens are in front (set from the app lifecycle in main.dart).
final appInFront = ValueNotifier<bool>(true);

/// Follows the app lifecycle into [appInFront].
class AppFrontObserver with WidgetsBindingObserver {
  AppFrontObserver._();
  static final instance = AppFrontObserver._();
  static void start() => WidgetsBinding.instance.addObserver(instance);

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    appInFront.value = state == AppLifecycleState.resumed || state == AppLifecycleState.inactive;
  }
}

/// Keeps a page in step with the car: asks the host for [liveMethod] when the page
/// opens and again whenever the host says [liveTopic] changed (pushed, no polling).
/// A slow [liveEvery] check stays as a safety net. Rebuilds only on a real change.
///
/// While MMI is in the background (CarPlay, the map or another app in front), pages
/// don't ask for anything: on this chip that time belongs to the app in front. A page
/// that missed a change catches up once when MMI comes back.
mixin Live<T extends StatefulWidget> on State<T> {
  bool _missed = false;
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
    _timer = Timer.periodic(liveEvery, (_) => _maybeRefresh());
    final topic = liveTopic;
    if (topic != null) _sub = Bridge.changes.stream.where((t) => t == topic).listen((_) => _maybeRefresh());
    appInFront.addListener(_frontChanged);
  }

  void _maybeRefresh() {
    if (appInFront.value) {
      _run();
    } else {
      _missed = true;
    }
  }

  void _frontChanged() {
    if (appInFront.value && _missed) {
      _missed = false;
      _run();
    }
  }

  // One request at a time: a burst of changes while one is still being answered (the
  // unit can be slow) becomes a single follow-up request, never a pile of them.
  bool _busy = false;
  bool _again = false;
  Future<void> _run() async {
    if (_busy) {
      _again = true;
      return;
    }
    _busy = true;
    try {
      await refresh();
    } finally {
      _busy = false;
    }
    if (_again && mounted) {
      _again = false;
      _run();
    }
  }

  Future<void> refresh() async {
    final m = await Bridge.call<Map<Object?, Object?>>(liveMethod);
    if (m == null || !mounted) return;
    if (m.toString() != live.toString()) setState(() => live = m);
  }

  @override
  void dispose() {
    appInFront.removeListener(_frontChanged);
    _timer?.cancel();
    _sub?.cancel();
    super.dispose();
  }
}
