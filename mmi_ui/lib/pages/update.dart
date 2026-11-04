import 'package:flutter/widgets.dart' hide Text;
import '../i18n.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';

/// Settings · Software update: the version running, whether a newer one exists, one
/// button that downloads (with progress) and installs, and the unit's free storage.
/// New builds also download by themselves in the background when online.
class UpdatePage extends StatefulWidget {
  const UpdatePage({super.key});
  @override
  State<UpdatePage> createState() => _UpdatePageState();
}

class _UpdatePageState extends State<UpdatePage> with Live<UpdatePage> {
  @override
  String get liveMethod => 'updateGet';
  @override
  Duration get liveEvery => const Duration(milliseconds: 700);

  @override
  Widget build(BuildContext context) {
    final installed = (live?['installed'] as num?)?.toInt();
    final latest = (live?['latest'] as num?)?.toInt();
    final ready = (live?['ready'] as num?)?.toInt();
    final busy = live?['busy'] == true;
    final progress = (live?['progress'] as num?)?.toInt();
    final status = live?['status'] as String?;
    final failure = live?['failure'] as String?;
    final newer = latest != null && installed != null && latest > installed;
    final headline = status ??
        (failure != null
            ? "The last update didn't install."
            : ready != null
                ? 'Version $ready is downloaded and ready.'
                : newer
                    ? 'Version $latest is available.'
                    : latest == null
                        ? (live?['checked'] == true ? 'Checking for updates…' : 'Not checked yet.')
                        : 'MMI is up to date.');
    final action = failure != null
        ? 'Try again'
        : ready != null
            ? 'Install now'
            : newer
                ? 'Download and install'
                : 'Check again';
    final free = (live?['freeGb'] as num?)?.toDouble();
    final total = (live?['totalGb'] as num?)?.toDouble();
    return ListView(padding: const EdgeInsets.only(top: 18, bottom: 24), children: [
      Container(
        margin: const EdgeInsets.symmetric(horizontal: 8),
        padding: const EdgeInsets.all(24),
        decoration: Mib.glass(),
        child: Row(children: [
          const Icon(MibIcons.sync, size: 72, color: Mib.white),
          const SizedBox(width: 26),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('MMI ${live?['versionName'] ?? ''}', style: Mib.text(30)),
              Text(installed == null ? '' : 'Build $installed', style: Mib.text(21, color: Mib.grey)),
              const SizedBox(height: 14),
              Text(headline, style: Mib.text(25)),
              if (busy && progress != null) ...[
                const SizedBox(height: 12),
                ClipRRect(
                  borderRadius: BorderRadius.circular(2),
                  child: SizedBox(
                    height: 6,
                    child: Stack(children: [
                      const Positioned.fill(child: ColoredBox(color: Color(0x33FFFFFF))),
                      Positioned.fill(
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: FractionallySizedBox(widthFactor: (progress / 100).clamp(0, 1), heightFactor: 1, child: const ColoredBox(color: Mib.white)),
                        ),
                      ),
                    ]),
                  ),
                ),
              ],
            ]),
          ),
          const SizedBox(width: 20),
          Opacity(
            opacity: busy ? 0.4 : 1,
            child: MibButton(
              label: busy ? 'Working…' : action,
              icon: MibIcons.sync,
              width: 300,
              onTap: () {
                if (busy) return;
                Bridge.call(ready != null || newer || failure != null ? 'updateRun' : 'updateCheck');
                refresh();
              },
            ),
          ),
        ]),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(10, 12, 10, 20),
        child: Text('New builds download by themselves when the unit is online; installing takes one tap here.', style: Mib.text(20, color: Mib.grey)),
      ),
      MibRow(
        label: 'Unit storage',
        icon: MibIcons.storage,
        value: free == null || total == null ? '--' : '${free.toStringAsFixed(1)} GB free of ${total.toStringAsFixed(1)} GB',
      ),
      MibRow(
        label: 'Version',
        icon: MibIcons.info,
        value: latest == null ? '${installed ?? '--'}' : '${installed ?? '--'} (newest $latest)',
      ),
    ]);
  }
}
