package com.openlauncher.app.ui.screen

import android.app.ActivityManager
import android.app.role.RoleManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaSessionManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.map.MapData
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.util.CrashLog
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.util.LocationData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

private enum class Level { OK, WARN, BAD, INFO }
private data class Check(val label: String, val value: String, val level: Level)

/**
 * Reports what this head unit lets the launcher do natively, so the next build
 * can target it: calls, radio, streaming apps, root, GPU/memory, GPS, maps.
 */
@Composable
internal fun MmiSystemCheckPage(apps: List<AppInfo>, location: LocationData?) {
    val context = LocalContext.current
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var running by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        checks = runChecks(context, apps, location)
        running = false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 8.dp)) {
        Text(
            if (running) "Checking this head unit…" else "Take a photo of this page and send it over.",
            color = MmiCyan, fontFamily = MmiFont, fontSize = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(checks) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(
                            when (c.level) {
                                Level.OK -> Color(0xFF4CD964)
                                Level.WARN -> Color(0xFFFFC107)
                                Level.BAD -> Color(0xFFE57373)
                                Level.INFO -> MmiDim
                            }
                        )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(c.label, color = MmiDim, fontFamily = MmiFont, fontSize = 15.sp, modifier = Modifier.width(230.dp))
                    Text(c.value, color = MmiInk, fontFamily = MmiFont, fontSize = 15.sp)
                }
            }
        }
    }
}

private suspend fun runChecks(context: Context, apps: List<AppInfo>, location: LocationData?): List<Check> {
    val out = mutableListOf<Check>()
    val pm = context.packageManager

    out += Check("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", Level.INFO)
    out += Check("Device", "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.HARDWARE} · ${Build.SUPPORTED_ABIS.joinToString()}", Level.INFO)
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    out += Check("Memory", "%.1f GB total, %.1f GB free".format(mem.totalMem / 1e9, mem.availMem / 1e9), Level.INFO)
    out += Check("OpenGL ES", am.deviceConfigurationInfo.glEsVersion, Level.INFO)

    val su = com.openlauncher.app.util.Root.path()
    out += Check(
        "Root",
        su?.let { "su found at $it" } ?: ("not rooted" + if (Build.TAGS?.contains("test-keys") == true) " (test-keys build)" else ""),
        if (su != null) Level.OK else Level.INFO,
    )

    // Bluetooth and calls
    val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
    out += Check(
        "Bluetooth adapter",
        when {
            adapter == null -> "none visible to apps (vendor Bluetooth module)"
            !adapter.isEnabled -> "present, switched off"
            else -> "present, on"
        },
        if (adapter != null) Level.OK else Level.WARN,
    )
    out += Check("Hands-free (HFP client)", hfpClientStatus(context, adapter), Level.INFO).let {
        it.copy(level = if (it.value.startsWith("available")) Level.OK else Level.WARN)
    }
    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    val dialerRole = if (Build.VERSION.SDK_INT >= 29) {
        runCatching { (context.getSystemService(Context.ROLE_SERVICE) as RoleManager).isRoleAvailable(RoleManager.ROLE_DIALER) }.getOrDefault(false)
    } else null
    out += Check(
        "Phone app role",
        "default dialer: ${runCatching { telecom?.defaultDialerPackage }.getOrNull() ?: "none"}" +
            (dialerRole?.let { if (it) " · role available" else " · role unavailable" } ?: " · Android < 10"),
        if (dialerRole == true) Level.OK else Level.WARN,
    )
    out += Check("Telephony feature", if (pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) "yes" else "no", Level.INFO)
    out += Check(
        "Phone / BT apps",
        apps.filter { a -> listOf("bluetooth", "phone", "bt", "dial", "call").any { a.appName.lowercase().contains(it) } }
            .joinToString { "${it.appName} (${it.packageName})" }.ifEmpty { "none found" },
        Level.INFO,
    )

    // Radio
    out += Check(
        "Radio apps",
        apps.filter { a -> listOf("radio", "fm").any { a.appName.lowercase().contains(it) } }
            .joinToString { "${it.appName} (${it.packageName})" }.ifEmpty { "none found" },
        Level.INFO,
    )

    // Media
    val sessions = runCatching {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(context, MediaListenerService::class.java)).map { it.packageName }
    }
    out += Check(
        "Active media sessions",
        sessions.getOrNull()?.joinToString()?.ifEmpty { "none (start the radio or music, then re-open this page)" }
            ?: "no access — enable media/notification access",
        if (sessions.isSuccess) Level.OK else Level.BAD,
    )
    out += Check("Spotify", if (isInstalled(pm, "com.spotify.music")) "installed" else "not installed", Level.INFO)
    out += Check("Anghami", if (isInstalled(pm, "com.anghami")) "installed" else "not installed", Level.INFO)

    // Bluetooth first: if the unit's Bluetooth app exposes the phone's library
    // (AVRCP browsing), Spotify playlists can be browsed with no internet.
    val browsers = pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
        .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        .filter { it.packageName != context.packageName }
        .sortedByDescending { "bluetooth" in it.packageName.lowercase() || "bt" in it.packageName.lowercase() }
    if (browsers.isEmpty()) out += Check("Media libraries", "no apps expose a media library", Level.WARN)
    for (component in browsers.take(8)) {
        val result = browserStatus(context, component)
        out += Check("Library: ${component.packageName}", result, if (result.startsWith("browsable")) Level.OK else Level.WARN)
    }

    // GPS and maps
    out += Check(
        "GPS",
        location?.let { "fix · ${it.satellitesUsed.takeIf { s -> s >= 0 } ?: "?"} satellites · ±${it.accuracy.toInt()} m" } ?: "no fix yet",
        if (location != null) Level.OK else Level.WARN,
    )
    val tiles = MapData.tiles(context)
    val search = MapData.search(context)
    out += Check(
        "Offline map data",
        if (MapData.isInstalled(context)) "installed · %.0f MB tiles · %.0f MB search".format(tiles.length() / 1e6, search.length() / 1e6)
        else "not imported yet",
        if (MapData.isInstalled(context)) Level.OK else Level.WARN,
    )
    val routing = MapData.routing(context)
    out += Check(
        "Turn-by-turn data",
        if (routing.exists()) "installed · %.0f MB routing graph".format(routing.length() / 1e6) else "not imported — direct line only",
        if (routing.exists()) Level.OK else Level.WARN,
    )
    val safety = MapData.safety(context)
    out += Check(
        "Speed limits & cameras",
        if (safety.exists()) "installed" else "not imported",
        if (safety.exists()) Level.OK else Level.WARN,
    )
    val accel = (context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager)
        .getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
    val road = com.openlauncher.app.map.RoadMemory.stats.value
    out += Check(
        "Motion sensor",
        accel?.let {
            "${it.name} · max ${if (it.minDelay > 0) 1_000_000 / it.minDelay else 0} Hz" +
                (if (road.rateHz > 0) " · measured %.0f Hz · road noise %.2f m/s²".format(road.rateHz, road.noiseFloor) else " · drive to measure")
        } ?: "none — road memory can't detect jolts (manual marks still work)",
        when {
            accel == null -> Level.BAD
            road.rateHz in 1.0..24.0 -> Level.WARN
            else -> Level.OK
        },
    )
    out += Check("Road memory", "${road.confirmed} confirmed · ${road.spots} spots · ${road.jolts} jolts (${road.axleMatches} two-axle)", Level.INFO)
    val voice = NavigationEngine.voiceStatus
    out += Check("Voice prompts", voice, if (voice.startsWith("Ready")) Level.OK else Level.BAD)

    val crash = CrashLog.read(context)
    out += Check(
        "Last crash",
        crash?.lines()?.filter { it.isNotBlank() }?.take(8)?.joinToString("\n") { it.trim() } ?: "none",
        if (crash == null) Level.OK else Level.BAD,
    )
    return out
}

private fun isInstalled(pm: PackageManager, pkg: String) = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)

/** Whether the Android Bluetooth stack offers the hands-free *client* role (car side of a call). */
private suspend fun hfpClientStatus(context: Context, adapter: BluetoothAdapter?): String {
    if (adapter == null) return "unknown — no Bluetooth adapter"
    val headsetClient = 16 // BluetoothProfile.HEADSET_CLIENT (hidden constant)
    return withTimeoutOrNull(3000) {
        suspendCancellableCoroutine<String> { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    if (cont.isActive) cont.resume("available (Android stack) — native calls likely possible")
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            }
            val requested = runCatching { adapter.getProfileProxy(context, listener, headsetClient) }
            when {
                requested.isFailure -> if (cont.isActive) cont.resume("blocked: ${requested.exceptionOrNull()?.javaClass?.simpleName}")
                requested.getOrNull() != true -> if (cont.isActive) cont.resume("not offered — calls run in the vendor app")
            }
        }
    } ?: "no answer — calls likely run in the vendor app"
}

/** Tries to open an app's media library the way Android Auto would. */
private suspend fun browserStatus(context: Context, component: ComponentName): String = withContext(Dispatchers.Main) {
    var browser: MediaBrowser? = null
    val result = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine<String> { cont ->
            val callback = object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() { if (cont.isActive) cont.resume("browsable — playlists can be shown in-app") }
                override fun onConnectionFailed() { if (cont.isActive) cont.resume("refused — playback control only") }
                override fun onConnectionSuspended() { if (cont.isActive) cont.resume("suspended") }
            }
            browser = MediaBrowser(context, component, callback, null).also { it.connect() }
        }
    } ?: "no answer"
    runCatching { browser?.disconnect() }
    result
}
