package com.openlauncher.app.ui.screen

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openlauncher.app.data.CarPrefs
import com.openlauncher.app.map.MapData
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.util.CarAudio
import kotlin.math.roundToInt

// ── Shared controls ──────────────────────────────────────────────────────────

/** MMI slider: thin track, cyan fill, chrome knob. Tap or drag; [value] is 0…1. */
@Composable
internal fun MmiSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier, vertical: Boolean = false) {
    val current by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(vertical) {
                fun at(p: Offset) = if (vertical) 1f - p.y / size.height else p.x / size.width
                detectTapGestures { current(at(it).coerceIn(0f, 1f)) }
            }
            .pointerInput(vertical) {
                fun at(p: Offset) = if (vertical) 1f - p.y / size.height else p.x / size.width
                detectDragGestures { change, _ -> current(at(change.position).coerceIn(0f, 1f)) }
            },
    ) {
        val track = 4.dp.toPx()
        val v = value.coerceIn(0f, 1f)
        val start = if (vertical) Offset(size.width / 2, size.height) else Offset(0f, size.height / 2)
        val end = if (vertical) Offset(size.width / 2, 0f) else Offset(size.width, size.height / 2)
        val knob = if (vertical) Offset(size.width / 2, size.height * (1f - v)) else Offset(size.width * v, size.height / 2)
        drawLine(Color(0xFF2A2E34), start, end, track, StrokeCap.Round)
        drawLine(Brush.linearGradient(listOf(Color(0xFF1C8FB8), MmiCyan), start, knob), start, knob, track, StrokeCap.Round)
        drawCircle(Brush.linearGradient(ChromeRim, knob - Offset(10f, 10f), knob + Offset(10f, 10f)), 9.dp.toPx(), knob)
    }
}

@Composable
private fun MmiSwitch(on: Boolean) {
    val x by animateFloatAsState(if (on) 1f else 0f, tween(180), label = "switch")
    val track by animateColorAsState(if (on) MmiCyan.copy(alpha = 0.55f) else Color(0xFF2A2E34), label = "switch_track")
    Box(Modifier.size(width = 52.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
        Box(
            Modifier.padding(3.dp).offset(x = 24.dp * x).size(22.dp).clip(RoundedCornerShape(11.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFF2F4F6), Color(0xFF9AA0A8)))),
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).let { if (onClick != null) it.pressable(onClick) else it }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MmiInk, fontFamily = MmiFont, fontSize = 19.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value != null) Text(value, color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(12.dp))
            MmiLineIcon(MmiLine.ARROW, MmiDim, Modifier.size(18.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E2125)))
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) =
    SettingRow(label, onClick = { onToggle(!on) }) { MmiSwitch(on) }

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = MmiCyan, fontFamily = MmiFont, fontSize = 13.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun SettingsColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp), content = content)
}

// ── Display ──────────────────────────────────────────────────────────────────

@Composable
internal fun MmiDisplayPage() {
    val context = LocalContext.current
    val prefs = remember(context) { CarPrefs.get(context) }
    val clock24 by prefs.clock24.collectAsStateWithLifecycle()
    var canWrite by remember { mutableStateOf(canWriteSettings(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canWrite = canWriteSettings(context) }
    var brightness by remember {
        mutableFloatStateOf(runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f))
    }

    SettingsColumn(Modifier.padding(horizontal = 48.dp)) {
        SectionTitle("Screen brightness")
        if (canWrite) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                MmiLineIcon(MmiLine.MINUS, MmiDim, Modifier.size(18.dp))
                Spacer(Modifier.width(16.dp))
                MmiSlider(brightness, { v ->
                    brightness = v
                    runCatching {
                        val cr = context.contentResolver
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v * 255).roundToInt().coerceIn(5, 255))
                    }
                }, Modifier.weight(1f).height(40.dp))
                Spacer(Modifier.width(16.dp))
                MmiLineIcon(MmiLine.PLUS, MmiDim, Modifier.size(18.dp))
                Spacer(Modifier.width(16.dp))
                Text("${(brightness * 100).roundToInt()}%", color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp, modifier = Modifier.width(56.dp))
            }
        } else {
            SettingRow("Allow brightness control", "one-time permission", onClick = {
                context.launchFirst(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
                    Intent(Settings.ACTION_DISPLAY_SETTINGS),
                )
            })
        }
        SectionTitle("Clock")
        ToggleRow("24-hour clock", clock24, prefs::setClock24)
        SectionTitle("More")
        SettingRow("Android display settings", onClick = { context.launchFirst(Intent(Settings.ACTION_DISPLAY_SETTINGS)) })
    }
}

private fun canWriteSettings(context: Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)

// ── Sound ────────────────────────────────────────────────────────────────────

private data class VolumeStream(val label: String, val stream: Int)

private val VOLUME_STREAMS = listOf(
    VolumeStream("Media & navigation", AudioManager.STREAM_MUSIC),
    VolumeStream("Calls", AudioManager.STREAM_VOICE_CALL),
    VolumeStream("Ringtone", AudioManager.STREAM_RING),
    VolumeStream("System sounds", AudioManager.STREAM_SYSTEM),
)

@Composable
internal fun MmiSoundPage(apps: List<AppInfo>, onLaunchPackage: (String) -> Unit) {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    Row(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
        SettingsColumn(Modifier.weight(1f)) {
            SectionTitle("Volume")
            VOLUME_STREAMS.forEach { s ->
                val max = remember(s) { runCatching { audio.getStreamMaxVolume(s.stream) }.getOrDefault(15).coerceAtLeast(1) }
                var level by remember(s) { mutableIntStateOf(runCatching { audio.getStreamVolume(s.stream) }.getOrDefault(0)) }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row {
                        Text(s.label, color = MmiInk, fontFamily = MmiFont, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text("$level", color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp)
                    }
                    MmiSlider(level / max.toFloat(), { v ->
                        val next = (v * max).roundToInt()
                        if (next != level) {
                            level = next
                            runCatching { audio.setStreamVolume(s.stream, next, 0) }
                        }
                    }, Modifier.fillMaxWidth().height(36.dp))
                }
            }
        }
        Spacer(Modifier.width(40.dp))
        SettingsColumn(Modifier.weight(1.3f)) {
            SectionTitle("Equalizer")
            if (!CarAudio.available) {
                Text(
                    "This unit doesn't let apps change its system sound. Use the unit's own equalizer:",
                    color = MmiDim, fontFamily = MmiFont, fontSize = 16.sp,
                )
                Spacer(Modifier.height(12.dp))
                ChromePill("Open unit equalizer", {
                    val vendor = apps.findByLabel("equalizer", "eq", "sound", "audio")
                    if (vendor != null) onLaunchPackage(vendor.packageName)
                    else context.launchFirst(
                        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0),
                        Intent(Settings.ACTION_SOUND_SETTINGS),
                    )
                }, MmiLine.OPEN)
            } else {
                EqualizerPanel()
            }
        }
    }
}

@Composable
private fun EqualizerPanel() {
    var enabled by remember { mutableStateOf(CarAudio.enabled) }
    // Bumped after a preset so the band sliders re-read their levels.
    var revision by remember { mutableIntStateOf(0) }
    val range = CarAudio.levelRange
    ToggleRow("Sound effects", enabled) {
        enabled = it
        CarAudio.setEnabled(it)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().height(200.dp)) {
        for (band in 0 until CarAudio.bandCount) {
            var level by remember(band, revision) { mutableIntStateOf(CarAudio.bandLevel(band)) }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%+d".format(level / 100), color = MmiDim, fontFamily = MmiFont, fontSize = 13.sp)
                MmiSlider(
                    (level - range.first) / (range.last - range.first).toFloat(),
                    { v ->
                        level = (range.first + v * (range.last - range.first)).roundToInt()
                        CarAudio.setBandLevel(band, level)
                    },
                    Modifier.weight(1f).width(40.dp).padding(vertical = 8.dp),
                    vertical = true,
                )
                Text(CarAudio.bandLabel(band), color = MmiInk, fontFamily = MmiFont, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChromePill("Flat", { CarAudio.flat(); revision++ })
        CarAudio.presets.take(4).forEachIndexed { i, name ->
            ChromePill(name, { CarAudio.usePreset(i); revision++ })
        }
    }
    if (CarAudio.bassSupported) {
        var bass by remember { mutableIntStateOf(CarAudio.bassStrength) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bass boost", color = MmiInk, fontFamily = MmiFont, fontSize = 17.sp, modifier = Modifier.width(120.dp))
            MmiSlider(bass / 1000f, { v ->
                bass = (v * 1000).roundToInt()
                CarAudio.setBass(bass)
            }, Modifier.weight(1f).height(36.dp))
        }
    }
}

// ── Bluetooth ────────────────────────────────────────────────────────────────

@Composable
internal fun MmiBluetoothPage(apps: List<AppInfo>, onLaunchPackage: (String) -> Unit) {
    val context = LocalContext.current
    val adapter = remember { runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val enabled = remember(refresh) { runCatching { adapter?.isEnabled == true }.getOrDefault(false) }
    val paired = remember(refresh) {
        runCatching { adapter?.bondedDevices?.map { (it.name ?: it.address) }.orEmpty() }.getOrDefault(emptyList())
    }
    val vendorApp = remember(apps) { apps.findByLabel("bluetooth", "bt phone", "bt") }

    SettingsColumn(Modifier.padding(horizontal = 48.dp)) {
        SectionTitle("Bluetooth")
        when {
            adapter == null -> Text(
                "This unit runs Bluetooth in its own module, so phones are paired in the unit's Bluetooth app.",
                color = MmiDim, fontFamily = MmiFont, fontSize = 17.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> SettingRow("Status", if (enabled) "On" else "Off")
        }
        if (paired.isNotEmpty()) {
            SectionTitle("Paired phones")
            paired.forEach { name ->
                Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MmiLineIcon(MmiLine.BLUETOOTH, MmiCyan, Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(name, color = MmiInk, fontFamily = MmiFont, fontSize = 18.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E2125)))
            }
        }
        SectionTitle("Connect")
        if (vendorApp != null) {
            SettingRow("Open ${vendorApp.appName}", onClick = { onLaunchPackage(vendorApp.packageName) })
        }
        SettingRow("Pair a new phone", onClick = { context.launchFirst(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
    }
}

// ── Navigation ───────────────────────────────────────────────────────────────

@Composable
internal fun MmiNavSettingsPage(metric: Boolean, onSetMetric: (Boolean) -> Unit) {
    val context = LocalContext.current
    val nav by NavigationEngine.state.collectAsStateWithLifecycle()
    var beep by remember { mutableStateOf(NavigationEngine.overspeedBeep) }
    var cameras by remember { mutableStateOf(NavigationEngine.cameraAlerts) }

    SettingsColumn(Modifier.padding(horizontal = 48.dp)) {
        SectionTitle("Guidance")
        ToggleRow("Voice prompts", nav.voiceOn, NavigationEngine::setVoice)
        SettingRow("Voice engine", NavigationEngine.voiceStatus)
        SectionTitle("Warnings")
        ToggleRow("Speed limit warning beep", beep) { beep = it; NavigationEngine.setOverspeedBeep(it) }
        ToggleRow("Speed camera alerts", cameras) { cameras = it; NavigationEngine.setCameraAlerts(it) }
        SectionTitle("Map")
        ToggleRow("Metric units (km, km/h)", metric, onSetMetric)
        SettingRow("Map data", if (MapData.isInstalled(context)) "Egypt, offline" else "not imported")
        SettingRow("Turn-by-turn data", if (MapData.routing(context).exists()) "installed" else "not imported")
    }
}
