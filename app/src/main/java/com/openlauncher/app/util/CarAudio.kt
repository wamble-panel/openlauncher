package com.openlauncher.app.util

import android.content.Context

/**
 * The classic Sound page's view of [SoundEngine]: five sliders (Bass, Low mid, Mid,
 * High mid, Treble), each moving two of the engine's ten bands, in millibels.
 */
object CarAudio {
    val available: Boolean get() = SoundEngine.available
    val enabled: Boolean get() = SoundEngine.enabled

    fun init(context: Context) = SoundEngine.init(context)

    val bandCount: Int get() = if (SoundEngine.available) 5 else 0
    val levelRange: IntRange get() = -1200..1200

    fun bandLevel(band: Int): Int =
        ((SoundEngine.gain(band * 2) + SoundEngine.gain(band * 2 + 1)) / 2f * 100).toInt()

    fun setBandLevel(band: Int, millibels: Int) {
        val db = millibels.coerceIn(levelRange) / 100f
        SoundEngine.setGain(band * 2, db)
        SoundEngine.setGain(band * 2 + 1, db)
    }

    val presets: List<String> get() = SoundEngine.PRESETS.map { it.name }
    fun usePreset(index: Int) { presets.getOrNull(index)?.let { SoundEngine.usePreset(it) } }
    fun flat() = SoundEngine.usePreset("Flat")
    fun setEnabled(on: Boolean) = SoundEngine.setEnabled(on)
}
