import 'dart:async';
import 'package:flutter/widgets.dart';
import '../bridge.dart';
import '../live.dart';
import '../theme/mib.dart';
import '../theme/mib_icons.dart';
import '../widgets/pressable.dart';

const _green = Color(0xFF1E7B34);
const _red = Color(0xFF9E1218);
const _missed = Color(0xFFE5484D);
const _arrow = Color(0xFF5BD35B);

/// Telephone · Call list (design 33): call type symbol, name over type, time on the
/// right, A–Z strip to jump through the list.
class CallListPage extends StatefulWidget {
  const CallListPage({super.key, required this.onDial});
  final VoidCallback onDial;
  @override
  State<CallListPage> createState() => _CallListPageState();
}

class _CallListPageState extends State<CallListPage> with Live<CallListPage> {
  @override
  String get liveMethod => 'callsGet';
  @override
  String? get liveTopic => 'calls';
  @override
  Duration get liveEvery => const Duration(seconds: 30);
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final calls = ((live?['calls'] as List?) ?? const []).cast<Map<Object?, Object?>>();
    if (live != null && live!['allowed'] == false) {
      return _Message('MMI needs access to the call list.', 'Allow', () => Bridge.call('system', {'what': 'permissions'}));
    }
    if (calls.isEmpty) {
      return _Message(live == null ? '' : 'No calls yet. Calls made with your phone connected show here.', 'Dial number', widget.onDial);
    }
    return Row(children: [
      Expanded(
        child: ListView.builder(
          controller: _scroll,
          itemCount: calls.length,
          itemExtent: 84,
          itemBuilder: (context, i) => _CallRow(calls[i]),
        ),
      ),
      SizedBox(
        width: 30,
        child: Column(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
          for (final c in 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split(''))
            GestureDetector(
              onTap: () {
                final i = calls.indexWhere((x) => '${x['name']}'.toUpperCase().startsWith(c));
                if (i >= 0) _scroll.animateTo(i * 84.0, duration: const Duration(milliseconds: 300), curve: Curves.easeOutCubic);
              },
              child: Text(c, style: Mib.text(16, color: const Color(0xFFD0D0D0), height: 1.0)),
            ),
        ]),
      ),
    ]);
  }
}

class _CallRow extends StatelessWidget {
  const _CallRow(this.c);
  final Map<Object?, Object?> c;
  @override
  Widget build(BuildContext context) {
    final type = c['type'] as String? ?? 'in';
    final label = switch (type) { 'out' => 'Outgoing', 'missed' => 'Missed', _ => 'Incoming' };
    return Pressable(
      onTap: () => Bridge.call('dial', {'number': c['number']}),
      child: Container(
        decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0x40FFFFFF)))),
        child: Row(children: [
          SizedBox(width: 106, child: Center(child: CallTypeIcon(type))),
          Expanded(
            child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${c['name']}', style: Mib.text(28), maxLines: 1, overflow: TextOverflow.ellipsis),
              Text(label, style: Mib.text(20, color: Mib.grey)),
            ]),
          ),
          Text('${c['when'] ?? ''}', style: Mib.text(23)),
          const SizedBox(width: 22),
        ]),
      ),
    );
  }
}

/// Handset with a small arrow: green in, white out; red handset with a cross when missed.
class CallTypeIcon extends StatelessWidget {
  const CallTypeIcon(this.type, {super.key});
  final String type;
  @override
  Widget build(BuildContext context) {
    final missed = type == 'missed';
    return SizedBox(
      width: 50,
      height: 46,
      child: Stack(children: [
        Positioned(left: 0, bottom: 0, child: Icon(missed ? MibIcons.call_end : MibIcons.call, size: missed ? 38 : 34, color: missed ? _missed : Mib.white)),
        Positioned(
          right: 4,
          top: 0,
          child: Icon(missed ? MibIcons.close : (type == 'out' ? MibIcons.call_made : MibIcons.call_received), size: 22, color: missed ? _missed : (type == 'out' ? Mib.white : _arrow)),
        ),
      ]),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message(this.text, this.action, this.onTap);
  final String text, action;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(text, style: Mib.text(26, color: Mib.grey), textAlign: TextAlign.center),
          const SizedBox(height: 28),
          Pressable(
            onTap: onTap,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 34, vertical: 16),
              decoration: BoxDecoration(border: Border.all(color: const Color(0xAAFFFFFF))),
              child: Text(action, style: Mib.text(24)),
            ),
          ),
        ]),
      );
}

/// Telephone · Dial number (design 35): number box with backspace, 3 × 4 keys with
/// letters, green CALL, RECENT / CONTACTS.
class DialPage extends StatefulWidget {
  const DialPage({super.key, required this.onRecent});
  final VoidCallback onRecent;
  @override
  State<DialPage> createState() => _DialPageState();
}

class _DialPageState extends State<DialPage> {
  String number = '';
  static const keys = [
    ('1', ''), ('2', 'ABC'), ('3', 'DEF'),
    ('4', 'GHI'), ('5', 'JKL'), ('6', 'MNO'),
    ('7', 'PQRS'), ('8', 'TUV'), ('9', 'WXYZ'),
    ('*', ''), ('0', '+'), ('#', ''),
  ];

  String get shown {
    // 0100 123 4567 grouping for Egyptian mobiles, plain otherwise.
    final d = number;
    if (d.length <= 4 || !RegExp(r'^\d+$').hasMatch(d)) return d;
    if (d.length <= 7) return '${d.substring(0, 4)} ${d.substring(4)}';
    return '${d.substring(0, 4)} ${d.substring(4, 7)} ${d.substring(7)}';
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 17, top: 18),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
          width: 548,
          child: Column(children: [
            Container(
              height: 69,
              padding: const EdgeInsets.only(left: 18, right: 12),
              decoration: BoxDecoration(border: Border.all(color: const Color(0xAAFFFFFF))),
              child: Row(children: [
                Expanded(child: Text(shown, style: Mib.text(38), maxLines: 1, overflow: TextOverflow.ellipsis)),
                Pressable(
                  onTap: () => setState(() => number = number.isEmpty ? '' : number.substring(0, number.length - 1)),
                  onLongPress: () => setState(() => number = ''),
                  child: const Padding(padding: EdgeInsets.all(6), child: Icon(MibIcons.backspace, size: 44, color: Mib.white)),
                ),
              ]),
            ),
            const SizedBox(height: 18),
            for (var r = 0; r < 4; r++)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Row(children: [
                  for (var c = 0; c < 3; c++) ...[
                    if (c > 0) const SizedBox(width: 10),
                    Expanded(child: _Key(keys[r * 3 + c], () => setState(() => number += keys[r * 3 + c].$1),
                        long: keys[r * 3 + c].$1 == '0' ? () => setState(() => number += '+') : null)),
                  ],
                ]),
              ),
          ]),
        ),
        const SizedBox(width: 41),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.only(top: 87, right: 4),
            child: Column(children: [
              Pressable(
                onTap: () {
                  if (number.isNotEmpty) Bridge.call('dial', {'number': number});
                },
                child: Container(
                  height: 215,
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0xFF238A3B), _green]),
                    border: Border.all(color: const Color(0xFF3BB55A)),
                  ),
                  child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                    const Icon(MibIcons.call, size: 66, color: Mib.white),
                    const SizedBox(width: 30),
                    Text('CALL', style: Mib.text(40)),
                  ]),
                ),
              ),
              const SizedBox(height: 16),
              Pressable(
                onTap: widget.onRecent,
                child: Container(
                  height: 208,
                  decoration: BoxDecoration(border: Border.all(color: const Color(0xAAFFFFFF))),
                  child: Center(child: Text('RECENT / CONTACTS', style: Mib.text(27))),
                ),
              ),
            ]),
          ),
        ),
      ]),
    );
  }
}

class _Key extends StatelessWidget {
  const _Key(this.k, this.onTap, {this.long});
  final (String, String) k;
  final VoidCallback onTap;
  final VoidCallback? long;
  @override
  Widget build(BuildContext context) => Pressable(
        onTap: onTap,
        onLongPress: long,
        child: Container(
          height: 102,
          decoration: BoxDecoration(border: Border.all(color: const Color(0x99FFFFFF))),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            Text(k.$1, style: Mib.text(k.$1 == '*' || k.$1 == '#' ? 36 : 40, height: 1.05)),
            if (k.$2.isNotEmpty) Text(k.$2, style: Mib.text(19, color: const Color(0xFFD8D8D8))),
          ]),
        ),
      );
}

/// In a call (design 34): round avatar, name, number, running time, four round
/// buttons and the red End Call bar. An incoming call shows Answer / Decline instead.
/// Mute, Speaker and Hold need the car's phone module, which MMI cannot reach yet:
/// they are shown dimmed rather than pretending to work.
class InCallPage extends StatefulWidget {
  const InCallPage({super.key, required this.call});
  final Map<Object?, Object?> call;
  @override
  State<InCallPage> createState() => _InCallPageState();
}

class _InCallPageState extends State<InCallPage> {
  late final Timer _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));

  @override
  void dispose() {
    _tick.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.call;
    final state = c['state'] as String? ?? 'active';
    final since = (c['since'] as num?)?.toInt() ?? 0;
    final secs = since > 0 ? (DateTime.now().millisecondsSinceEpoch - since) ~/ 1000 : 0;
    final time = switch (state) {
      'incoming' => 'Incoming call',
      'outgoing' => 'Calling…',
      _ => '${(secs ~/ 60).toString().padLeft(2, '0')}:${(secs % 60).toString().padLeft(2, '0')}',
    };
    final name = (c['name'] as String?)?.trim();
    final number = (c['number'] as String?) ?? '';
    return Column(children: [
      const SizedBox(height: 34),
      Container(
        width: 152,
        height: 152,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: const Color(0xFF1A1A1A),
          border: Border.all(color: const Color(0xFFBDBDBD), width: 2.5),
        ),
        child: ClipOval(
          child: Stack(alignment: Alignment.center, children: [
            Positioned(top: 32, child: Container(width: 48, height: 48, decoration: const BoxDecoration(shape: BoxShape.circle, color: Color(0xFFB8B8B8)))),
            Positioned(top: 88, child: Container(width: 92, height: 80, decoration: const BoxDecoration(borderRadius: BorderRadius.vertical(top: Radius.circular(46)), color: Color(0xFFB8B8B8)))),
          ]),
        ),
      ),
      const SizedBox(height: 14),
      Text(name?.isNotEmpty == true ? name! : (number.isEmpty ? 'Unknown' : number), style: Mib.text(48)),
      if (name?.isNotEmpty == true) Text(number, style: Mib.text(30, color: Mib.grey)),
      const SizedBox(height: 2),
      Text(time, style: Mib.text(32)),
      const SizedBox(height: 36),
      if (state == 'incoming')
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          _Bar('Answer', MibIcons.call, _green, () => Bridge.call('answer')),
          const SizedBox(width: 40),
          _Bar('Decline', MibIcons.call_end, _red, () => Bridge.call('hangUp')),
        ])
      else ...[
        Row(mainAxisAlignment: MainAxisAlignment.center, children: const [
          _Round('Mute', MibIcons.mic_off),
          SizedBox(width: 50),
          _Round('Keypad', MibIcons.dialpad),
          SizedBox(width: 50),
          _Round('Speaker', MibIcons.volume_up),
          SizedBox(width: 50),
          _Round('Hold', MibIcons.pause),
        ]),
        const SizedBox(height: 46),
        _Bar('End Call', MibIcons.call_end, _red, () => Bridge.call('hangUp'), width: 371),
      ],
    ]);
  }
}

class _Round extends StatelessWidget {
  const _Round(this.label, this.icon);
  final String label;
  final IconData icon;
  @override
  Widget build(BuildContext context) {
    // Not reachable on this unit yet: drawn at half strength, no action.
    const c = Color(0x66FFFFFF);
    return Container(
      width: 128,
      height: 128,
      decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: const Color(0x55FFFFFF), width: 2)),
      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
        icon == MibIcons.pause ? const TransportIcon(Transport.pause, size: 48, color: c) : Icon(icon, size: 44, color: c),
        const SizedBox(height: 4),
        Text(label, style: Mib.text(19, color: c)),
      ]),
    );
  }
}

class _Bar extends StatelessWidget {
  const _Bar(this.label, this.icon, this.color, this.onTap, {this.width = 300});
  final String label;
  final IconData icon;
  final Color color;
  final VoidCallback onTap;
  final double width;
  @override
  Widget build(BuildContext context) => Pressable(
        onTap: onTap,
        child: Container(
          width: width,
          height: 86,
          color: color,
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            Icon(icon, size: 52, color: Mib.white),
            const SizedBox(width: 26),
            Text(label, style: Mib.text(29)),
          ]),
        ),
      );
}
