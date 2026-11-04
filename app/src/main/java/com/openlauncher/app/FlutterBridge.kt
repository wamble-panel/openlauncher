package com.openlauncher.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.launch
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
            "phonelinkAuto" to com.openlauncher.app.util.PhoneLink.autoOpen(ctx),
            "gles" to prefs.getBoolean("gles", false),
            "version" to runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull(),
            // Car buttons the driver gave a job to: {"code": "action"} as JSON text.
            "keymap" to (prefs.getString("keymap", null) ?: "{}"),
            "lang" to (prefs.getString("lang", null) ?: "en"),
        ) + carIdentity()
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
            "phonelinkAuto" -> com.openlauncher.app.util.PhoneLink.setAutoOpen(ctx, on)
            "gles" -> prefs.edit().putBoolean("gles", on).apply() // used from the next start
            "carDecoder" -> runCatching { com.openlauncher.app.util.CarProfile.setDecoder(com.openlauncher.app.util.CarProfile.Decoder.valueOf(value as String)) }
            "keymap" -> prefs.edit().putString("keymap", value as? String ?: "{}").apply()
            "lang" -> prefs.edit().putString("lang", if (value == "ar") "ar" else "en").apply()
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
            // The vendor's own settings (its Factory tab asks for the vendor password as usual).
            "vendorSettings" -> ctx.packageManager.getLaunchIntentForPackage("com.dofun.carsetting") ?: Intent(Settings.ACTION_SETTINGS)
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
        val tuner = com.openlauncher.app.util.CarCore.radio.value
        val name = state?.stationName?.takeUnless { it.endsWith("…") }
            ?: TwBridge.radioPresets().indexOfFirst { kotlin.math.abs(it - freq) < 0.05f }.takeIf { it >= 0 }?.let { presetName(it) }
        return mapOf(
            "am" to am,
            "freq" to freq.toDouble(),
            "name" to name,
            "genre" to ptyName(tuner.pty),
            "text" to tuner.text,
            "seeking" to tuner.seeking,
            "stereo" to tuner.stereo,
            "local" to tuner.local,
            "live" to com.openlauncher.app.util.CarCore.active.value,
            "logo" to RadioLogos.file(ctx, name, freq)?.absolutePath,
            "presets" to TwBridge.radioPresets().take(6).mapIndexed { i, f ->
                mapOf("freq" to f.toDouble(), "name" to presetName(i))
            },
        )
    }

    /** RDS programme type (Europe) as a word, for the line under the station. */
    private fun ptyName(pty: Int): String? = listOf(
        null, "News", "Current affairs", "Information", "Sport", "Education", "Drama", "Culture", "Science", "Varied",
        "Pop", "Rock", "Easy listening", "Light classical", "Classical", "Other music", "Weather", "Finance",
        "Children", "Social affairs", "Religion", "Phone-in", "Travel", "Leisure", "Jazz", "Country", "National music",
        "Oldies", "Folk", "Documentary",
    ).getOrNull(pty)

    fun radioSave(slot: Int) {
        val (_, freq) = TwBridge.radioLast()
        TwBridge.radioSavePreset(slot, freq)
        // The station name as it is now (RDS), so the favourite keeps it.
        val name = (com.openlauncher.app.util.CarCore.radio.value.ps ?: TwBridge.radio.value?.stationName)?.takeUnless { it.endsWith("…") }
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
        // MMI's own USB player first, when it is the chosen source.
        val usb = com.openlauncher.app.util.UsbMusic
        usb.state.value.song?.let { s ->
            artKey = "usb:${s.id}"
            return mapOf(
                "title" to s.title, "artist" to s.artist, "album" to s.album,
                "playing" to usb.state.value.playing, "pos" to usb.positionMs.toInt(), "dur" to s.durationMs.toInt(),
                "source" to "USB music", "artId" to artKey,
            )
        }
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
        val bmp = (if (com.openlauncher.app.util.UsbMusic.active) com.openlauncher.app.util.UsbMusic.cover() else MediaListenerService.nowPlaying.value?.albumArt) ?: return null
        val scaled = if (bmp.width > 400) Bitmap.createScaledBitmap(bmp, 400, (400f * bmp.height / bmp.width).toInt(), true) else bmp
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    fun mediaCommand(cmd: String, ms: Long) {
        val usb = com.openlauncher.app.util.UsbMusic
        if (usb.active) {
            when (cmd) {
                "toggle" -> usb.toggle()
                "next" -> usb.skip(1)
                "prev" -> usb.skip(-1)
                "seek" -> usb.seek(ms)
            }
            return
        }
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
        val usbSongs = com.openlauncher.app.util.UsbMusic.library.value.size
        list += mapOf("key" to "usb", "label" to "USB music" + if (usbSongs > 0) " · $usbSongs songs" else " · no songs found",
            "kind" to "usb", "playing" to com.openlauncher.app.util.UsbMusic.state.value.playing)
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
        if (key != "usb") com.openlauncher.app.util.UsbMusic.stop()
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

/** Car information for the Flutter Car page (CAN box values; null when not sent). */
internal fun carInfo(): Map<String, Any?> {
    val car = com.openlauncher.app.util.CarCore.data.value
    val info = com.openlauncher.app.util.CarInfoLink.info.value
    return mapOf(
        "speed" to (info.speedKmh ?: car.speedKmh),
        "rpm" to info.rpm,
        "handbrake" to info.handbrake,
        "headlights" to info.headlights,
        "voltage" to (car.voltage ?: info.voltage)?.toDouble(),
        "coolant" to car.coolantC,
        "oilLife" to car.oilLifePct,
        "range" to (car.rangeKm ?: info.range?.toInt()),
        "consumption" to (car.avgConsumption ?: info.avgFuel)?.toDouble(),
        "odometer" to info.odometer?.toDouble(),
        "outside" to (com.openlauncher.app.util.Climate.state.value.outdoor ?: info.outsideC)?.toDouble(),
        // Tyres: CAN box (kPa) first, else the car service's TPMS items (with their unit).
        "tyres" to car.tyresKpa,
        "tyresText" to info.tyres.takeIf { t -> car.tyresKpa == null && t.any { it != null } }
            ?.map { v -> v?.let { "%.1f".format(Locale.US, it) + " " + (info.tyreUnit ?: "") } ?: "--" },
        "doors" to com.openlauncher.app.util.CarCore.doorsText(car.doors)?.lowercase(),
        // Doors are only known on cars whose CAN box MMI can read (CarProfile).
        "doorsKnown" to com.openlauncher.app.util.CarProfile.raiseDoors,
    ) + carIdentity()
}

/**
 * The car this unit is set up for, and its picture: your own photo if there is one in
 * the MMI folder (car.png / car.jpg / car.webp), else the built-in Astra J only when the
 * unit is set to an Opel / GM car (or the car list could not be read).
 */
internal fun carIdentity(): Map<String, Any?> {
    val profile = com.openlauncher.app.util.CarProfile
    profile.refresh()
    val c = profile.car
    val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "MMI")
    val own = listOf("car.png", "car.jpg", "car.webp").map { java.io.File(dir, it) }.firstOrNull { it.isFile && it.length() > 0 }
    return mapOf(
        "carName" to c.name,
        "carBox" to c.canBox,
        "carKnown" to c.known,
        "carPicture" to own?.absolutePath,
        "carDefaultPicture" to (!c.known || c.gm),
        "carDecoder" to profile.decoderSetting().name,
    )
}

/**
 * Weather where the car is, from Open-Meteo (free, no key). Fetched at most every
 * 30 minutes; the last answer is kept in prefs and shown while offline.
 */
internal object Weather {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var fetching = false

    fun get(ctx: Context): Map<String, Any?> {
        val prefs = ctx.getSharedPreferences("mmi_weather", Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0L)
        if (System.currentTimeMillis() - at > 30 * 60_000L && !fetching) {
            fetching = true
            worker.execute {
                try { fetch(ctx) } catch (_: Exception) {} finally { fetching = false }
            }
        }
        val json = prefs.getString("json", null) ?: return mapOf(
            "message" to if (com.openlauncher.app.util.CarLocation.get(ctx).location.value == null) "Waiting for the car's position…" else "Loading the weather…",
        )
        return runCatching { parse(org.json.JSONObject(json), prefs.getString("place", null), at) }.getOrDefault(mapOf("message" to "No weather yet"))
    }

    private fun fetch(ctx: Context) {
        val loc = com.openlauncher.app.util.CarLocation.get(ctx).location.value ?: return
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(Locale.US, loc.latitude, loc.longitude) +
            "&current=temperature_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        val body = try {
            c.connectTimeout = 8000; c.readTimeout = 10000
            if (c.responseCode != 200) return
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
        val place = runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(ctx, Locale.ENGLISH).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                ?.let { it.subLocality ?: it.locality ?: it.subAdminArea }
        }.getOrNull()
        ctx.getSharedPreferences("mmi_weather", Context.MODE_PRIVATE).edit()
            .putString("json", body).putString("place", place).putLong("at", System.currentTimeMillis()).apply()
    }

    private fun parse(j: org.json.JSONObject, place: String?, at: Long): Map<String, Any?> {
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val times = d.getJSONArray("time")
        val codes = d.getJSONArray("weather_code")
        val hi = d.getJSONArray("temperature_2m_max")
        val lo = d.getJSONArray("temperature_2m_min")
        val day = SimpleDateFormat("EEE", Locale.ENGLISH)
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = (1 until times.length()).map { i ->
            mapOf(
                "day" to runCatching { day.format(iso.parse(times.getString(i))!!) }.getOrDefault(""),
                "code" to codes.getInt(i), "hi" to hi.getDouble(i), "lo" to lo.getDouble(i),
            )
        }
        return mapOf(
            "place" to place,
            "temp" to cur.getDouble("temperature_2m"),
            "code" to cur.getInt("weather_code"),
            "wind" to cur.getDouble("wind_speed_10m"),
            "hi" to hi.getDouble(0),
            "lo" to lo.getDouble(0),
            "updated" to SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(at)),
            "days" to days,
        )
    }
}

/**
 * The vendor apps and whether MMI does their job yet, for removing them one by one.
 * Status: "safe" (MMI replaces it), "test" (MMI replaces it; check in the car first),
 * "keep" (MMI still needs it). Each row also says what is lost without it.
 */
internal object VendorApps {
    private data class Row(val pkg: String, val name: String, val status: String, val note: String)

    private val ROWS = listOf(
        Row("com.dofun.variety", "Vendor home screen", "safe", "MMI is the home screen"),
        Row("com.dofun.carassistant.car", "CarMate", "safe", "MMI Car information reads the same service"),
        Row("com.dofun.dofunweather.main", "Weather", "safe", "MMI Weather"),
        Row("com.tw.video", "Video", "safe", "Use any video app from Apps"),
        Row("com.dofun.market", "DoFun store", "safe", "Only after ZLink is installed and sent to the developer"),
        Row("com.tw.radio", "Radio", "test", "MMI is the whole radio; check tuning, names and car buttons first"),
        Row("com.tw.radio.theme", "Radio theme", "test", "Pictures for the vendor radio; goes with it"),
        Row("com.tw.eq", "EQ", "test", "MMI equaliser and balance / fader; check the sound first"),
        Row("com.tw.music", "Music (USB)", "test", "MMI USB music replaces it; play a stick first"),
        Row("com.tw.bt", "Bluetooth", "keep", "Calls, phonebook and Bluetooth music"),
        Row("com.zjinnova.zlink", "Tlink5 (ZLink)", "keep", "CarPlay and Android Auto engine"),
        Row("com.tw.service", "TW service", "keep", "Car link; part of the system"),
        Row("com.tw.service.xt", "XT service", "keep", "Command relay MMI uses (amplifier)"),
        Row("com.tw.core", "TW core", "keep", "Car link; part of the system"),
        Row("com.tw.coreservice", "TD service", "keep", "Car link; part of the system"),
        Row("com.tw.carinfoservice", "Car Info Service", "keep", "Speed, rpm, lights for MMI"),
        Row("com.tw.car", "Car settings (CAN)", "keep", "Decodes the Astra's CAN box"),
        Row("com.tw.carchoose", "Car choose", "test", "Setup tool: the car choice stays saved; check car data after disabling"),
        Row("com.tw.keypad", "Steering wheel", "test", "Setup tool for learning keys; check the steering keys after disabling"),
        Row("com.tw.reverse", "Reverse camera", "keep", "Camera picture when reversing"),
        Row("com.tw.ac", "Air conditioning", "keep", "Climate pop-up and outside temperature"),
        Row("com.dofun.carsetting", "Car settings", "test", "Setup tool; settings stay saved. Disable last, after everything else works"),
        Row("com.tw.auxin", "AUX", "keep", "Needed only if you use the AUX input"),
        Row("com.tw.dvr", "Dash cam", "keep", "Needed only with a dash cam"),
        Row("com.tw.tv", "TV", "keep", "Needed only with a TV tuner"),
        Row("com.tw.rightview", "Right-side camera", "keep", "Needed only with a side camera"),
    )

    fun list(c: Context): Map<String, Any?> = mapOf("apps" to ROWS.mapNotNull { r ->
        val info = runCatching { c.packageManager.getApplicationInfo(r.pkg, 0) }.getOrNull() ?: return@mapNotNull null
        mapOf("pkg" to r.pkg, "name" to r.name, "status" to r.status, "note" to r.note, "enabled" to info.enabled)
    })

    /** Android's own page for the app, where it can be disabled (and enabled again). */
    fun open(c: Context, pkg: String) = runCatching {
        c.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * How the A133 is doing: its temperature sensors (CPU, GPU, memory), CPU speed per
 * core, free RAM and MMI's own memory. Read from the kernel's files; anything the
 * unit hides is left out.
 */
internal object UnitHealth {
    private fun read(path: String): String? = runCatching { java.io.File(path).readText().trim() }.getOrNull()

    fun get(c: Context): Map<String, Any?> {
        val temps = java.io.File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name }
            ?.mapNotNull { z ->
                val raw = read("${z.path}/temp")?.toIntOrNull() ?: return@mapNotNull null
                val c2 = if (raw > 1000) raw / 1000.0 else raw.toDouble()
                mapOf("name" to (read("${z.path}/type") ?: z.name), "c" to c2)
            }.orEmpty()
        val cores = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
            mapOf(
                "cur" to read("$base/scaling_cur_freq")?.toIntOrNull()?.div(1000),
                "max" to (read("$base/cpuinfo_max_freq") ?: read("$base/scaling_max_freq"))?.toIntOrNull()?.div(1000),
            )
        }
        val mem = read("/proc/meminfo")?.lines().orEmpty().associate { l ->
            val parts = l.split(Regex("\\s+"))
            parts[0].trimEnd(':') to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
        }
        val gpu = java.io.File("/sys/class/devfreq").listFiles()?.firstOrNull { it.name.contains("gpu", true) }
            ?.let { read("${it.path}/cur_freq")?.toLongOrNull()?.div(1_000_000) }
        return mapOf(
            "temps" to temps,
            "cores" to cores,
            "gpuMhz" to gpu,
            "ramTotalMb" to (mem["MemTotal"] ?: 0) / 1024,
            "ramFreeMb" to (mem["MemAvailable"] ?: 0) / 1024,
            "mmiMb" to android.os.Debug.getPss() / 1024,
            "uptimeMin" to android.os.SystemClock.elapsedRealtime() / 60000,
        )
    }
}


/** The USB library for the Flutter page: songs, albums, artists or folders, and playing them. */
internal object UsbLibrary {
    private val music get() = com.openlauncher.app.util.UsbMusic

    fun get(mode: String, filter: String?): Map<String, Any?> {
        val all = music.library.value
        val items: List<Map<String, Any?>> = when {
            filter != null -> songsFor(mode, filter).map { song(it) }
            mode == "albums" -> all.groupBy { it.album }.map { (k, v) -> mapOf("key" to k, "title" to k.ifEmpty { "Unknown album" }, "sub" to "${v.first().artist} · ${v.size} songs") }.sortedBy { (it["title"] as String).lowercase() }
            mode == "artists" -> all.groupBy { it.artist }.map { (k, v) -> mapOf("key" to k, "title" to k, "sub" to "${v.size} songs") }.sortedBy { (it["title"] as String).lowercase() }
            mode == "folders" -> all.groupBy { it.folder }.map { (k, v) -> mapOf("key" to k, "title" to k.substringAfterLast('/').ifEmpty { "Top folder" }, "sub" to "$k · ${v.size} songs") }.sortedBy { (it["title"] as String).lowercase() }
            else -> all.map { song(it) }
        }
        return mapOf("scanning" to music.scanning, "count" to all.size, "items" to items.take(2000))
    }

    private fun songsFor(mode: String, filter: String) = music.library.value.filter {
        when (mode) {
            "albums" -> it.album == filter
            "artists" -> it.artist == filter
            "folders" -> it.folder == filter
            else -> true
        }
    }

    private fun song(s: com.openlauncher.app.util.UsbMusic.Song) =
        mapOf("key" to s.id.toString(), "title" to s.title, "sub" to "${s.artist} · ${s.album}", "song" to true)

    /** Plays the list being shown (all songs, or one album / artist / folder) from [id]. */
    fun play(mode: String, filter: String?, id: Long) {
        val queue = if (filter != null) songsFor(mode, filter) else music.library.value
        com.openlauncher.app.util.TwBridge.btPause()
        music.play(queue, queue.indexOfFirst { it.id == id }.coerceAtLeast(0))
    }
}

/**
 * Software update for the new screens: the same updater as before (builds download in
 * the background; one tap installs), with its progress kept here for the page to show.
 */
internal object UpdateFlow {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val updater = com.openlauncher.app.util.AppUpdater
    @Volatile private var latest: Long? = null
    @Volatile private var checked = false
    @Volatile private var status: String? = null
    @Volatile private var progress: Int? = null
    @Volatile private var busy = false

    fun state(context: Context): Map<String, Any?> {
        if (!checked) check()
        val installed = updater.installedVersion(context)
        val stat = runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path) }.getOrNull()
        val name = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return mapOf(
            "installed" to installed,
            "versionName" to name,
            "latest" to latest,
            "checked" to checked,
            "ready" to updater.ready.value,
            "failure" to updater.failure.value,
            "status" to status,
            "progress" to progress,
            "busy" to busy,
            "freeGb" to stat?.let { it.availableBytes / 1e9 },
            "totalGb" to stat?.let { it.totalBytes / 1e9 },
        )
    }

    fun check() {
        checked = true
        scope.launch { latest = updater.latestVersion() }
    }

    /** Download if needed, then install. */
    fun run(context: Context) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (updater.ready.value == null) {
                    status = "Checking…"
                    progress = null
                    val got = updater.prefetch(context) { p -> progress = p; status = "Downloading $p %" }
                    latest = updater.latestVersion()
                    progress = null
                    if (got == null) {
                        status = if (latest == null) "No internet. Connect Wi-Fi and try again." else "MMI is up to date."
                        return@launch
                    }
                }
                status = if (updater.installReady(context)) "Installing…" else "Allow MMI to install apps, then tap again."
            } finally {
                busy = false
            }
        }
    }
}

/**
 * The developer page's PIN, chosen by the owner the first time. Only a salted SHA-256 of
 * it is kept. Forgotten PIN: clear MMI's app data (Android settings) and choose a new one.
 */
internal object DevPin {
    private fun prefs(c: Context) = c.getSharedPreferences("mmi_dev", Context.MODE_PRIVATE)

    private fun hash(salt: String, pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun isSet(c: Context) = prefs(c).getString("hash", null) != null

    fun set(c: Context, pin: String): Boolean {
        if (pin.length !in 4..8 || !pin.all(Char::isDigit)) return false
        val salt = java.util.UUID.randomUUID().toString()
        prefs(c).edit().putString("salt", salt).putString("hash", hash(salt, pin)).apply()
        return true
    }

    fun check(c: Context, pin: String): Boolean {
        val p = prefs(c)
        val salt = p.getString("salt", null) ?: return false
        return p.getString("hash", null) == hash(salt, pin)
    }
}
