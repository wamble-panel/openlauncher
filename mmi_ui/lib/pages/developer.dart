import 'package:flutter/widgets.dart' hide Text;
import '../i18n.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/mib_widgets.dart';
import '../widgets/pressable.dart';
import 'settings.dart';

/// Settings · Developer: everything a normal driver never needs (vendor car list, unit
/// health, vendor apps, system tuning, graphics switch, reports), behind the owner's own
/// PIN. Unlocked for 10 minutes, or until MMI restarts.
class DeveloperPage extends StatefulWidget {
  const DeveloperPage({super.key, required this.onOpen});
  final ValueChanged<String> onOpen;
  static DateTime? _unlockedUntil;
  static bool get unlocked => _unlockedUntil != null && DateTime.now().isBefore(_unlockedUntil!);
  @override
  State<DeveloperPage> createState() => _DeveloperPageState();
}

class _DeveloperPageState extends State<DeveloperPage> {
  @override
  Widget build(BuildContext context) {
    if (!DeveloperPage.unlocked) {
      return PinPad(onUnlocked: () => setState(() => DeveloperPage._unlockedUntil = DateTime.now().add(const Duration(minutes: 10))));
    }
    return _DevList(onOpen: widget.onOpen);
  }
}

class _DevList extends StatefulWidget {
  const _DevList({required this.onOpen});
  final ValueChanged<String> onOpen;
  @override
  State<_DevList> createState() => _DevListState();
}

class _DevListState extends State<_DevList> with Live<_DevList>, SettingsState<_DevList> {
  @override
  Widget build(BuildContext context) {
    Widget open(String label, String key, IconData icon, String sub) => MibRow(label: label, sub: sub, icon: icon, onTap: () => widget.onOpen(key));
    return SettingsList(children: [
      open('CAN box car list', 'carlist', MibIcons.directions_car, 'every car the unit supports, and the one it is set to'),
      MibRow(
        label: 'Factory settings (vendor)',
        sub: 'select model, steering wheel, doors: asks for the vendor password',
        icon: MibIcons.settings,
        onTap: () => Bridge.call('system', {'what': 'vendorSettings'}),
      ),
      const MibCaption('Car data decoding', top: 18),
      MibSegmented(
        options: const ['Automatic', 'Opel / GM', 'Car service only'],
        selected: const ['AUTO', 'GM', 'CAR_SERVICE_ONLY'].indexOf('${v('carDecoder', 'AUTO')}').clamp(0, 2),
        onSelect: (i) => set('carDecoder', const ['AUTO', 'GM', 'CAR_SERVICE_ONLY'][i]),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(10, 8, 10, 14),
        child: Text(
          "Automatic reads the car from the unit's car list. MMI's own Opel / GM decoding (doors, tyres, range, oil life) is "
          "used only on those cars; every other car gets its data from the unit's car service.",
          style: Mib.text(19, color: Mib.grey),
        ),
      ),
      open('Unit health', 'health', MibIcons.device_thermostat, 'chip temperatures, speed, memory, frame times'),
      open('Vendor apps', 'vendor', MibIcons.apps, 'which ones MMI replaces; disable them one by one'),
      open('System tuning', 'tuning', MibIcons.build, 'root speed-up, firmware backup'),
      MibRow(label: 'OpenGL ES graphics', sub: 'try if pages stutter; used from the next start', trailing: MibToggle(value: b('gles'), onChanged: (x) => set('gles', x))),
      MibRow(label: 'Classic MMI', sub: 'switch back to the previous screens', trailing: MibToggle(value: false, onChanged: (_) => Bridge.call('classic'))),
      const DevSend(),
      MibRow(label: 'Lock developer page', icon: MibIcons.lock, onTap: () {
        DeveloperPage._unlockedUntil = null;
        widget.onOpen('system');
      }),
    ]);
  }
}

/// PIN pad: choose a PIN the first time (twice), then enter it to open the page.
class PinPad extends StatefulWidget {
  const PinPad({super.key, required this.onUnlocked});
  final VoidCallback onUnlocked;
  @override
  State<PinPad> createState() => _PinPadState();
}

class _PinPadState extends State<PinPad> {
  bool? _hasPin;
  String _pin = '';
  String? _first;
  String? _error;

  @override
  void initState() {
    super.initState();
    Bridge.call<bool>('devPin', {'action': 'state'}).then((v) {
      if (mounted) setState(() => _hasPin = v ?? false);
    });
  }

  Future<void> _ok() async {
    if (_pin.length < 4) {
      setState(() => _error = 'At least 4 digits.');
      return;
    }
    if (_hasPin == true) {
      final ok = await Bridge.call<bool>('devPin', {'action': 'check', 'pin': _pin}) ?? false;
      if (!mounted) return;
      if (ok) {
        Bridge.chime();
        widget.onUnlocked();
      } else {
        setState(() {
          _pin = '';
          _error = 'Wrong PIN.';
        });
      }
    } else if (_first == null) {
      setState(() {
        _first = _pin;
        _pin = '';
        _error = null;
      });
    } else if (_first == _pin) {
      await Bridge.call('devPin', {'action': 'set', 'pin': _pin});
      Bridge.chime();
      widget.onUnlocked();
    } else {
      setState(() {
        _first = null;
        _pin = '';
        _error = "The two PINs didn't match. Start again.";
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final title = _hasPin == null
        ? ''
        : _hasPin!
            ? 'Enter the developer PIN'
            : _first == null
                ? 'Choose a developer PIN (4 to 8 digits)'
                : 'Enter the same PIN again';
    Widget key(String label, VoidCallback onTap, {IconData? icon}) => Pressable(
          onTap: onTap,
          child: Container(
            width: 150,
            height: 74,
            decoration: BoxDecoration(border: Border.all(color: const Color(0x99FFFFFF))),
            child: Center(child: icon != null ? Icon(icon, size: 38, color: Mib.white) : Text(label, style: Mib.text(34))),
          ),
        );
    void add(String d) => setState(() {
          if (_pin.length < 8) _pin += d;
          _error = null;
        });
    return Center(
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        SizedBox(
          width: 420,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Icon(MibIcons.lock, size: 56, color: Mib.white),
            const SizedBox(height: 18),
            Text(title, style: Mib.text(28)),
            const SizedBox(height: 22),
            Row(children: [
              for (var i = 0; i < 8; i++)
                Container(
                  margin: const EdgeInsets.only(right: 14),
                  width: 18,
                  height: 18,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: i < _pin.length ? Mib.white : null,
                    border: Border.all(color: i < _pin.length ? Mib.white : const Color(0x55FFFFFF), width: 2),
                  ),
                ),
            ]),
            const SizedBox(height: 18),
            Text(_error ?? (_hasPin == false ? 'Only you will know it. Forgot it? Clear MMI\'s app data in Android settings.' : ''),
                style: Mib.text(20, color: _error != null ? const Color(0xFFE5484D) : Mib.grey)),
          ]),
        ),
        const SizedBox(width: 40),
        Column(mainAxisSize: MainAxisSize.min, children: [
          for (final row in const [['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9']])
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Row(children: [for (final d in row) Padding(padding: const EdgeInsets.only(right: 10), child: key(d, () => add(d)))]),
            ),
          Row(children: [
            Padding(padding: const EdgeInsets.only(right: 10), child: key('', () => setState(() => _pin = _pin.isEmpty ? '' : _pin.substring(0, _pin.length - 1)), icon: MibIcons.backspace)),
            Padding(padding: const EdgeInsets.only(right: 10), child: key('0', () => add('0'))),
            Padding(padding: const EdgeInsets.only(right: 10), child: key('', _ok, icon: MibIcons.check)),
          ]),
        ]),
      ]),
    );
  }
}

/// Developer · CAN box car list: the vendor car chooser's whole list, browsed level by
/// level (CAN-box maker → brand → model → …), the unit's chosen car marked. Read only.
class CarListPage extends StatefulWidget {
  const CarListPage({super.key});
  @override
  State<CarListPage> createState() => _CarListPageState();
}

class _CarListPageState extends State<CarListPage> {
  final _trail = <(String, String)>[('2', 'CAN box makers')];
  Map<Object?, Object?>? _data;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final path = _trail.last.$1;
    for (var i = 0; i < 60; i++) {
      final m = await Bridge.call<Map<Object?, Object?>>('carListGet', {'path': path});
      if (!mounted || _trail.last.$1 != path) return;
      setState(() => _data = m);
      if (m == null || m['loading'] != true) return;
      await Future.delayed(const Duration(milliseconds: 500));
    }
  }

  void _go((String, String) to) {
    setState(() {
      _trail.add(to);
      _data = null;
    });
    _load();
  }

  void _up() {
    if (_trail.length < 2) return;
    setState(() {
      _trail.removeLast();
      _data = null;
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final items = ((_data?['items'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    final loading = _data == null || _data!['loading'] == true;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(10, 14, 10, 8),
        child: Text(_trail.map((t) => t.$2).join('  ›  '), style: Mib.text(21, color: Mib.grey), maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
      if (_trail.length > 1) MibRow(label: 'Up one level', icon: MibIcons.arrow_back, height: 64, onTap: _up),
      Expanded(
        child: _data?['error'] != null
            ? Center(child: Text('${_data!['error']}', style: Mib.text(24, color: Mib.grey)))
            : loading
                ? Center(child: Text('Reading the car list…', style: Mib.text(24, color: Mib.grey)))
                : ListView.builder(
                    itemCount: items.length,
                    itemExtent: 72,
                    itemBuilder: (context, i) {
                      final it = items[i];
                      final chosen = it['chosen'] == true;
                      final name = '${it['name']}';
                      final original = '${it['original']}';
                      return Pressable(
                        onTap: () => it['more'] == true ? _go(('${it['path']}', name)) : null,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10),
                          decoration: BoxDecoration(
                            color: chosen ? const Color(0x1AFFFFFF) : null,
                            border: Border(
                              left: BorderSide(color: chosen ? Mib.white : const Color(0x00000000), width: 4),
                              bottom: const BorderSide(color: Color(0x40FFFFFF)),
                            ),
                          ),
                          child: Row(children: [
                            Expanded(
                              child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                                Text(name, style: Mib.text(25), maxLines: 1, overflow: TextOverflow.ellipsis),
                                if (original != name) Text(original, style: Mib.text(17, color: Mib.grey), maxLines: 1),
                              ]),
                            ),
                            if (chosen) Text('this unit   ', style: Mib.text(19, color: const Color(0xFF5BD35B))),
                            Text('#${it['id']}', style: Mib.text(18, color: Mib.grey)),
                            if (it['more'] == true) const Padding(padding: EdgeInsets.only(left: 10), child: Icon(MibIcons.chevron_right, size: 30, color: Mib.white)),
                          ]),
                        ),
                      );
                    },
                  ),
      ),
    ]);
  }
}
