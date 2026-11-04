import 'package:flutter/widgets.dart' hide Text;
import '../bridge.dart';
import '../i18n.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../unit.dart';
import '../widgets/mib_widgets.dart';

/// Settings › System › This unit: what MMI found out by itself about this head unit
/// and, per feature, whether MMI does it, the unit's own app does it, or it isn't there.
class UnitPage extends StatefulWidget {
  const UnitPage({super.key});
  @override
  State<UnitPage> createState() => _UnitPageState();
}

class _UnitPageState extends State<UnitPage> {
  @override
  void initState() {
    super.initState();
    unit.addListener(_changed);
    loadUnit();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    unit.removeListener(_changed);
    super.dispose();
  }

  static const _rows = [
    ('radio', 'Radio', MibIcons.radio),
    ('phone', 'Bluetooth phone', MibIcons.call),
    ('carinfo', 'Car data', MibIcons.directions_car),
    ('camera', 'Reverse camera', MibIcons.videocam),
    ('keys', 'Steering-wheel buttons', MibIcons.tune),
    ('climate', 'Climate', MibIcons.mode_fan),
    ('phonelink', 'CarPlay / Android Auto', MibIcons.mobile_share),
    ('sound', 'Sound and equaliser', MibIcons.equalizer),
    ('music', 'Music', MibIcons.music_note),
    ('nav', 'Navigation', MibIcons.near_me),
  ];

  static String _mode(String m) => switch (m) {
        'mmi' => 'In MMI',
        'app' => "The unit's app",
        'system' => 'By the unit',
        _ => 'Not on this unit',
      };

  @override
  Widget build(BuildContext context) {
    final u = unit.value;
    final chip = u['chip'] as String?;
    return ListView(padding: const EdgeInsets.only(bottom: 24), children: [
      MibRow(
        label: 'Unit',
        value: (u['platformLabel'] as String?) ?? 'Checking…',
        sub: [?chip, if (u['android'] != null) 'Android ${u['android']}', if (u['build'] != null) '${u['build']}'].join(' · '),
      ),
      for (final r in _rows)
        MibRow(
          icon: r.$3,
          label: r.$2,
          sub: featurePkg(r.$1),
          trailing: _Status(featureMode(r.$1)),
          onTap: featureMode(r.$1) == 'app' && featurePkg(r.$1) != null ? () => Bridge.call('launch', {'key': 'pkg:${featurePkg(r.$1)}'}) : null,
        ),
      Padding(
        padding: const EdgeInsets.fromLTRB(10, 18, 10, 0),
        child: Text('MMI checks this by itself every time it starts. Jobs the unit does with its own app open that app; jobs the unit does not have are hidden.',
            style: Mib.text(20, color: Mib.grey)),
      ),
    ]);
  }
}

class _Status extends StatelessWidget {
  const _Status(this.mode);
  final String mode;
  @override
  Widget build(BuildContext context) {
    final color = switch (mode) {
      'mmi' => const Color(0xFF5BD35B),
      'app' || 'system' => Mib.white,
      _ => Mib.grey,
    };
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Text(_UnitPageState._mode(mode), style: Mib.text(24, color: color)),
      if (mode == 'app') ...[const SizedBox(width: 10), const Icon(MibIcons.open_in_new, size: 28, color: Mib.white)],
    ]);
  }
}
