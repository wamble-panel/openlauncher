package com.openlauncher.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openlauncher.app.util.CarAudio
import com.openlauncher.app.util.SpeedVolume
import com.openlauncher.app.util.TwBridge
import com.openlauncher.app.util.UiSound
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.math.abs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.conflate
import kotlin.math.roundToInt

/**
 * The new MMI screens, drawn by Flutter (mmi_ui). Car access stays in Kotlin: the
 * Flutter side asks for it over the "mmi" channel (sounds, equaliser, speed volume),
 * and pages not yet rebuilt in Flutter open in the classic MMI.
 */
class MmiFlutterActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Back from a call, the reverse camera or another app: the bars must stay hidden, or
    // the screen gets shorter and the whole interface would change size.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // The engine started at power-on (FlutterEngines.warm): the screens are ready the
    // moment this opens. It outlives the activity, so going back to MMI is instant too.
    private var prewarmed = false

    override fun provideFlutterEngine(context: Context): FlutterEngine {
        prewarmed = FlutterEngines.running
        return FlutterEngines.get(context)
    }

    override fun shouldDestroyEngineWithHost(): Boolean = false

    // Starting page, e.g. "/sound" (adb: --es flutter_page sound).
    override fun getInitialRoute(): String {
        val page = intent.getStringExtra("flutter_page") ?: "home"
        // The start-up screen once per power-on (first start of the app process).
        if (prewarmed) return "/home" // already running; the page is sent on attach
        val splash = !splashShown && page == "home"
        splashShown = true
        return "/$page" + if (splash) "?splash=1" else ""
    }

    private var channel: MethodChannel? = null
    private val scope = kotlinx.coroutines.MainScope()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        CarAudio.init(this)
        UiSound.init(this)
        SpeedVolume.init(this)
        val prefs = getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE)
        val car = FlutterBridge(this)
        val ch = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "mmi")
        channel = ch
        ch.setMethodCallHandler { call, result ->
            fun <T> arg(name: String): T? = call.argument<T>(name)
            try {
                when (call.method) {
                    "sound" -> {
                        when (arg<String>("kind")) {
                            "tick" -> UiSound.tick()
                            "open" -> UiSound.open()
                            else -> UiSound.tap()
                        }
                        result.success(null)
                    }
                    "eqGet" -> result.success(Eq.state(prefs))
                    "eqSet" -> { Eq.setSlider(prefs, arg<Int>("band") ?: 0, arg<Int>("level") ?: 0); result.success(null) }
                    "soundGet" -> {
                        val se = com.openlauncher.app.util.SoundEngine
                        result.success(mapOf(
                            "gains" to List(10) { se.gain(it).toDouble() },
                            "effective" to se.effective().map { it.toDouble() },
                            "centres" to se.CENTRES.toList(),
                            "preset" to se.preset,
                            "presets" to se.PRESETS.map { mapOf("name" to it.name, "about" to it.about, "gains" to it.gains.map { g -> g.toDouble() }) },
                            "loudness" to se.loudness,
                            "enabled" to se.enabled,
                            "engine" to se.engine,
                        ))
                    }
                    "soundSet" -> {
                        com.openlauncher.app.util.SoundEngine.setGain(arg<Int>("band") ?: 0, (arg<Number>("db") ?: 0).toFloat())
                        result.success(null)
                    }
                    "soundPreset" -> { com.openlauncher.app.util.SoundEngine.usePreset(arg<String>("name") ?: "Flat"); result.success(null) }
                    "soundLoudness" -> { com.openlauncher.app.util.SoundEngine.setLoudness(arg<Boolean>("on") == true); result.success(null) }
                    "soundEnabled" -> { com.openlauncher.app.util.SoundEngine.setEnabled(arg<Boolean>("on") == true); result.success(null) }
                    "eqPreset" -> { Eq.choose(prefs, arg<String>("name") ?: "Flat"); result.success(null) }
                    "speedVolume" -> { SpeedVolume.setLevel(this, arg<Int>("level") ?: 0); result.success(null) }
                    "settingsGet" -> result.success(car.settings())
                    "settingsSet" -> { car.setSetting(arg<String>("key") ?: "", call.argument<Any>("value")); result.success(null) }
                    "system" -> { car.system(arg<String>("what") ?: ""); result.success(null) }
                    "radioGet" -> result.success(car.radio())
                    "radioTune" -> {
                        TwBridge.radioTune(arg<Boolean>("am") == true, (arg<Number>("freq") ?: 98.5).toFloat())
                        result.success(null)
                    }
                    "radioSeek" -> { TwBridge.radioSeek(arg<Boolean>("up") != false); result.success(null) }
                    "radioStep" -> { TwBridge.radioStep(arg<Boolean>("up") != false); result.success(null) }
                    "radioResume" -> { TwBridge.radioResume(); result.success(null) }
                    "radioScan" -> { result.success(com.openlauncher.app.util.CarCore.radioScan()) }
                    "radioStop" -> { result.success(com.openlauncher.app.util.CarCore.radioStop()) }
                    "radioBand" -> {
                        val am = arg<Boolean>("am") == true
                        com.openlauncher.app.util.CarCore.radioSetBand(am)
                        TwBridge.radioBand(am)
                        result.success(null)
                    }
                    "radioLocal" -> { result.success(com.openlauncher.app.util.CarCore.radioToggleLocal()) }
                    "radioSave" -> { car.radioSave(arg<Int>("slot") ?: 0); result.success(null) }
                    // Dash buttons 1-6: tune the favourite saved in that place on the Radio page.
                    "radioPreset" -> {
                        val f = TwBridge.radioPresets().getOrNull(arg<Int>("slot") ?: -1)?.takeIf { it > 0f }
                        if (f != null) TwBridge.radioTune(f > 200f, f)
                        result.success(f != null)
                    }
                    "appsGet" -> scope.launch {
                        result.success(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { car.apps() })
                    }
                    "launch" -> {
                        val key = arg<String>("key") ?: ""
                        if (!car.launch(key)) openClassic(if (key == "settings") "settings" else "apps")
                        result.success(null)
                    }
                    "callsGet" -> scope.launch {
                        result.success(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { car.calls() })
                    }
                    "dial" -> { car.dial(arg<String>("number") ?: ""); result.success(null) }
                    "answer" -> {
                        TwBridge.answer()
                        if (com.openlauncher.app.util.PhoneLink.status.value.inCall) com.openlauncher.app.util.PhoneLink.function(this, com.openlauncher.app.util.PhoneLink.ANSWER)
                        result.success(null)
                    }
                    "hangUp" -> {
                        TwBridge.hangUp()
                        if (com.openlauncher.app.util.PhoneLink.status.value.inCall) com.openlauncher.app.util.PhoneLink.function(this, com.openlauncher.app.util.PhoneLink.HANG_UP)
                        result.success(null)
                    }
                    "mediaGet" -> result.success(car.media())
                    "mediaArt" -> result.success(car.mediaArt())
                    "media" -> { car.mediaCommand(arg<String>("cmd") ?: "", (arg<Number>("ms") ?: 0).toLong()); result.success(null) }
                    "sourcesGet" -> result.success(car.sources())
                    "source" -> { car.source(arg<String>("key") ?: ""); result.success(null) }
                    "btGet" -> result.success(car.bt())
                    "carGet" -> result.success(carInfo())
                    "updateGet" -> result.success(UpdateFlow.state(this))
                    "updateCheck" -> { UpdateFlow.check(); result.success(null) }
                    "updateRun" -> { UpdateFlow.run(applicationContext); result.success(null) }
                    "phoneLinkGet" -> {
                        val st = com.openlauncher.app.util.PhoneLink.status.value
                        result.success(mapOf(
                            "installed" to com.openlauncher.app.util.PhoneLink.installed(this),
                            "connected" to st.connected, "mode" to st.mode,
                            "auto" to com.openlauncher.app.util.PhoneLink.autoOpen(this),
                        ))
                    }
                    "phoneLinkOpen" -> result.success(com.openlauncher.app.util.PhoneLink.open(this))
                    "phoneGet" -> {
                        val ph = TwBridge.phone.value
                        result.success(mapOf(
                            "connected" to (TwBridge.phoneConnected.value == true || com.openlauncher.app.util.BtConnect.anyConnected()),
                            "name" to ph.name, "battery" to ph.battery, "signal" to ph.signal,
                            "music" to (TwBridge.btTrack.value?.title),
                        ))
                    }
                    "usbGet" -> result.success(UsbLibrary.get(arg<String>("mode") ?: "songs", arg<String>("filter")))
                    "usbPlay" -> {
                        UsbLibrary.play(arg<String>("mode") ?: "songs", arg<String>("filter"), (arg<String>("id") ?: "0").toLong())
                        result.success(null)
                    }
                    "usbRescan" -> { com.openlauncher.app.util.UsbMusic.rescan(); result.success(null) }
                    "carStream" -> { com.openlauncher.app.util.CarInfoLink.stream(arg<Boolean>("on") == true); result.success(null) }
                    "vendorGet" -> result.success(VendorApps.list(this))
                    "optimizeStatus" -> {
                        val st = com.openlauncher.app.util.RootOptimize.status(this)
                        result.success(mapOf("rooted" to st.rooted, "wifiAdb" to st.wifiAdbOn, "optimized" to st.optimized))
                    }
                    "optimizeRun" -> scope.launch {
                        result.success(com.openlauncher.app.util.RootOptimize.optimize(this@MmiFlutterActivity, arg<Boolean>("wifiAdb") == true))
                    }
                    "optimizeUndo" -> scope.launch {
                        result.success(com.openlauncher.app.util.RootOptimize.undo(this@MmiFlutterActivity))
                    }
                    "backupPartitions" -> scope.launch {
                        val dir = usbBackupDir()
                        if (dir == null) result.success(listOf("Insert a USB stick or SD card first."))
                        else result.success(com.openlauncher.app.util.RootOptimize.backupPartitions("$dir/mmi-firmware-backup"))
                    }
                    "healthGet" -> scope.launch {
                        result.success(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UnitHealth.get(this@MmiFlutterActivity) })
                    }
                    "vendorOpen" -> { VendorApps.open(this, arg<String>("pkg") ?: ""); result.success(null) }
                    "ampGet" -> result.success(mapOf(
                        "balance" to com.openlauncher.app.util.Amplifier.balance(this),
                        "fader" to com.openlauncher.app.util.Amplifier.fader(this),
                        "chip" to com.openlauncher.app.util.Amplifier.chip(this),
                    ))
                    "ampSet" -> {
                        arg<String>("chip")?.let { com.openlauncher.app.util.Amplifier.setChip(this, it) }
                        val ok = com.openlauncher.app.util.Amplifier.setBalanceFader(
                            this, arg<Int>("balance") ?: 9, arg<Int>("fader") ?: 9,
                        )
                        result.success(ok)
                    }
                    "aux" -> {
                        result.success(car.launch("pkg:com.tw.auxin") || runCatching {
                            startActivity(Intent().setClassName("com.tw.auxin", "com.tw.auxin.AuxInActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                        }.getOrDefault(false))
                    }
                    "devSend" -> {
                        val app = applicationContext
                        com.openlauncher.app.util.DevUpload.start(app) {
                            com.openlauncher.app.util.CarAppsExport.export(app) { s -> com.openlauncher.app.util.DevUpload.say(s) }
                        }
                        result.success(null)
                    }
                    "devStatus" -> result.success(mapOf(
                        "running" to com.openlauncher.app.util.DevUpload.running,
                        "status" to com.openlauncher.app.util.DevUpload.status.value,
                    ))
                    "weatherGet" -> result.success(Weather.get(this))
                    "btScan" -> { car.btScan(); result.success(null) }
                    "btStop" -> { car.btStop(); result.success(null) }
                    "btConnect" -> { car.btConnect(arg<String>("address") ?: ""); result.success(null) }
                    "open" -> {
                        val page = arg<String>("page")
                        if (page != null) openClassicPage(page) else openKey(arg<String>("key") ?: "home", car)
                        result.success(null)
                    }
                    "classic" -> {
                        prefs.edit().putBoolean("flutter_ui", false).apply()
                        openClassic("home")
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("mmi", e.message, null)
            }
        }
        // Calls: the call screen takes over as soon as a call starts and leaves when it ends.
        scope.launch {
            TwBridge.call.collect { ch.invokeMethod("call", car.callState()) }
        }
        // A pre-started engine is already on Home: open the asked-for page and the
        // start-up screen (once per power-on) now.
        if (prewarmed) {
            val page = intent.getStringExtra("flutter_page") ?: "home"
            if (page != "home") ch.invokeMethod("page", page)
            if (!splashShown && page == "home") { splashShown = true; ch.invokeMethod("splash", null) }
        }
        // Tell the screens when car data changes, instead of them asking all the time.
        fun push(topic: String, flow: kotlinx.coroutines.flow.Flow<Any?>, gapMs: Long = 0) = scope.launch {
            flow.distinctUntilChanged().drop(1).conflate().collect {
                ch.invokeMethod("changed", topic)
                if (gapMs > 0) kotlinx.coroutines.delay(gapMs)
            }
        }
        push("media", kotlinx.coroutines.flow.combine(com.openlauncher.app.service.MediaListenerService.nowPlaying, TwBridge.btTrack, com.openlauncher.app.util.UsbMusic.state) { a, b, u -> listOf(a?.title, a?.artist, a?.isPlaying, a?.albumArt?.generationId, b?.title, b?.playing, u.song?.id, u.playing) }, 300)
        push("radio", kotlinx.coroutines.flow.combine(TwBridge.radio, com.openlauncher.app.util.CarCore.radio) { a, b -> a to b }, 250)
        push("calls", TwBridge.call.map { it.state })
        push("phonelink", com.openlauncher.app.util.PhoneLink.status)
        push("phone", kotlinx.coroutines.flow.combine(TwBridge.phoneConnected, TwBridge.phone) { a, b -> a to b })
        push("car", kotlinx.coroutines.flow.combine(com.openlauncher.app.util.CarCore.data, com.openlauncher.app.util.CarInfoLink.info) { a, b -> a to b }, 1000)
        // Doors: a banner while any is open.
        scope.launch {
            com.openlauncher.app.util.CarCore.data.map { it.doors }.distinctUntilChanged().collect { d ->
                ch.invokeMethod("doors", com.openlauncher.app.util.CarCore.doorsText(d))
            }
        }
        // Climate: MMI's own pop-up when something changes (the vendor one is closed).
        scope.launch {
            com.openlauncher.app.util.Climate.state.drop(1).distinctUntilChanged { a, b -> a.changedAt == b.changedAt }.collect { c ->
                if (c.changedAt == 0L) return@collect
                ch.invokeMethod("climate", mapOf(
                    "left" to c.left?.toDouble(), "right" to c.right?.toDouble(),
                    "leftLo" to c.leftLo, "leftHi" to c.leftHi, "rightLo" to c.rightLo, "rightHi" to c.rightHi,
                    "fan" to c.fan, "ac" to c.ac, "auto" to c.auto, "recirc" to c.recirculation, "on" to c.on,
                    "dual" to c.dual, "front" to c.frontDefrost, "rear" to c.rearDefrost,
                ))
            }
        }
        // Speed camera ahead, on every page (also without the map).
        scope.launch {
            com.openlauncher.app.map.NavigationEngine.state
                .map { st ->
                    st.camera?.let { cam ->
                        mapOf(
                            "user" to cam.user,
                            "distance" to com.openlauncher.app.map.formatDistance(cam.distanceM, com.openlauncher.app.data.CarPrefs.get(this@MmiFlutterActivity).metric.value),
                            "limit" to cam.limit,
                            "over" to (cam.limit > 0 && st.speedKmh > cam.limit + 3),
                        )
                    }
                }
                .distinctUntilChanged()
                .collect { ch.invokeMethod("camera", it) }
        }
        // Volume pop-up when the music volume changes.
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        volumeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return
                ch.invokeMethod("volume", mapOf(
                    "level" to audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
                    "max" to audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
                ))
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, volumeReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        // Car knob and buttons, while the new screens are in front.
        scope.launch {
            com.openlauncher.app.util.CarCore.keys.collect { k ->
                if (resumed && k.press == 1) ch.invokeMethod("key", mapOf("code" to k.code))
            }
        }
    }

    // Already open: show the asked-for page (Home button, adb --es flutter_page …).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("flutter_page")?.let { channel?.invokeMethod("page", it) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        com.openlauncher.app.util.CarCore.screensInFront = true
        com.openlauncher.app.util.CarCore.vendorRadioPresent = (application as CarApp).vendorEnabled("com.tw.radio")
    }

    override fun onPause() {
        resumed = false
        com.openlauncher.app.util.CarCore.screensInFront = false
        super.onPause()
    }

    private var volumeReceiver: android.content.BroadcastReceiver? = null

    // Android is short of memory: the screens drop their decoded pictures.
    /** First writable USB stick / SD card, for the partition backup. */
    private fun usbBackupDir(): String? =
        java.io.File("/mnt/media_rw").listFiles()?.firstOrNull { it.canWrite() }?.path
            ?: java.io.File("/storage").listFiles()?.firstOrNull { it.name != "emulated" && it.name != "self" && it.canWrite() }?.path

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) channel?.invokeMethod("trim", null)
    }

    override fun onDestroy() {
        volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        super.onDestroy()
    }

    // The car's software turns every knob step into a fake next / previous-track key too:
    // drop those so turning the knob never skips songs.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_NEXT || event.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            val k = com.openlauncher.app.util.CarCore.lastKey.value
            if (k != null && k.code in setOf(5, 6, 19, 21) && System.currentTimeMillis() - k.at < 600) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Home tiles not drawn in Flutter: the unit's own apps, or the classic page. */
    private fun openKey(key: String, car: FlutterBridge) {
        val launched = when (key) {
            "browser" -> car.launch("browser")
            "weather" -> car.launch("weather")
            "video" -> car.launch("video")
            // Phone Link: CarPlay / Android Auto (the unit's ZLink engine, no vendor screens first).
            "phonelink" -> com.openlauncher.app.util.PhoneLink.open(this) || car.launch("pkg:" + (packageManager.getInstalledApplications(0)
                .firstOrNull { a -> listOf("zlink", "carlink", "autokit", "easyconnect", "phonelink").any { a.packageName.contains(it, true) } }?.packageName ?: ""))
            else -> false
        }
        if (!launched) openClassic(key)
    }

    /** A page that is not in Flutter yet: show it in the classic MMI. */
    private fun openClassic(key: String) = openClassicPage(CLASSIC_PAGES[key] ?: "HOME")

    private fun openClassicPage(page: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("mmi_page", page)
                .putExtra("classic", true)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    /**
     * The design's five sliders (Bass, Low mid, Mid, High mid, Treble) over the sound
     * engine's ten bands: each slider moves two bands; levels in millibels.
     */
    private object Eq {
        fun state(prefs: android.content.SharedPreferences): Map<String, Any> = mapOf(
            "levels" to List(5) { com.openlauncher.app.util.CarAudio.bandLevel(it) },
            "min" to -1200,
            "max" to 1200,
            "preset" to com.openlauncher.app.util.SoundEngine.preset,
            "speedVolume" to SpeedVolume.level,
            "available" to com.openlauncher.app.util.SoundEngine.available,
        )

        fun setSlider(prefs: android.content.SharedPreferences, i: Int, level: Int) =
            com.openlauncher.app.util.CarAudio.setBandLevel(i.coerceIn(0, 4), level)

        fun choose(prefs: android.content.SharedPreferences, name: String) =
            com.openlauncher.app.util.SoundEngine.usePreset(name)
    }

    companion object {
        /** True while the new screens are in front (the classic shell then ignores the car's keys). */
        @Volatile var resumed = false
            private set
        private var splashShown = false

        private val CLASSIC_PAGES = mapOf(
            "home" to "HOME", "radio" to "RADIO", "music" to "MUSIC", "phone" to "PHONE",
            "nav" to "NAV", "phonelink" to "BT_HUB", "carinfo" to "DASHBOARD", "settings" to "SETTINGS",
            "help" to "SYSTEM_SET", "video" to "MUSIC", "eq" to "SOUND", "weather" to "HOME",
            "apps" to "APPS", "browser" to "APPS",
        )

        fun isOn(context: Context) =
            context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).getBoolean("flutter_ui", true)

        fun start(context: Context, page: String = "home") {
            context.startActivity(
                Intent(context, MmiFlutterActivity::class.java)
                    .putExtra("flutter_page", page)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }
}

/**
 * One Flutter engine for MMI's screens, started in the background at power-on so
 * Home appears instantly. Graphics mode: the default renderer, or OpenGL ES when
 * Settings › System › "OpenGL ES graphics" is on (for GPUs whose Vulkan is slow).
 */
internal object FlutterEngines {
    private const val ID = "mmi"
    val running: Boolean get() = io.flutter.embedding.engine.FlutterEngineCache.getInstance().contains(ID)

    fun glesOnly(c: Context) = c.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).getBoolean("gles", false)

    fun get(c: Context): FlutterEngine {
        val cache = io.flutter.embedding.engine.FlutterEngineCache.getInstance()
        cache.get(ID)?.let { return it }
        val args = if (glesOnly(c)) arrayOf("--impeller-backend=opengles") else arrayOf<String>()
        val engine = FlutterEngine(c.applicationContext, args)
        cache.put(ID, engine)
        return engine
    }

    /** Starts the screens on Home in the background (only when the new MMI is on). */
    fun warm(c: Context) {
        if (running || !MmiFlutterActivity.isOn(c)) return
        val engine = get(c)
        engine.navigationChannel.setInitialRoute("/home")
        engine.dartExecutor.executeDartEntrypoint(io.flutter.embedding.engine.dart.DartExecutor.DartEntrypoint.createDefault())
    }
}
