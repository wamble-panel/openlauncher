package com.openlauncher.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MMI's sound: a 10-band equaliser on everything the unit plays (radio, Bluetooth,
 * apps), on the output mix.
 *
 * - Engine: Android's DynamicsProcessing (10 bands, ±12 dB, plus a limiter) when the
 *   unit allows it, else the unit's own Equalizer with the 10 bands mapped onto its bands.
 * - Headroom: boosting a band lowers the input level by part of the boost and the limiter
 *   catches peaks, so boosts get louder in that band instead of distorting.
 * - Loudness: at low volume the ear hears less bass and treble; with Loudness on, MMI
 *   adds them back, more the quieter it plays, and none at high volume.
 * Settings are saved and applied again at start.
 */
object SoundEngine {
    /** Band centres (Hz), ISO octaves. */
    val CENTRES = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    // Upper edge of each band: halfway (on a log scale) to the next centre.
    private val CUTOFFS = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)
    const val MAX_DB = 12f

    /** Preset curves (dB per band) for a car: each with what it is for. */
    data class Preset(val name: String, val about: String, val gains: FloatArray)

    val PRESETS = listOf(
        Preset("Flat", "Nothing changed: the recording as it is.", FloatArray(10)),
        Preset("Natural", "Made for a car cabin: tames the boom cars add around 125–250 Hz and lifts voice clarity a little.",
            floatArrayOf(1f, 1f, -1.5f, -2f, -0.5f, 0f, 1.5f, 1.5f, 1f, 0.5f)),
        Preset("Bass", "Deeper, fuller low end without muddying voices.",
            floatArrayOf(5f, 5f, 3f, 0.5f, -0.5f, 0f, 0f, 0.5f, 1f, 1f)),
        Preset("Rock", "Punchy drums and bass, forward guitars, crisp cymbals.",
            floatArrayOf(3f, 3.5f, 1.5f, -1f, -1.5f, 0.5f, 2f, 3f, 3f, 2f)),
        Preset("Pop", "Clear vocals on top of a solid beat.",
            floatArrayOf(2f, 2.5f, 1f, -0.5f, 0f, 1.5f, 2.5f, 2f, 1.5f, 1.5f)),
        Preset("Jazz", "Warm and open: smooth bass, natural instruments, soft highs.",
            floatArrayOf(2f, 2f, 1f, 0.5f, -1f, -0.5f, 0.5f, 1.5f, 2f, 2.5f)),
        Preset("Classical", "Wide and natural, with a little air for strings.",
            floatArrayOf(2f, 1.5f, 0.5f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f)),
        Preset("Voice", "News, podcasts and calls: less rumble, clearer speech over road noise.",
            floatArrayOf(-6f, -4f, -2f, 0f, 1f, 2.5f, 3.5f, 3f, 1f, -1f)),
        Preset("Night", "Gentle for late drives: softer highs, no booming bass.",
            floatArrayOf(-1f, 0f, 0.5f, 0.5f, 0.5f, 0f, -0.5f, -1.5f, -2.5f, -3f)),
    )

    // What the ear adds back at low volume (dB at full effect).
    private val LOUDNESS = floatArrayOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 1f, 2.5f, 3f)

    private var app: Context? = null
    private var dp: DynamicsProcessing? = null
    private var eq: Equalizer? = null
    private val gains = FloatArray(10)
    var preset: String = "Flat"
        private set
    var loudness = true
        private set
    var enabled = true
        private set

    /** "dynamics" (10 real bands), "equalizer" (mapped onto the unit's bands) or "none". */
    val engine: String get() = when { dp != null -> "dynamics"; eq != null -> "equalizer"; else -> "none" }
    val available: Boolean get() = dp != null || eq != null

    fun gain(band: Int): Float = gains[band]

    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        val p = prefs(ctx)
        p.getString("gains", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.forEachIndexed { i, g -> if (i < 10) gains[i] = g }
        preset = p.getString("preset", "Natural") ?: "Natural"
        if (p.getString("gains", null) == null) PRESETS.firstOrNull { it.name == preset }?.gains?.copyInto(gains)
        loudness = p.getBoolean("loudness", true)
        enabled = p.getBoolean("enabled", true)
        dp = runCatching { createDynamics() }.getOrNull()
        if (dp == null) eq = runCatching { Equalizer(1000, 0) }.getOrNull()
        TwBridge.log("SoundEngine: $engine")
        // Loudness follows the volume.
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (loudness) apply() }
            }, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
        apply()
    }

    private fun createDynamics(): DynamicsProcessing {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, 10, false, 0, false, 0, true,
        ).setPreferredFrameDuration(20f) // bigger audio blocks: less CPU per second on the A53
        val pre = DynamicsProcessing.Eq(true, true, 10)
        for (i in 0 until 10) pre.setBand(i, DynamicsProcessing.EqBand(true, CUTOFFS[i], 0f))
        cfg.setPreEqAllChannelsTo(pre)
        // Safety net: nothing goes above -1 dBFS, so boosts never clip.
        cfg.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        return DynamicsProcessing(1000, 0, cfg.build()).apply { enabled = true }
    }

    /** 0 at full volume … 1 at the quietest, for loudness. */
    private fun quietness(): Float {
        val a = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0f
        val maxV = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val v = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Full effect below a quarter of the volume, none above three quarters.
        return ((0.75f - v.toFloat() / maxV) / 0.5f).coerceIn(0f, 1f)
    }

    /** What is actually applied: your bands plus loudness. */
    fun effective(): FloatArray {
        val q = if (loudness) quietness() else 0f
        return FloatArray(10) { (gains[it] + LOUDNESS[it] * q).coerceIn(-MAX_DB - 6, MAX_DB + 6) }
    }

    private fun apply() {
        val g = if (enabled) effective() else FloatArray(10)
        dp?.let { d ->
            runCatching {
                for (i in 0 until 10) d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, CUTOFFS[i], g[i]))
                // Headroom: take back half of the biggest boost before the EQ; the limiter does the rest.
                d.setInputGainAllChannelsTo(-max(0f, g.maxOrNull() ?: 0f) * 0.5f)
                d.enabled = enabled
            }
        }
        eq?.let { e ->
            runCatching {
                val range = e.bandLevelRange
                for (b in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(b.toShort()) / 1000f
                    val mb = (atFrequency(g, hz) * 100).roundToInt().coerceIn(range[0].toInt(), range[1].toInt())
                    e.setBandLevel(b.toShort(), mb.toShort())
                }
                e.enabled = enabled
            }
        }
    }

    /** The 10-band curve read at any frequency (log interpolation), for the unit's own bands. */
    private fun atFrequency(g: FloatArray, hz: Float): Float {
        val x = ln(hz.coerceIn(31f, 16000f) / 31f) / ln(2f)   // octaves above 31 Hz: 0…9
        val i = x.toInt().coerceIn(0, 8)
        val t = (x - i).coerceIn(0f, 1f)
        return g[i] * (1 - t) + g[i + 1] * t
    }

    fun setGain(band: Int, db: Float) {
        gains[band.coerceIn(0, 9)] = ((db * 2).roundToInt() / 2f).coerceIn(-MAX_DB, MAX_DB)
        preset = "Custom"
        if (!enabled) enabled = true
        apply(); save()
    }

    fun usePreset(name: String) {
        if (name == "Custom") {
            prefs(app ?: return).getString("custom", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.forEachIndexed { i, g -> if (i < 10) gains[i] = g }
        } else {
            val p = PRESETS.firstOrNull { it.name.equals(name, true) } ?: return
            p.gains.copyInto(gains)
        }
        preset = name
        enabled = true
        apply(); save()
    }

    fun setLoudness(on: Boolean) { loudness = on; apply(); save() }
    fun setEnabled(on: Boolean) { enabled = on; apply(); save() }

    private fun prefs(c: Context) = c.getSharedPreferences("mmi_sound", Context.MODE_PRIVATE)

    private fun save() {
        val c = app ?: return
        val e = prefs(c).edit()
            .putString("gains", gains.joinToString(","))
            .putString("preset", preset)
            .putBoolean("loudness", loudness)
            .putBoolean("enabled", enabled)
        if (preset == "Custom") e.putString("custom", gains.joinToString(","))
        e.apply()
    }
}
