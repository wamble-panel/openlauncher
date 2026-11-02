package com.openlauncher.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.CallLog
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.openlauncher.app.data.CarPrefs
import com.openlauncher.app.map.NavigationEngine
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.util.BtConnect
import com.openlauncher.app.util.TwBridge
import com.openlauncher.app.util.UiSound
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Answers the Flutter screens' questions about the car: settings, radio, apps,
 * calls, media and Bluetooth. Each method returns plain maps and lists, so the
 * Dart side never touches Android.
 */
internal class FlutterBridge(private val activity: MmiFlutterActivity) {
    private val ctx: Context = activity.applicationContext
    private val prefs = ctx.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE)

    // ── Settings ─────────────────────────────────────────────────────────────

    private fun canWrite() = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(ctx)

    fun settings(): Map<String, Any?> {
        val cr = ctx.contentResolver
        val brightness = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
        val off = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(Int.MAX_VALUE)
        val car = CarPrefs.get(ctx)
        return mapOf(
            "canWrite" to canWrite(),
            "brightness" to brightness.toDouble(),
            "dayNight" to prefs.getInt("day_night", 0),
            "touchSounds" to UiSound.enabled,
            "screenOff" to (off <= 1_800_000),
            "clock24" to car.clock24.value,
            "metric" to car.metric.value,
            "cameraAlerts" to NavigationEngine.cameraAlerts,
            "voice" to NavigationEngine.state.value.voiceOn,
            "callBar" to TwBridge.mmiCallBar(),
            "keepMusicReverse" to TwBridge.keepMusicInReverse(),
            "version" to runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull(),
        )
    }

    fun setSetting(key: String, value: Any?) {
        val cr = ctx.contentResolver
        val on = value == true
        when (key) {
            "brightness" -> setBrightness((value as Number).toFloat())
            "dayNight" -> {
                val i = (value as Number).toInt()
                prefs.edit().putInt("day_night", i).apply()
                when (i) {
                    0 -> runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) }
                    1 -> setBrightness(1f)
                    else -> setBrightness(0.3f)
                }
            }
            "touchSounds" -> UiSound.setEnabled(on)
            "screenOff" -> runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, if (on) 600_000 else Int.MAX_VALUE) }
            "clock24" -> CarPrefs.get(ctx).setClock24(on)
            "metric" -> CarPrefs.get(ctx).setMetric(on)
            "cameraAlerts" -> NavigationEngine.setCameraAlerts(on)
            "voice" -> NavigationEngine.setVoice(on)
            "callBar" -> TwBridge.setMmiCallBar(on)
            "keepMusicReverse" -> TwBridge.setKeepMusicInReverse(on)
        }
    }

    private fun setBrightness(v: Float) {
        val cr = ctx.contentResolver
        runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v * 255).roundToInt().coerceIn(5, 255))
        }
    }

    /** Android's own settings screens, for what MMI does not draw itself. */
    fun system(what: String) {
        val intent = when (what) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "keyboard" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        }
        runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ── Radio ────────────────────────────────────────────────────────────────

    private val radioPrefs = ctx.getSharedPreferences("mmi_radio", Context.MODE_PRIVATE)

    private fun presetName(slot: Int): String? = radioPrefs.getString("preset_name_$slot", null)

    fun radio(): Map<String, Any?> {
        val (am, freq) = TwBridge.radioLast()
        val state = TwBridge.radio.value
        val name = state?.stationName?.takeUnless { it.endsWith("…") }
            ?: TwBridge.radioPresets().indexOfFirst { kotlin.math.abs(it - freq) < 0.05f }.takeIf { it >= 0 }?.let { presetName(it) }
        return mapOf(
            "am" to am,
            "freq" to freq.toDouble(),
            "name" to name,
            "genre" to null,
            "logo" to RadioLogos.file(ctx, name, freq)?.absolutePath,
            "presets" to TwBridge.radioPresets().take(6).mapIndexed { i, f ->
                mapOf("freq" to f.toDouble(), "name" to presetName(i))
            },
        )
    }

    fun radioSave(slot: Int) {
        val (_, freq) = TwBridge.radioLast()
        TwBridge.radioSavePreset(slot, freq)
        // The station name as it is now (RDS), so the favourite keeps it.
        val name = TwBridge.radio.value?.stationName?.takeUnless { it.endsWith("…") }
        radioPrefs.edit().putString("preset_name_$slot", name).apply()
        UiSound.open()
    }

    // ── Apps ─────────────────────────────────────────────────────────────────

    private val categories: Map<String, List<String>> = mapOf(
        "browser" to listOf("browser", "chrome", "firefox", "via"),
        "files" to listOf("files", "file manager", "explorer", "filemanager"),
        "music" to listOf("music", "spotify", "anghami", "player"),
        "video" to listOf("video", "youtube", "vlc", "mx player"),
        "camera" to listOf("camera", "dvr"),
        "calculator" to listOf("calculator"),
        "calendar" to listOf("calendar"),
        "clock" to listOf("clock", "deskclock"),
        "gallery" to listOf("gallery", "photos"),
        "maps" to listOf("maps", "waze", "navigation"),
        "weather" to listOf("weather"),
        "settings" to listOf("settings"),
        "store" to listOf("play store", "store", "aptoide", "market"),
        "notes" to listOf("notes", "keep", "notepad"),
        "recorder" to listOf("recorder", "sound recorder"),
    )

    private data class App(val label: String, val pkg: String)

    private fun installed(): List<App> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun appFor(key: String, apps: List<App>): App? {
        val words = categories[key] ?: return null
        // A label match first ("Calculator"), then the package name (com.android.calculator2).
        return words.firstNotNullOfOrNull { w -> apps.firstOrNull { it.label.equals(w, true) } }
            ?: words.firstNotNullOfOrNull { w -> apps.firstOrNull { it.label.contains(w, true) } }
            ?: words.firstNotNullOfOrNull { w -> apps.firstOrNull { it.pkg.contains(w.replace(" ", ""), true) } }
    }

    fun apps(): Map<String, Any?> {
        val apps = installed()
        val matched = categories.keys.associateWith { appFor(it, apps) }
        val used = matched.values.filterNotNull().map { it.pkg }.toSet()
        return mapOf(
            "found" to matched.filterValues { it != null }.keys.toList(),
            "others" to apps.filter { it.pkg !in used }.map { mapOf("label" to it.label, "pkg" to it.pkg) },
        )
    }

    /** Opens a tile: a category ("music") or a package ("pkg:com.x"). */
    fun launch(key: String): Boolean {
        val pkg = if (key.startsWith("pkg:")) key.removePrefix("pkg:") else appFor(key, installed())?.pkg
        if (pkg == null) {
            if (key == "settings") {
                system("settings")
                return true
            }
            return false
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    // ── Calls ────────────────────────────────────────────────────────────────

    fun calls(): Map<String, Any?> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return mapOf("allowed" to false, "calls" to emptyList<Any>())
        }
        val out = ArrayList<Map<String, Any?>>()
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 80) {
                    val number = c.getString(1) ?: continue
                    out += mapOf(
                        "name" to (c.getString(0)?.takeIf { it.isNotBlank() } ?: number),
                        "number" to number,
                        "type" to when (c.getInt(2)) {
                            CallLog.Calls.OUTGOING_TYPE -> "out"
                            CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "missed"
                            else -> "in"
                        },
                        "when" to whenText(c.getLong(3)),
                    )
                }
            }
        }
        return mapOf("allowed" to true, "calls" to out)
    }

    /** 10:42 today, Yesterday, Mon… within a week, 12 Oct before that. */
    private fun whenText(ms: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val now = Calendar.getInstance()
        val days = ((startOfDay(now) - startOfDay(then)) / 86_400_000L).toInt()
        val h24 = CarPrefs.get(ctx).clock24.value
        return when {
            days <= 0 -> SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.getDefault()).format(then.time)
            days == 1 -> "Yesterday"
            days < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(then.time)
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(then.time)
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun dial(number: String) {
        if (TwBridge.available) {
            TwBridge.dial(number)
        } else {
            runCatching { activity.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun callState(): Map<String, Any?> {
        val c = TwBridge.call.value
        val state = when (c.state) {
            TwBridge.CallState.INCOMING -> "incoming"
            TwBridge.CallState.OUTGOING -> "outgoing"
            TwBridge.CallState.ACTIVE -> "active"
            else -> "none"
        }
        return mapOf("state" to state, "name" to c.name, "number" to c.number, "since" to c.since)
    }

    // ── Media ────────────────────────────────────────────────────────────────

    private var artKey: String? = null

    fun media(): Map<String, Any?> {
        val np = MediaListenerService.nowPlaying.value
        val bt = TwBridge.btTrack.value
        if (np == null && bt != null) {
            return mapOf(
                "title" to bt.title, "artist" to bt.artist, "album" to "", "playing" to bt.playing,
                "pos" to bt.positionMs, "dur" to bt.durationMs, "source" to "Bluetooth", "artId" to null,
            )
        }
        if (np == null) return mapOf("title" to "")
        val c = np.controller
        val meta = c?.metadata
        val ps = c?.playbackState
        var pos = ps?.position ?: 0L
        if (ps?.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        }
        val dur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val source = c?.packageName?.let { p -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
        artKey = if (np.albumArt != null) "${np.title}|${np.artist}|${np.albumArt.width}" else null
        return mapOf(
            "title" to np.title,
            "artist" to np.artist,
            "album" to (meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""),
            "playing" to np.isPlaying,
            "pos" to pos.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE).toInt(),
            "dur" to dur.toInt(),
            "source" to (source ?: "Media"),
            "artId" to artKey,
        )
    }

    fun mediaArt(): ByteArray? {
        val bmp = MediaListenerService.nowPlaying.value?.albumArt ?: return null
        val scaled = if (bmp.width > 400) Bitmap.createScaledBitmap(bmp, 400, (400f * bmp.height / bmp.width).toInt(), true) else bmp
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    fun mediaCommand(cmd: String, ms: Long) {
        val controls = MediaListenerService.nowPlaying.value?.controller?.transportControls
        if (controls == null && TwBridge.btTrack.value != null) {
            when (cmd) {
                "toggle" -> TwBridge.btToggle()
                "next" -> TwBridge.btNext()
                "prev" -> TwBridge.btPrev()
            }
            return
        }
        controls ?: return
        when (cmd) {
            "toggle" -> if (MediaListenerService.nowPlaying.value?.isPlaying == true) controls.pause() else controls.play()
            "next" -> controls.skipToNext()
            "prev" -> controls.skipToPrevious()
            "seek" -> controls.seekTo(ms)
        }
    }

    fun sources(): Map<String, Any?> {
        val list = ArrayList<Map<String, Any?>>()
        if (TwBridge.available || BtConnect.anyConnected()) {
            list += mapOf("key" to "bt", "label" to "Bluetooth audio", "kind" to "bt", "playing" to (TwBridge.btTrack.value?.playing == true))
        }
        val pm = ctx.packageManager
        val playing = MediaListenerService.nowPlaying.value?.controller?.packageName
        pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
            .filter { pm.getLaunchIntentForPackage(it) != null }
            .forEach { p ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
                list += mapOf("key" to "pkg:$p", "label" to label, "kind" to "app", "playing" to (p == playing))
            }
        return mapOf("sources" to list)
    }

    fun source(key: String) {
        when {
            key == "bt" -> TwBridge.btPlay()
            key.startsWith("pkg:") -> launch(key)
        }
    }

    // ── Bluetooth search ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private var scanning = false
    private var connecting: String? = null
    private var registered = false
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) found[d.address] = d
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanning = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun btScan() {
        if (!registered) {
            registered = true
            ContextCompat.registerReceiver(ctx, btReceiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }, ContextCompat.RECEIVER_EXPORTED)
        }
        found.clear()
        runCatching { adapter?.bondedDevices?.forEach { found[it.address] = it } }
        runCatching { adapter?.cancelDiscovery() }
        scanning = runCatching { adapter?.startDiscovery() == true }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun btStop() {
        runCatching { adapter?.cancelDiscovery() }
        scanning = false
        if (registered) {
            registered = false
            runCatching { ctx.unregisterReceiver(btReceiver) }
        }
    }

    @SuppressLint("MissingPermission")
    fun bt(): Map<String, Any?> = mapOf(
        "scanning" to scanning,
        "name" to runCatching { adapter?.name }.getOrNull(),
        "devices" to found.values.toList().mapNotNull { d ->
            val name = runCatching { d.name }.getOrNull() ?: return@mapNotNull null
            mapOf(
                "name" to name,
                "address" to d.address,
                "connected" to BtConnect.isConnected(d),
                "connecting" to (connecting == d.address || runCatching { d.bondState == BluetoothDevice.BOND_BONDING }.getOrDefault(false)),
            )
        },
    )

    @SuppressLint("MissingPermission")
    fun btConnect(address: String) {
        runCatching { adapter?.cancelDiscovery() }
        scanning = false
        connecting = address
        val d = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        if (runCatching { d.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)) BtConnect.connect(address)
        else runCatching { d.createBond() }
    }
}
