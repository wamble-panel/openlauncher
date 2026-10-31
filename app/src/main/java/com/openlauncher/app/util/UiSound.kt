package com.openlauncher.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.openlauncher.app.R

/**
 * Short interface sounds: a glass tap for touches, a soft detent tick for every
 * knob step and a small chime when a page opens. SoundPool keeps them decoded in
 * memory, so they play with almost no delay; they mix over music at a low level.
 */
object UiSound {
    enum class Kind { TAP, TICK, OPEN }

    private var pool: SoundPool? = null
    private val ids = IntArray(Kind.entries.size)
    private var app: Context? = null
    @Volatile var enabled = true
        private set
    private var lastTick = 0L

    fun init(context: Context) {
        if (pool != null) return
        val ctx = context.applicationContext
        app = ctx
        enabled = ctx.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).getBoolean("ui_sounds", true)
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        ids[Kind.TAP.ordinal] = p.load(ctx, R.raw.ui_tap, 1)
        ids[Kind.TICK.ordinal] = p.load(ctx, R.raw.ui_tick, 1)
        ids[Kind.OPEN.ordinal] = p.load(ctx, R.raw.ui_open, 1)
        pool = p
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        app?.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE)?.edit()?.putBoolean("ui_sounds", on)?.apply()
        if (on) play(Kind.TAP)
    }

    fun play(kind: Kind) {
        if (!enabled) return
        val p = pool ?: return
        val volume = when (kind) {
            Kind.TAP -> 0.55f
            Kind.TICK -> {
                // A fast knob spin would otherwise turn into a buzz.
                val now = SystemClock.uptimeMillis()
                if (now - lastTick < 35) return
                lastTick = now
                0.5f
            }
            Kind.OPEN -> 0.45f
        }
        p.play(ids[kind.ordinal], volume, volume, 1, 0, 1f)
    }

    fun tap() = play(Kind.TAP)
    fun tick() = play(Kind.TICK)
    fun open() = play(Kind.OPEN)
}
