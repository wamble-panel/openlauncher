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
import kotlin.math.roundToInt

/**
 * The new MMI screens, drawn by Flutter (mmi_ui). Car access stays in Kotlin: the
 * Flutter side asks for it over the "mmi" channel (sounds, equaliser, speed volume),
 * and pages not yet rebuilt in Flutter open in the classic MMI.
 */
class MmiFlutterActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Starting page, e.g. "/sound" (adb: --es flutter_page sound).
    override fun getInitialRoute(): String {
        val page = intent.getStringExtra("flutter_page") ?: "home"
        // The start-up screen once per power-on (first start of the app process).
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
                    "radioSave" -> { car.radioSave(arg<Int>("slot") ?: 0); result.success(null) }
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
                    "answer" -> { TwBridge.answer(); result.success(null) }
                    "hangUp" -> { TwBridge.hangUp(); result.success(null) }
                    "mediaGet" -> result.success(car.media())
                    "mediaArt" -> result.success(car.mediaArt())
                    "media" -> { car.mediaCommand(arg<String>("cmd") ?: "", (arg<Number>("ms") ?: 0).toLong()); result.success(null) }
                    "sourcesGet" -> result.success(car.sources())
                    "source" -> { car.source(arg<String>("key") ?: ""); result.success(null) }
                    "btGet" -> result.success(car.bt())
                    "carGet" -> result.success(carInfo())
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
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private var volumeReceiver: android.content.BroadcastReceiver? = null

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
            "phonelink" -> car.launch("pkg:" + (packageManager.getInstalledApplications(0)
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

    /** The five equaliser sliders of the design, spread over however many bands the unit has. */
    private object Eq {
        private val VOICE = intArrayOf(-300, 0, 400, 300, -200)

        private fun bandOf(i: Int): Int = ((i * (CarAudio.bandCount - 1)) / 4f).roundToInt()

        fun state(prefs: android.content.SharedPreferences): Map<String, Any> {
            val n = CarAudio.bandCount
            val range = CarAudio.levelRange
            return mapOf(
                "levels" to List(5) { if (n == 0) 0 else CarAudio.bandLevel(bandOf(it)) },
                "min" to range.first,
                "max" to range.last,
                "preset" to (prefs.getString("eq_preset", "Custom") ?: "Custom"),
                "speedVolume" to SpeedVolume.level,
                "available" to (CarAudio.available && n > 0),
            )
        }

        fun setSlider(prefs: android.content.SharedPreferences, i: Int, level: Int) {
            if (CarAudio.bandCount == 0) return
            if (!CarAudio.enabled) CarAudio.setEnabled(true)
            CarAudio.setBandLevel(bandOf(i), level.coerceIn(CarAudio.levelRange))
            prefs.edit().putInt("eq_custom_$i", level).putString("eq_preset", "Custom").apply()
        }

        private fun apply(levels: IntArray) {
            val n = CarAudio.bandCount
            if (!CarAudio.enabled) CarAudio.setEnabled(true)
            for (b in 0 until n) {
                // Bands between two sliders take the nearer slider's level.
                val nearest = (0 until 5).minBy { abs(bandOf(it) - b) }
                CarAudio.setBandLevel(b, levels[nearest].coerceIn(CarAudio.levelRange))
            }
        }

        fun choose(prefs: android.content.SharedPreferences, name: String) {
            if (CarAudio.bandCount == 0) return
            prefs.edit().putString("eq_preset", name).apply()
            when (name) {
                "Flat" -> CarAudio.flat()
                "Voice" -> apply(VOICE)
                "Custom" -> apply(IntArray(5) { prefs.getInt("eq_custom_$it", 0) })
                else -> {
                    val i = CarAudio.presets.indexOfFirst { it.equals(name, ignoreCase = true) }
                    if (i >= 0) { CarAudio.setEnabled(true); CarAudio.usePreset(i) } else CarAudio.flat()
                }
            }
        }
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
            context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).getBoolean("flutter_ui", false)

        fun start(context: Context, page: String = "home") {
            context.startActivity(
                Intent(context, MmiFlutterActivity::class.java)
                    .putExtra("flutter_page", page)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }
}
