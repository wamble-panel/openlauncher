package com.openlauncher.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.R
import com.openlauncher.app.util.CarAudio
import kotlin.math.roundToInt

// Settings · Sound, from the approved design: frosted panels, 5 vertical equaliser
// sliders, presets, balance / fader and speed-dependent volume. Sizes = design px ÷ 1.24.

/** Frosted panel: faint light gradient, thin outline brighter at the top, 6 dp corners. */
internal fun Modifier.mibGlass(): Modifier {
    val shape = RoundedCornerShape(6.dp)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0x1CFFFFFF), Color(0x0AFFFFFF))))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x22FFFFFF))), shape)
}

private val BANDS = listOf("Bass", "Low mid", "Mid", "High mid", "Treble")
private val PRESETS = listOf("Flat", "Rock", "Pop", "Jazz", "Voice", "Custom")
// Voice: a speech curve (millibels per visible band).
private val VOICE = intArrayOf(-300, 0, 400, 300, -200)

@Composable
internal fun MibSoundPage() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("mmi_prefs", android.content.Context.MODE_PRIVATE) }
    if (!CarAudio.available || CarAudio.bandCount == 0) {
        MibMessage("The equaliser needs media access. Allow it in Settings · System & updates · Permissions.")
        return
    }
    val n = CarAudio.bandCount
    val range = CarAudio.levelRange
    // The five sliders map onto the unit's bands (evenly spread when it has more or fewer than five).
    val bandOf = remember(n) { IntArray(5) { i -> ((i * (n - 1)) / 4f).roundToInt() } }
    var revision by remember { mutableIntStateOf(0) }
    var preset by remember { mutableStateOf(prefs.getString("eq_preset", "Custom") ?: "Custom") }

    fun applyLevels(levels: IntArray) {
        if (!CarAudio.enabled) CarAudio.setEnabled(true)
        for (b in 0 until n) {
            // Bands between two sliders take the nearer slider's level.
            val nearest = (0 until 5).minBy { kotlin.math.abs(bandOf[it] - b) }
            CarAudio.setBandLevel(b, levels[nearest].coerceIn(range))
        }
        revision++
    }
    fun choose(name: String) {
        preset = name
        prefs.edit().putString("eq_preset", name).apply()
        when (name) {
            "Flat" -> { CarAudio.flat(); revision++ }
            "Voice" -> applyLevels(VOICE)
            "Custom" -> applyLevels(IntArray(5) { prefs.getInt("eq_custom_$it", 0) })
            else -> {
                val i = CarAudio.presets.indexOfFirst { it.equals(name, ignoreCase = true) }
                if (i >= 0) { CarAudio.setEnabled(true); CarAudio.usePreset(i); revision++ } else CarAudio.flat()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 12.dp, top = 26.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(Modifier.fillMaxWidth().height(333.dp), horizontalArrangement = Arrangement.spacedBy(21.dp)) {
            // Equaliser.
            Column(Modifier.weight(654f).fillMaxHeight().mibGlass().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)) {
                Text("Equaliser", color = Color.White, fontFamily = MmiFont, fontSize = 23.sp)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BANDS.forEachIndexed { i, name ->
                        val b = bandOf[i]
                        var level by remember(b, revision) { mutableIntStateOf(CarAudio.bandLevel(b)) }
                        Column(Modifier.width(100.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%+d".format((level / 100f).roundToInt()), color = Color.White, fontFamily = MmiFont, fontSize = 19.sp)
                            Spacer(Modifier.height(8.dp))
                            VerticalSlider(
                                (level - range.first) / (range.last - range.first).toFloat(),
                                { v ->
                                    level = (range.first + v * (range.last - range.first)).roundToInt()
                                    if (!CarAudio.enabled) CarAudio.setEnabled(true)
                                    CarAudio.setBandLevel(b, level)
                                    // Any slider change becomes the Custom preset.
                                    prefs.edit().putInt("eq_custom_$i", level).putString("eq_preset", "Custom").apply()
                                    preset = "Custom"
                                },
                                Modifier.weight(1f).width(60.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(name, color = Color.White, fontFamily = MmiFont, fontSize = 19.sp)
                        }
                    }
                }
            }
            // Presets.
            Column(Modifier.weight(424f).fillMaxHeight().mibGlass().padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 26.dp)) {
                Text("Presets", color = Color.White, fontFamily = MmiFont, fontSize = 23.sp)
                Spacer(Modifier.height(16.dp))
                PRESETS.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                        row.forEach { name ->
                            val on = name == preset
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                                    .background(if (on) Brush.verticalGradient(listOf(Color(0xFF8C8C8C), Color(0xFF5E5E5E))) else Brush.verticalGradient(listOf(Color(0xFF050505), Color(0xFF050505))))
                                    .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(4.dp))
                                    .pressable { choose(name) },
                                contentAlignment = Alignment.Center,
                            ) { Text(name, color = Color.White, fontFamily = MmiFont, fontSize = 24.sp) }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(204.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            // Balance / fader: shown as set by the car's amplifier (MMI cannot move it yet).
            Box(Modifier.weight(428f).fillMaxHeight().mibGlass().padding(start = 20.dp, top = 14.dp)) {
                Text("Balance / fader", color = Color.White, fontFamily = MmiFont, fontSize = 23.sp)
                Row(Modifier.align(Alignment.Center).padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    MmiLineIcon(MmiLine.CHEVRON_LEFT, Color.White.copy(alpha = 0.5f), Modifier.size(30.dp))
                    Spacer(Modifier.width(16.dp))
                    Image(painterResource(R.drawable.sound_seats), "Seats", modifier = Modifier.size(137.dp, 115.dp))
                    Spacer(Modifier.width(16.dp))
                    MmiLineIcon(MmiLine.ARROW, Color.White.copy(alpha = 0.5f), Modifier.size(30.dp))
                }
            }
            // Speed-dependent volume (MMI raises the music a little as speed rises).
            Column(Modifier.weight(649f).fillMaxHeight().mibGlass().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                Text("Speed-dependent volume", color = Color.White, fontFamily = MmiFont, fontSize = 23.sp)
                Spacer(Modifier.height(36.dp))
                var sv by remember { mutableIntStateOf(com.openlauncher.app.util.SpeedVolume.level) }
                MibSegmented(listOf("Off", "Low", "Mid", "High"), sv, { i ->
                    sv = i
                    com.openlauncher.app.util.SpeedVolume.setLevel(context, i)
                }, Modifier.height(78.dp))
            }
        }
    }
}

/** Vertical slider from the design: thin dark track, white fill from the bottom, round silver knob. */
@Composable
private fun VerticalSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val current by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { current((1f - it.y / size.height).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectVerticalDragGestures { change, _ -> current((1f - change.position.y / size.height).coerceIn(0f, 1f)) } },
    ) {
        val cx = size.width / 2
        val top = 14.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        val y = bottom - (bottom - top) * value.coerceIn(0f, 1f)
        drawLine(Color(0xFF3C3C3C), Offset(cx, top), Offset(cx, bottom), 5.dp.toPx())
        drawLine(Color.White, Offset(cx, y), Offset(cx, bottom), 5.dp.toPx())
        val r = 14.dp.toPx()
        drawCircle(Color(0x66000000), r + 2.dp.toPx(), Offset(cx, y + 2.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF2F2F2), Color(0xFF9A9A9A)), Offset(cx - r * 0.3f, y - r * 0.3f), r * 1.4f), r, Offset(cx, y))
    }
}
