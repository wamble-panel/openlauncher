import 'package:flutter/widgets.dart' as w;
import 'package:flutter/widgets.dart' hide Text;

/// MMI's languages: English (the source) and Arabic. Every [Text] in MMI goes through
/// [tr]: a phrase found here is shown in Arabic, a phrase with numbers in it is matched
/// against the patterns below, and anything else (station names, song titles, contacts,
/// places) is shown as it is. Arabic also turns the whole interface right-to-left.
final lang = ValueNotifier<String>('en');

bool get isArabic => lang.value == 'ar';

String tr(String s) {
  if (!isArabic || s.isEmpty) return s;
  final exact = _ar[s] ?? _ar[s.trim()];
  if (exact != null) return exact;
  for (final p in _patterns) {
    final m = p.$1.firstMatch(s);
    if (m != null) return p.$2(m);
  }
  return s;
}

/// Drop-in for Flutter's Text that translates its string.
class Text extends StatelessWidget {
  const Text(this.data, {super.key, this.style, this.textAlign, this.maxLines, this.overflow, this.softWrap, this.textHeightBehavior});
  final String data;
  final TextStyle? style;
  final TextAlign? textAlign;
  final int? maxLines;
  final TextOverflow? overflow;
  final bool? softWrap;
  final TextHeightBehavior? textHeightBehavior;

  @override
  Widget build(BuildContext context) {
    // Depends on the reading direction, so every text re-translates when the language changes.
    final dir = Directionality.of(context);
    final text = tr(data);
    return w.Text(
        text,
        // Values with no Arabic letters (14.1 V, 104.2 MHz, 1:21 | -2:01, names) keep their
        // own left-to-right order inside the Arabic interface.
        textDirection: dir == TextDirection.rtl && !_arabicLetters.hasMatch(text) ? TextDirection.ltr : null,
        style: style,
        textAlign: textAlign,
        maxLines: maxLines,
        overflow: overflow,
        softWrap: softWrap,
        textHeightBehavior: textHeightBehavior,
      );
  }
}

final _arabicLetters = RegExp(r'[\u0600-\u06FF]');

String _unit(String u) => switch (u) { 'km/h' => 'كم/س', 'km' => 'كم', 'MB' => 'ميغابايت', 'GB' => 'غيغابايت', 'min' => 'دقيقة', _ => u };

final _patterns = <(RegExp, String Function(RegExpMatch))>[
  (RegExp(r'^Doors: (.+)$'), (m) => 'الأبواب: ${tr(m[1]!)}'),
  (RegExp(r'^Odometer (.+) km$'), (m) => 'العداد ${m[1]} كم'),
  (RegExp(r'^CAN box: (.+)$'), (m) => 'صندوق CAN: ${m[1]}'),
  (RegExp(r'^Visible to phones as: (.+)$'), (m) => 'ظاهر للهواتف باسم: ${m[1]}'),
  (RegExp(r'^High (.+)°   Low (.+)°$'), (m) => 'العظمى ${m[1]}°   الصغرى ${m[2]}°'),
  (RegExp(r'^Wind (.+) km/h$'), (m) => 'الرياح ${m[1]} كم/س'),
  (RegExp(r'^Updated (.+)$'), (m) => 'آخر تحديث ${m[1]}'),
  (RegExp(r'^Version (\d+) is downloaded and ready\.$'), (m) => 'الإصدار ${m[1]} جاهز للتثبيت.'),
  (RegExp(r'^Version (\d+) is available\.$'), (m) => 'الإصدار ${m[1]} متاح.'),
  (RegExp(r'^Build (\d+)$'), (m) => 'الإصدار ${m[1]}'),
  (RegExp(r'^Downloading (\d+) %$'), (m) => 'جارٍ التنزيل ${m[1]}٪'),
  (RegExp(r'^([\d.]+) GB free of ([\d.]+) GB$'), (m) => '${m[1]} غيغابايت متاحة من ${m[2]} غيغابايت'),
  (RegExp(r'^(\d+) \(newest (\d+)\)$'), (m) => '${m[1]} (الأحدث ${m[2]})'),
  (RegExp(r'^MMI uses (.+) MB$'), (m) => 'يستخدم MMI ${m[1]} ميغابايت'),
  (RegExp(r'^(.+) of (.+) MB$'), (m) => '${m[1]} من ${m[2]} ميغابايت'),
  (RegExp(r'^up to (\d+) MHz per core \(4 × Cortex-A53\)$'), (m) => 'حتى ${m[1]} ميغاهرتز لكل نواة (4 × Cortex-A53)'),
  (RegExp(r'^Button (\d+) now opens (.+)\.$'), (m) => 'الزر ${m[1]} يفتح الآن ${tr(m[2]!)}.'),
  (RegExp(r'^Press the button on the car for "(.+)" now…$'), (m) => 'اضغط الآن زر السيارة الخاص بـ «${tr(m[1]!)}»…'),
  (RegExp(r'^button (\d+)$'), (m) => 'الزر ${m[1]}'),
  (RegExp(r'^(\d+) Hz$'), (m) => '${m[1]} هرتز'),
  (RegExp(r'^(\d+) kHz$'), (m) => '${m[1]} كيلوهرتز'),
  (RegExp(r'^([\d.,]+) (km/h|km|MB|GB|min)$'), (m) => '${m[1]} ${_unit(m[2]!)}'),
  (RegExp(r'^Calling…$'), (m) => 'جارٍ الاتصال…'),
];

/// English → Arabic, in the words premium in-car systems use.
const _ar = <String, String>{
  // Main areas and home tiles.
  'Home': 'الرئيسية', 'RADIO': 'الراديو', 'Radio': 'الراديو', 'MEDIA': 'الوسائط', 'Media': 'الوسائط',
  'TELEPHONE': 'الهاتف', 'Telephone': 'الهاتف', 'NAVIGATION': 'الملاحة', 'Navigation': 'الملاحة',
  'PHONE LINK': 'ربط الهاتف', 'Phone Link': 'ربط الهاتف', 'CAR': 'السيارة', 'Car': 'السيارة',
  'SETTINGS': 'الإعدادات', 'Settings': 'الإعدادات', 'HELP': 'المساعدة', 'Help': 'المساعدة',
  'VIDEO': 'الفيديو', 'Video': 'الفيديو', 'EQUALISER': 'المعادل الصوتي', 'Equaliser': 'المعادل الصوتي',
  'WEATHER': 'الطقس', 'Weather': 'الطقس', 'APPS': 'التطبيقات', 'Apps': 'التطبيقات', 'BROWSER': 'المتصفح', 'Browser': 'المتصفح',
  'Back': 'رجوع', 'Sound': 'الصوت', 'Source': 'المصدر', 'More': 'المزيد', 'Open': 'فتح', 'Save': 'حفظ', 'Allow': 'سماح',
  'Phone': 'الهاتف', 'End': 'إنهاء', 'Dial': 'طلب', 'Dial number': 'طلب رقم', 'Call list': 'سجل المكالمات',
  // Apps.
  'Files': 'الملفات', 'Music': 'الموسيقى', 'Camera': 'الكاميرا', 'Calculator': 'الآلة الحاسبة', 'Calendar': 'التقويم',
  'Clock': 'الساعة', 'Gallery': 'المعرض', 'Maps': 'الخرائط', 'Store': 'المتجر', 'Notes': 'الملاحظات', 'Recorder': 'المسجّل',
  // Media.
  'Now playing': 'قيد التشغيل', 'Nothing is playing': 'لا يوجد تشغيل حالياً', 'Choose a source to start music.': 'اختر مصدراً لتشغيل الموسيقى.',
  'Playing': 'قيد التشغيل', 'Paused': 'متوقف مؤقتاً', 'AUX input': 'مدخل AUX', 'USB music': 'موسيقى USB',
  'Songs': 'الأغاني', 'Albums': 'الألبومات', 'Artists': 'الفنانون', 'Folders': 'المجلدات',
  'Looking for music…': 'جارٍ البحث عن الموسيقى…', 'No music found on USB or SD card.': 'لم يتم العثور على موسيقى في USB أو بطاقة SD.',
  'Look again': 'البحث مجدداً', 'Play / pause': 'تشغيل / إيقاف مؤقت', 'Next track': 'المقطع التالي', 'Previous track': 'المقطع السابق',
  // Radio.
  'FM': 'FM', 'AM': 'AM', 'FM / AM': 'FM / AM', 'Favourites': 'المفضلة', 'Empty – press Save': 'فارغ – اضغط حفظ',
  'Fine tune': 'ضبط دقيق', 'Radio favourites': 'محطات الراديو المفضلة',
  // Telephone.
  'Incoming call': 'مكالمة واردة', 'Incoming': 'واردة', 'Outgoing': 'صادرة', 'Missed': 'فائتة', 'Unknown': 'غير معروف',
  'Answer': 'رد', 'Decline': 'رفض', 'Mute': 'كتم', 'Keypad': 'لوحة الأرقام', 'Speaker': 'مكبر الصوت', 'Hold': 'انتظار',
  'End Call': 'إنهاء المكالمة', 'RECENT / CONTACTS': 'الأخيرة / جهات الاتصال', 'MMI needs access to the call list.': 'يحتاج MMI إلى إذن الوصول إلى سجل المكالمات.',
  'No calls yet. Calls made with your phone connected show here.': 'لا توجد مكالمات بعد. تظهر هنا المكالمات التي تُجرى أثناء اتصال هاتفك.',
  'Phone connected': 'الهاتف متصل', 'No phone connected': 'لا يوجد هاتف متصل',
  'USB cable for CarPlay / Android Auto, or Bluetooth for calls and music.': 'كابل USB لـ CarPlay / Android Auto، أو البلوتوث للمكالمات والموسيقى.',
  'Calls': 'المكالمات', 'Phone music': 'موسيقى الهاتف', 'Other phone': 'هاتف آخر', 'Connect a phone': 'توصيل هاتف',
  'Open CarPlay / Android Auto when the phone connects': 'فتح CarPlay / Android Auto عند اتصال الهاتف',
  'Connected · tap to open': 'متصل · اضغط للفتح', 'Not connected': 'غير متصل', 'Signal': 'الإشارة', 'Battery': 'البطارية',
  'Apple CarPlay · USB': 'Apple CarPlay · USB', 'Apple CarPlay · wireless': 'Apple CarPlay · لاسلكي',
  'Android Auto · USB': 'Android Auto · USB', 'Android Auto · wireless': 'Android Auto · لاسلكي',
  'Searching…': 'جارٍ البحث…', 'Phones nearby': 'الهواتف القريبة', 'Connected': 'متصل', 'Connecting…': 'جارٍ الاتصال…',
  'Tap to connect': 'اضغط للاتصال', 'Connect mobile device': 'توصيل جهاز محمول', 'Phone mirroring': 'عرض شاشة الهاتف',
  // Settings grid and lists.
  'General': 'عام', 'Display': 'الشاشة', 'Display &\nbrightness': 'الشاشة\nوالسطوع', 'Bluetooth &\nconnections': 'البلوتوث\nوالاتصالات',
  'Language &\nkeyboard': 'اللغة\nولوحة المفاتيح', 'System &\nupdates': 'النظام\nوالتحديثات',
  'Bluetooth & connections': 'البلوتوث والاتصالات', 'Language & keyboard': 'اللغة ولوحة المفاتيح', 'System & updates': 'النظام والتحديثات',
  'Allow brightness control': 'السماح بالتحكم في السطوع', 'one-time permission': 'إذن لمرة واحدة', 'Brightness': 'السطوع',
  'Day / night mode': 'وضع النهار / الليل', 'Auto': 'تلقائي', 'Day': 'نهار', 'Night': 'ليل', 'Min': 'أدنى', 'Max': 'أقصى',
  'Touch sounds': 'أصوات اللمس', 'Screen off while parked': 'إطفاء الشاشة أثناء التوقف', 'Clock format': 'تنسيق الساعة',
  '24 h': '24 ساعة', '12 h': '12 ساعة', 'Units': 'الوحدات', 'km, km/h': 'كم، كم/س', 'mi, mph': 'ميل، ميل/س',
  'Speed camera alerts': 'تنبيهات كاميرات السرعة', 'on every page, also without the map': 'في كل الصفحات، حتى بدون الخريطة',
  'Call bar on the map': 'شريط المكالمة على الخريطة', 'Keep music playing in reverse': 'استمرار الموسيقى عند الرجوع للخلف',
  'Wi-Fi': 'Wi-Fi', 'Bluetooth (system)': 'البلوتوث (النظام)', 'Voice guidance': 'الإرشاد الصوتي',
  'Maps and offline data': 'الخرائط والبيانات دون اتصال', 'Language': 'اللغة', 'system language and region': 'لغة النظام والمنطقة',
  'Keyboard': 'لوحة المفاتيح', 'Date and time': 'التاريخ والوقت', 'MMI language': 'لغة MMI',
  'Software update': 'تحديث البرنامج', 'Storage': 'التخزين', 'Version': 'الإصدار', 'Developer': 'المطوّر',
  'for the owner only; opens with your PIN': 'للمالك فقط؛ يُفتح برمزك السري',
  // Car.
  'Car information': 'معلومات السيارة', 'Car buttons': 'أزرار السيارة', 'Diagnostics': 'التشخيص', 'Climate': 'التكييف',
  'Not found in the car list': 'غير موجودة في قائمة السيارات', 'Car picture': 'صورة السيارة', 'Your photo': 'صورتك',
  'Astra J': 'Astra J', 'Opel': 'أوبل', 'This unit': 'هذا الجهاز', 'what MMI found on this head unit, by itself': 'ما اكتشفه MMI في هذا الجهاز تلقائيًا',
  'Unit': 'الجهاز', 'Bluetooth phone': 'هاتف البلوتوث', 'Car data': 'بيانات السيارة',
  'Reverse camera': 'كاميرا الرجوع', 'Steering-wheel buttons': 'أزرار المقود', 'CarPlay / Android Auto': 'CarPlay / Android Auto',
  'Sound and equaliser': 'الصوت والمعادل', 'In MMI': 'داخل MMI', "The unit's app": 'تطبيق الجهاز', 'By the unit': 'من الجهاز',
  'Not on this unit': 'غير متوفر في هذا الجهاز',
  'MMI checks this by itself every time it starts. Jobs the unit does with its own app open that app; jobs the unit does not have are hidden.':
      'يفحص MMI ذلك تلقائيًا عند كل تشغيل. الوظائف التي ينفذها الجهاز بتطبيقه الخاص تفتح ذلك التطبيق، والوظائف غير المتوفرة تُخفى.',
  'Nissan Sunny': 'نيسان صني', 'None': 'بلا', 'Your own: put car.png in the MMI folder (unit storage)': 'صورتك: ضع car.png في مجلد MMI (ذاكرة الجهاز)',
  'give unknown steering-wheel or dash buttons a job': 'خصّص وظيفة لأزرار المقود أو اللوحة غير المعروفة',
  'Coolant': 'سائل التبريد', 'Oil life': 'عمر الزيت', 'Range': 'المدى', 'Avg consumption': 'متوسط الاستهلاك', 'Outside': 'الخارج',
  'km/h': 'كم/س', 'rpm': 'د/د', 'Handbrake on': 'فرامل اليد مفعّلة', 'all closed': 'مغلقة جميعها',
  'Tap a job, then press the car button for it. Hold a job to clear its buttons.': 'اختر وظيفة، ثم اضغط زر السيارة الخاص بها. اضغط مطولاً على وظيفة لمسح أزرارها.',
  'That is the knob; it stays as it is. Press another button.': 'هذا هو المقبض الدوّار ويبقى كما هو. اضغط زراً آخر.',
  'waiting…': 'بانتظار الضغط…', 'no button set': 'لا يوجد زر', 'Sound ': 'الصوت',
  // Sound.
  'Equaliser ': 'المعادل الصوتي', 'Presets': 'الإعدادات المسبقة', 'Flat': 'مستوٍ', 'Rock': 'روك', 'Pop': 'بوب', 'Jazz': 'جاز',
  'Voice': 'الكلام', 'Custom': 'مخصص', 'Natural': 'طبيعي', 'Bass': 'الجهير', 'Low mid': 'متوسط منخفض', 'Mid': 'متوسط',
  'High mid': 'متوسط مرتفع', 'Treble': 'الحِدّة', 'Balance / fader': 'التوازن / الأمام والخلف', 'Speed-dependent volume': 'مستوى الصوت حسب السرعة',
  'Off': 'إيقاف', 'Low': 'منخفض', 'High': 'مرتفع', 'On': 'تشغيل', 'Loudness': 'تعزيز الصوت', 'fuller sound at low volume': 'صوت أغنى عند المستوى المنخفض',
  'Your own curve: drag the points.': 'منحناك الخاص: اسحب النقاط.', 'not available on this unit': 'غير متاح في هذا الجهاز',
  '10 bands, limiter on': '10 نطاقات، المحدِّد مفعّل', "mapped to the unit's bands": 'موزّعة على نطاقات الجهاز',
  'Sub-bass': 'الجهير العميق', 'Upper bass': 'الجهير العلوي', 'Low mids': 'المتوسطات المنخفضة', 'Mids': 'المتوسطات', 'Centre': 'المنتصف',
  'Upper mids': 'المتوسطات العليا', 'Presence': 'الحضور', 'Brilliance': 'اللمعان', 'Air': 'الهواء', 'Volume': 'مستوى الصوت',
  // Help.
  'Knob': 'المقبض الدوّار', 'Turn to move the white outline, press to choose. On Radio, turning tunes.': 'أدِره لتحريك الإطار الأبيض واضغطه للاختيار. في الراديو يغيّر التردد.',
  'Goes to the page before. The home key always goes home.': 'يعود إلى الصفحة السابقة. زر الرئيسية يعود دائماً إلى الشاشة الرئيسية.',
  'RADIO · MEDIA · PHONE': 'الراديو · الوسائط · الهاتف', 'Open their pages from anywhere.': 'تفتح صفحاتها من أي مكان.',
  'Opens Sound: equaliser, presets and speed-dependent volume.': 'يفتح الصوت: المعادل الصوتي والإعدادات المسبقة ومستوى الصوت حسب السرعة.',
  'Save puts the station in the first free place; hold a favourite to replace it. Keys 1–6 tune them.': 'زر الحفظ يضع المحطة في أول مكان فارغ؛ اضغط مطولاً على محطة مفضلة لاستبدالها. الأزرار 1–6 تشغّلها.',
  'Speed cameras': 'كاميرات السرعة', 'Alerts show on every page, also without the map.': 'تظهر التنبيهات في كل الصفحات، حتى بدون الخريطة.',
  'Marked camera': 'كاميرا محفوظة', 'Speed camera': 'كاميرا سرعة',
  // Weather.
  'Clear': 'صافٍ', 'Mostly clear': 'صافٍ غالباً', 'Partly cloudy': 'غائم جزئياً', 'Cloudy': 'غائم', 'Fog': 'ضباب', 'Drizzle': 'رذاذ',
  'Rain': 'مطر', 'Snow': 'ثلج', 'Thunderstorm': 'عاصفة رعدية', 'Loading the weather…': 'جارٍ تحميل الطقس…',
  'Waiting for the car\'s position…': 'بانتظار موقع السيارة…', 'Tue': 'الثلاثاء', 'Wed': 'الأربعاء', 'Thu': 'الخميس', 'Fri': 'الجمعة',
  'Sat': 'السبت', 'Sun': 'الأحد', 'Mon': 'الاثنين', 'Yesterday': 'أمس',
  // Software update.
  "The last update didn't install.": 'لم يتم تثبيت التحديث الأخير.', 'Checking for updates…': 'جارٍ البحث عن تحديثات…',
  'Not checked yet.': 'لم يتم التحقق بعد.', 'MMI is up to date.': 'MMI محدّث.', 'Try again': 'إعادة المحاولة', 'Install now': 'التثبيت الآن',
  'Download and install': 'التنزيل والتثبيت', 'Check again': 'التحقق مجدداً', 'Working…': 'جارٍ العمل…', 'Checking…': 'جارٍ التحقق…',
  'Installing…': 'جارٍ التثبيت…', 'No internet. Connect Wi-Fi and try again.': 'لا يوجد اتصال بالإنترنت. اتصل بشبكة Wi-Fi وحاول مجدداً.',
  'Allow MMI to install apps, then tap again.': 'اسمح لـ MMI بتثبيت التطبيقات، ثم اضغط مجدداً.',
  'New builds download by themselves when the unit is online; installing takes one tap here.': 'تُنزَّل الإصدارات الجديدة تلقائياً عند اتصال الجهاز بالإنترنت؛ ويتم التثبيت بضغطة واحدة هنا.',
  'Unit storage': 'ذاكرة الجهاز',
  // Developer.
  'CAN box car list': 'قائمة سيارات صندوق CAN', 'every car the unit supports, and the one it is set to': 'كل السيارات التي يدعمها الجهاز، والسيارة المضبوط عليها',
  'Car data decoding': 'فك ترميز بيانات السيارة', 'Automatic': 'تلقائي', 'Opel / GM': 'أوبل / GM', 'Car service only': 'خدمة السيارة فقط',
  'Unit health': 'حالة الجهاز', 'chip temperatures, speed, memory, frame times': 'حرارة الشريحة والسرعة والذاكرة وأزمنة الإطارات',
  'Vendor apps': 'تطبيقات الشركة المصنّعة', 'which ones MMI replaces; disable them one by one': 'أيها يستبدلها MMI؛ عطّلها واحداً تلو الآخر',
  'System tuning': 'ضبط النظام', 'root speed-up, firmware backup': 'تسريع بصلاحيات الجذر، نسخ احتياطي للنظام',
  'OpenGL ES graphics': 'رسومات OpenGL ES', 'try if pages stutter; used from the next start': 'جرّبها إذا تقطّعت الصفحات؛ تُطبَّق من التشغيل التالي',
  'Classic MMI': 'MMI الكلاسيكي', 'switch back to the previous screens': 'العودة إلى الشاشات السابقة', 'Lock developer page': 'قفل صفحة المطوّر',
  'At least 4 digits.': '4 أرقام على الأقل.', 'Wrong PIN.': 'رمز سري خاطئ.', "The two PINs didn't match. Start again.": 'الرمزان غير متطابقين. ابدأ من جديد.',
  'Enter the developer PIN': 'أدخل الرمز السري للمطوّر', 'Choose a developer PIN (4 to 8 digits)': 'اختر رمزاً سرياً للمطوّر (4 إلى 8 أرقام)',
  'Enter the same PIN again': 'أدخل الرمز نفسه مجدداً', "Only you will know it. Forgot it? Clear MMI's app data in Android settings.": 'أنت فقط من يعرفه. نسيته؟ امسح بيانات تطبيق MMI من إعدادات أندرويد.',
  'CAN box makers': 'مصنّعو صناديق CAN', 'Factory settings (vendor)': 'إعدادات المصنع (الشركة المصنّعة)',
  'select model, steering wheel, doors: asks for the vendor password': 'اختيار الطراز والمقود والأبواب: يطلب كلمة مرور الشركة المصنّعة', 'Up one level': 'مستوى للأعلى', 'Reading the car list…': 'جارٍ قراءة قائمة السيارات…',
  'this unit': 'هذا الجهاز', 'Send unit data to developer': 'إرسال بيانات الجهاز إلى المطوّر', 'Sending unit data…': 'جارٍ إرسال بيانات الجهاز…',
  'Car software and system details, to your private repo': 'برامج السيارة وتفاصيل النظام، إلى مستودعك الخاص',
  'MMI replaces it': 'يستبدله MMI', 'Test in the car first': 'اختبره في السيارة أولاً', 'Keep for now': 'أبقِه حالياً', 'Disabled': 'معطّل',
  'CPU': 'المعالج', 'GPU': 'معالج الرسومات', 'Memory': 'الذاكرة', 'CPU speed': 'سرعة المعالج', 'GPU speed': 'سرعة معالج الرسومات',
  'Memory free': 'الذاكرة المتاحة', 'On for': 'مدة التشغيل', 'Smoothness': 'السلاسة', 'Smooth': 'سلس', 'Mostly smooth': 'سلس غالباً',
  'Stuttering': 'متقطع', 'move something': 'حرّك شيئاً', 'swipe Home or drag a slider, then look again': 'اسحب الشاشة الرئيسية أو شريط تمرير، ثم انظر مجدداً',
  'Hot: the chip is slowing itself down to cool off': 'ساخن: الشريحة تبطئ نفسها لتبرد', 'Warm: fine, but close to where it slows down': 'دافئ: جيد، لكنه قريب من حد التباطؤ',
  'Cool: full speed available': 'بارد: السرعة الكاملة متاحة', 'The unit does not show its temperatures to apps.': 'لا يُظهر الجهاز درجات حرارته للتطبيقات.',
  'Root available': 'صلاحيات الجذر متاحة', 'No root on this unit': 'لا توجد صلاحيات جذر في هذا الجهاز', 'Speed tune-up': 'تحسين السرعة',
  'Optimize now': 'تحسين الآن', 'Re-run optimize': 'إعادة التحسين', 'Undo optimize': 'التراجع عن التحسين', 'Firmware backup': 'نسخ احتياطي للنظام',
  'Back up partitions to USB': 'نسخ الأقسام احتياطياً إلى USB', 'Turn on Wi-Fi adb too': 'تشغيل adb عبر Wi-Fi أيضاً',
  'lets a laptop connect over Wi-Fi, no cable': 'يتيح لحاسوب محمول الاتصال عبر Wi-Fi بلا كابل',
  // Overlays.
  'A/C': 'التكييف', 'OFF': 'إيقاف', 'LO': 'منخفض', 'HI': 'مرتفع',
};
