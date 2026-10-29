package com.openlauncher.app.util

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/**
 * System-wide equalizer and bass boost on the global output mix (session 0),
 * so they shape Bluetooth music, radio and everything else. Settings are saved
 * and re-applied each time the app starts. Some units block global effects;
 * then [available] is false and the Sound page offers the vendor EQ instead.
 */
object CarAudio {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var prefsContext: Context? = null

    val available: Boolean get() = eq != null
    var enabled: Boolean = true
        private set

    fun init(context: Context) {
        if (prefsContext != null) return
        val app = context.applicationContext
        prefsContext = app
        val prefs = app.getSharedPreferences("mmi_audio", Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", true)
        eq = runCatching { Equalizer(1000, 0) }.getOrNull()
        bass = runCatching { BassBoost(1000, 0) }.getOrNull()
        eq?.let { e ->
            prefs.getString("levels", null)?.split(',')?.mapNotNull { it.toShortOrNull() }?.forEachIndexed { i, level ->
                if (i < e.numberOfBands) runCatching { e.setBandLevel(i.toShort(), level) }
            }
            runCatching { e.enabled = enabled }
        }
        bass?.let { b ->
            runCatching {
                if (b.strengthSupported) b.setStrength(prefs.getInt("bass", 0).toShort())
                b.enabled = enabled
            }
        }
    }

    val bandCount: Int get() = eq?.numberOfBands?.toInt() ?: 0
    val levelRange: IntRange get() = eq?.bandLevelRange?.let { it[0].toInt()..it[1].toInt() } ?: (-1500..1500)

    fun bandLabel(band: Int): String {
        val hz = (eq?.getCenterFreq(band.toShort()) ?: 0) / 1000
        return if (hz >= 1000) "${hz / 1000}k" else "$hz"
    }

    fun bandLevel(band: Int): Int = runCatching { eq?.getBandLevel(band.toShort())?.toInt() }.getOrNull() ?: 0

    fun setBandLevel(band: Int, millibels: Int) {
        val e = eq ?: return
        runCatching { e.setBandLevel(band.toShort(), millibels.coerceIn(levelRange).toShort()) }
        save()
    }

    val presets: List<String>
        get() = eq?.let { e -> (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } }.orEmpty()

    fun usePreset(index: Int) {
        runCatching { eq?.usePreset(index.toShort()) }
        save()
    }

    fun flat() {
        for (b in 0 until bandCount) runCatching { eq?.setBandLevel(b.toShort(), 0) }
        save()
    }

    val bassSupported: Boolean get() = runCatching { bass?.strengthSupported == true }.getOrDefault(false)
    /** 0…1000 */
    val bassStrength: Int get() = runCatching { bass?.roundedStrength?.toInt() }.getOrNull() ?: 0

    fun setBass(strength: Int) {
        runCatching { bass?.setStrength(strength.coerceIn(0, 1000).toShort()) }
        save()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        runCatching { eq?.enabled = on }
        runCatching { bass?.enabled = on }
        save()
    }

    private fun save() {
        val ctx = prefsContext ?: return
        val levels = (0 until bandCount).joinToString(",") { bandLevel(it).toString() }
        ctx.getSharedPreferences("mmi_audio", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putString("levels", levels)
            .putInt("bass", bassStrength)
            .apply()
    }
}
