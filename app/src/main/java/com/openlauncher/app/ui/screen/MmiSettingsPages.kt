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
import com.openlauncher.app.map.CarProfile
import com.openlauncher.app.map.RoadMemory
import kotlinx.coroutines.launch
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.util.CarAudio
import kotlin.math.roundToInt

// ── Shared controls ──────────────────────────────────────────────────────────

// Settings pages use the MIB list parts (MmiMib.kt).
@Composable
private fun SettingRow(label: String, value: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) =
    MibListRow(label, value, onClick, trailing = trailing)

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: (Boolean) -> Unit) =
    MibListRow(label, onClick = { onToggle(!on) }) { MibSwitch(on) }

@Composable
private fun SectionTitle(text: String) = MibCaption(text)

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
    val cr = context.contentResolver
    var brightness by remember {
        mutableFloatStateOf(runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f))
    }
    fun setBrightness(v: Float) {
        brightness = v
        runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v * 255).roundToInt().coerceIn(5, 255))
        }
    }
    // Day / night: Auto = the screen's automatic brightness, Day = full, Night = 30 %.
    val modePrefs = remember(context) { context.getSharedPreferences("mmi_prefs", android.content.Context.MODE_PRIVATE) }
    var dayNight by remember { mutableIntStateOf(modePrefs.getInt("day_night", 0)) }
    var touchSounds by remember { mutableStateOf(com.openlauncher.app.util.UiSound.enabled) }
    var newMmi by remember { mutableStateOf(modePrefs.getBoolean("flutter_ui", false)) }
    var screenOff by remember {
        mutableStateOf(runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(Int.MAX_VALUE) <= 1_800_000)
    }

    SettingsColumn(Modifier.padding(horizontal = 8.dp)) {
        if (!canWrite) {
            SettingRow("Allow brightness control", "one-time permission", onClick = {
                context.launchFirst(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
                    Intent(Settings.ACTION_DISPLAY_SETTINGS),
                )
            })
        } else {
            MibSliderRow("Brightness", brightness, { setBrightness(it.coerceAtLeast(0.02f)) })
            Text("Day / night mode", color = Color.White, fontFamily = MmiFont, fontSize = 24.sp, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 12.dp))
            MibSegmented(listOf("Auto", "Day", "Night"), dayNight, { i ->
                dayNight = i
                modePrefs.edit().putInt("day_night", i).apply()
                when (i) {
                    0 -> runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) }
                    1 -> setBrightness(1f)
                    else -> setBrightness(0.3f)
                }
            })
            Spacer(Modifier.height(10.dp))
        }
        ToggleRow("New MMI (preview)", newMmi) { on ->
            newMmi = on
            modePrefs.edit().putBoolean("flutter_ui", on).apply()
            if (on) com.openlauncher.app.MmiFlutterActivity.start(context)
        }
        ToggleRow("Touch sounds", touchSounds) { on ->
            touchSounds = on
            com.openlauncher.app.util.UiSound.setEnabled(on)
        }
        if (canWrite) {
            ToggleRow("Screen off while parked", screenOff) { on ->
                screenOff = on
                runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, if (on) 600_000 else Int.MAX_VALUE) }
            }
        }
        SettingRow("Clock format", if (clock24) "24 h" else "12 h", onClick = { prefs.setClock24(!clock24) })
    }
}

// ── Storage & about ─────────────────────────────────────────────────────────

@Composable
internal fun MmiStoragePage() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val stat = remember(refresh) { runCatching { android.os.StatFs(context.filesDir.absolutePath) }.getOrNull() }
    val mapBytes = remember(refresh) { MapData.dir(context).listFiles()?.sumOf { it.length() } ?: 0L }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var confirmDelete by remember { mutableStateOf(false) }
    fun gb(b: Long) = "%.1f GB".format(b / 1e9)

    SettingsColumn(Modifier.padding(horizontal = 48.dp)) {
        SectionTitle("Storage")
        if (stat != null) {
            val total = stat.totalBytes
            val free = stat.availableBytes
            SettingRow("Free", "${gb(free)} of ${gb(total)}")
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2E34))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(((total - free).toFloat() / total).coerceIn(0f, 1f)).background(MmiCyan))
            }
        }
        SettingRow("Offline maps", "%.0f MB".format(mapBytes / 1e6))
        SettingRow(if (confirmDelete) "Tap again to delete the maps" else "Delete offline maps", onClick = {
            if (confirmDelete) {
                MapData.dir(context).listFiles()?.forEach { it.delete() }
                NavigationEngine.reloadData()
                confirmDelete = false
                refresh++
            } else {
                confirmDelete = true
            }
        })
        SectionTitle("About")
        SettingRow("MMI", version)
        SettingRow("Unit", "${Build.MANUFACTURER} ${Build.MODEL}")
        SettingRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
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
        SectionTitle("Road memory (potholes & speed bumps)")
        val road by RoadMemory.stats.collectAsStateWithLifecycle()
        var car by remember { mutableStateOf(RoadMemory.car) }
        var roadWarnings by remember { mutableStateOf(RoadMemory.warnings) }
        var roadMessage by remember { mutableStateOf<String?>(null) }
        val roadScope = rememberCoroutineScope()
        SettingRow("Car", car.label, onClick = {
            car = CarProfile.entries[(car.ordinal + 1) % CarProfile.entries.size]
            RoadMemory.setCar(car)
        })
        ToggleRow("Warn before potholes & bumps", roadWarnings) { roadWarnings = it; RoadMemory.setWarnings(it) }
        SettingRow("Learned", "${road.confirmed} confirmed · ${road.spots} spots · ${road.jolts} jolts this session")
        SettingRow("Share with the other car: export", roadMessage, onClick = {
            roadScope.launch { roadMessage = RoadMemory.export()?.let { "saved to Download" } ?: "export failed — allow file access" }
        })
        SettingRow("Import from the other car", onClick = {
            roadScope.launch {
                val n = RoadMemory.import()
                roadMessage = if (n > 0) "merged $n spots" else "no mmi-road-memory file on USB or in Download"
            }
        })
        SectionTitle("About the map")
        Text(
            "Map data © OpenStreetMap contributors, available under the Open Database License (openstreetmap.org/copyright).",
            color = MmiDim, fontFamily = MmiFont, fontSize = 14.sp, modifier = Modifier.padding(8.dp),
        )
    }
}
