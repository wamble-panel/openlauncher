import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';

/// Media · USB music: songs, albums, artists and folders on the USB stick / SD card.
/// Tap an album, artist or folder to open it; tap a song to play from there on
/// (the rest of the list follows, gapless).
class UsbPage extends StatefulWidget {
  const UsbPage({super.key, required this.onPlaying});
  final VoidCallback onPlaying;
  @override
  State<UsbPage> createState() => _UsbPageState();
}

class _UsbPageState extends State<UsbPage> {
  static const _modes = ['songs', 'albums', 'artists', 'folders'];
  String mode = 'songs';
  String? filter, filterTitle;
  Map<Object?, Object?>? data;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final m = await Bridge.call<Map<Object?, Object?>>('usbGet', {'mode': mode, 'filter': filter});
    if (mounted) setState(() => data = m);
  }

  @override
  Widget build(BuildContext context) {
    final items = ((data?['items'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    final count = (data?['count'] as num?)?.toInt() ?? 0;
    return Padding(
      padding: const EdgeInsets.only(left: 8, top: 16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        MibSegmented(
          options: const ['Songs', 'Albums', 'Artists', 'Folders'],
          selected: _modes.indexOf(mode),
          onSelect: (i) {
            setState(() {
              mode = _modes[i];
              filter = null;
              filterTitle = null;
            });
            _load();
          },
        ),
        if (filter != null)
          Pressable(
            onTap: () {
              setState(() {
                filter = null;
                filterTitle = null;
              });
              _load();
            },
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 6),
              child: Row(children: [
                const Icon(MibIcons.chevron_left, size: 32, color: Mib.white),
                const SizedBox(width: 8),
                Expanded(child: Text(filterTitle ?? '', style: Mib.text(26), maxLines: 1, overflow: TextOverflow.ellipsis)),
              ]),
            ),
          ),
        Expanded(
          child: data != null && count == 0
              ? Center(
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    Text(data?['scanning'] == true ? 'Looking for music…' : 'No music found on USB or SD card.', style: Mib.text(26, color: Mib.grey)),
                    const SizedBox(height: 24),
                    MibButton(label: 'Look again', icon: MibIcons.refresh, width: 240, onTap: () async {
                      await Bridge.call('usbRescan');
                      await Future<void>.delayed(const Duration(seconds: 2));
                      _load();
                    }),
                  ]),
                )
              : ListView.builder(
                  padding: const EdgeInsets.only(top: 6, bottom: 24),
                  itemCount: items.length,
                  itemExtent: 76,
                  itemBuilder: (context, i) {
                    final it = items[i];
                    final song = it['song'] == true;
                    return MibRow(
                      label: '${it['title']}',
                      sub: '${it['sub'] ?? ''}',
                      height: 76,
                      icon: song ? MibIcons.music_note : (mode == 'albums' ? MibIcons.album : mode == 'artists' ? MibIcons.person : MibIcons.folder),
                      onTap: () async {
                        if (song) {
                          await Bridge.call('usbPlay', {'mode': mode, 'filter': filter, 'id': it['key']});
                          widget.onPlaying();
                        } else {
                          setState(() {
                            filter = '${it['key']}';
                            filterTitle = '${it['title']}';
                          });
                          _load();
                        }
                      },
                    );
                  },
                ),
        ),
      ]),
    );
  }
}
