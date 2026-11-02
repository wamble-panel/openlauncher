package com.openlauncher.app.util

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Speed-dependent volume: as the car goes faster the music gets a little louder to stay
 * above road noise, and comes back down when it slows. Off / Low / Mid / High add up to
 * 0 / 2 / 3 / 4 volume steps, one step per 30 km/h. Only MMI's own boost is ever undone,
 * so a volume the driver set by hand is kept.
 */
object SpeedVolume {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var app: Context? = null
    @Volatile var level = 0
        private set
    private var applied = 0

    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        level = ctx.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).getInt("speed_volume", 0)
        scope.launch {
            CarLocation.get(ctx).location.collect { loc ->
                val kmh = (loc?.speedMps ?: 0f) * 3.6f
                val max = intArrayOf(0, 2, 3, 4)[level]
                apply((kmh / 30f).toInt().coerceIn(0, max))
            }
        }
    }

    fun setLevel(context: Context, value: Int) {
        level = value.coerceIn(0, 3)
        context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).edit().putInt("speed_volume", level).apply()
        if (level == 0) apply(0)
    }

    private fun apply(boost: Int) {
        val ctx = app ?: return
        val delta = boost - applied
        if (delta == 0) return
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(delta)) { runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) } }
        applied = boost
    }
}
