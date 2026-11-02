package com.openlauncher.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openlauncher.app.util.CarAudio
import com.openlauncher.app.util.SpeedVolume
import com.openlauncher.app.util.UiSound
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.math.abs
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
    override fun getInitialRoute(): String = "/" + (intent.getStringExtra("flutter_page") ?: "home")

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        CarAudio.init(this)
        UiSound.init(this)
        SpeedVolume.init(this)
        val prefs = getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "mmi").setMethodCallHandler { call, result ->
            when (call.method) {
                "sound" -> {
                    when (call.argument<String>("kind")) {
                        "tick" -> UiSound.tick()
                        "open" -> UiSound.open()
                        else -> UiSound.tap()
                    }
                    result.success(null)
                }
                "eqGet" -> result.success(Eq.state(prefs))
                "eqSet" -> {
                    Eq.setSlider(prefs, call.argument<Int>("band") ?: 0, call.argument<Int>("level") ?: 0)
                    result.success(null)
                }
                "eqPreset" -> {
                    Eq.choose(prefs, call.argument<String>("name") ?: "Flat")
                    result.success(null)
                }
                "speedVolume" -> {
                    SpeedVolume.setLevel(this, call.argument<Int>("level") ?: 0)
                    result.success(null)
                }
                "open" -> {
                    openClassic(call.argument<String>("key") ?: "home")
                    result.success(null)
                }
                "classic" -> {
                    prefs.edit().putBoolean("flutter_ui", false).apply()
                    openClassic("home")
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    /** A page that is not in Flutter yet: show it in the classic MMI. */
    private fun openClassic(key: String) {
        val page = CLASSIC_PAGES[key] ?: "HOME"
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
