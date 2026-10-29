package com.openlauncher.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openlauncher.app.data.CarPrefs
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.HardwareRadioState
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.util.CarLocation
import com.openlauncher.app.util.LocationData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State and actions behind the MMI shell: apps, media, GPS, radio and supply voltage. */
class CarViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = CarPrefs(application)
    private val locationMgr = CarLocation.get(application)

    // ── Installed apps ────────────────────────────────────────────────────────
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _appsLoading = MutableStateFlow(false)

    fun loadInstalledApps() {
        if (_appsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _appsLoading.value = true
            val pm = getApplication<Application>().packageManager

            // Use getInstalledApplications — same source Android Settings uses,
            // catches apps with no launcher/ACTION_MAIN activity (e.g. CarPlay companions)
            _apps.value = pm.getInstalledApplications(0)
                .mapNotNull { appInfo ->
                    try {
                        val label = pm.getApplicationLabel(appInfo).toString()
                        if (label.isBlank()) return@mapNotNull null
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName     = label,
                            icon        = pm.getApplicationIcon(appInfo),
                            isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (_: Exception) { null }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.appName }
            _appsLoading.value = false
        }
    }

    fun launchApp(packageName: String) {
        val app = getApplication<Application>()
        val pm  = app.packageManager
        // Try standard launch intent first; fall back to first ACTION_MAIN activity in package
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).setPackage(packageName), 0
            ).firstOrNull()?.activityInfo?.let { ai ->
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(ai.packageName, ai.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        intent?.let { app.startActivity(it) }
    }

    // ── Now Playing ───────────────────────────────────────────────────────────
    val nowPlaying: StateFlow<NowPlayingState?> = MediaListenerService.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun playPause() { nowPlaying.value?.controller?.also { ctrl ->
        val state = ctrl.playbackState?.state
        if (state == android.media.session.PlaybackState.STATE_PLAYING)
            ctrl.transportControls?.pause()
        else
            ctrl.transportControls?.play()
    }}

    fun skipNext() { nowPlaying.value?.controller?.transportControls?.skipToNext() }
    fun skipPrev() { nowPlaying.value?.controller?.transportControls?.skipToPrevious() }

    fun refreshMedia() {
        MediaListenerService.requestRefresh()
    }

    // ── Location & Compass ────────────────────────────────────────────────────
    val location: StateFlow<LocationData?> = locationMgr.location
    val compassBearing: StateFlow<Float> = locationMgr.bearing

    // ── Radio ─────────────────────────────────────────────────────────────────
    // Android has no public FM tuner API (the Broadcast Radio HAL is @SystemApi),
    // so the radio page mirrors the unit's real tuner through two backends:
    //   1. szchoiceway MCU units — Settings.Global JSON + canbus broadcasts.
    //   2. Any other unit — the vendor radio app's MediaSession; seek maps to
    //      skip next/prev (how steering-wheel keys drive these apps).
    private val _mcuRadio = MutableStateFlow<HardwareRadioState?>(null)

    private fun packageInstalled(pkg: String) = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(pkg, 0)
    }.isSuccess

    private val hasSzchoicewayMcu: Boolean by lazy {
        packageInstalled("com.szchoiceway.radio") ||
        packageInstalled("com.szchoiceway.eventcenter") ||
        runCatching {
            AndroidSettings.Global.getString(
                getApplication<Application>().contentResolver, "SYS_MEDIA_INFO_JSON"
            ) != null
        }.getOrDefault(false)
    }

    private fun parseRadioJson(): HardwareRadioState? {
        return try {
            val json = AndroidSettings.Global.getString(
                getApplication<Application>().contentResolver, "SYS_MEDIA_INFO_JSON"
            ) ?: return null
            val title = org.json.JSONObject(json).optString("mediaTitle", "") // e.g. "FM1 90.10"
            if (title.isEmpty()) return null
            val parts = title.trim().split("\\s+".toRegex())
            if (parts.size < 2) return null
            HardwareRadioState(band = parts[0], freq = parts[1], canTune = true)
        } catch (e: Exception) {
            android.util.Log.e("RadioMcu", "parseRadioJson error", e)
            null
        }
    }

    private var radioObserver: ContentObserver? = null

    private fun startHardwareRadioObserver() {
        if (radioObserver != null) return
        _mcuRadio.value = parseRadioJson()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                _mcuRadio.value = parseRadioJson()
            }
        }
        getApplication<Application>().contentResolver.registerContentObserver(
            AndroidSettings.Global.getUriFor("SYS_MEDIA_INFO_JSON"),
            false, observer
        )
        radioObserver = observer
    }

    // ── MediaSession radio backend (universal fallback) ───────────────────────

    private fun looksLikeRadioPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "radio" in p || "fmradio" in p || "tuner" in p || p.endsWith(".fm") || ".fm." in p
    }

    private fun isRadioSessionPackage(pkg: String): Boolean {
        val assigned = prefs.radioPackage
        return if (assigned.isNotEmpty()) pkg == assigned else looksLikeRadioPackage(pkg)
    }

    // Matches "FM1 90.10", "99.9 MHz", "FM 99.9 WXYZ", "1040 kHz", "99,9" …
    private val sessionFreqPattern =
        Regex("""(?:\b(FM\s?\d?|AM)\b)?\s*(\d{2,4}(?:[.,]\d{1,2})?)\s*(MHz|kHz)?""", RegexOption.IGNORE_CASE)

    private fun parseSessionRadio(np: NowPlayingState): HardwareRadioState? {
        val pkg = np.controller?.packageName ?: return null
        if (!isRadioSessionPackage(pkg)) return null

        val text = listOf(np.title, np.artist)
            .filter { it.isNotBlank() && it != "Unknown" }
            .joinToString("  ")
        val match   = sessionFreqPattern.find(text)
        val rawFreq = match?.groupValues?.get(2)?.replace(',', '.')?.takeIf { it.isNotEmpty() }
        val freqVal = rawFreq?.toFloatOrNull()
        val explicitBand = match?.groupValues?.get(1)?.replace(" ", "")?.uppercase().orEmpty()
        val band = when {
            explicitBand.isNotEmpty()                 -> explicitBand
            freqVal != null && freqVal in 520f..1710f -> "AM"
            else                                      -> "FM"
        }
        // Whatever isn't the frequency is the station / RDS text
        val station = (if (match != null) text.replace(match.value, "") else text)
            .trim(' ', '-', '|', '/', '•')
            .takeIf { it.isNotBlank() }
        return HardwareRadioState(band = band, freq = rawFreq ?: "—", stationName = station, canTune = false)
    }

    // MCU backend wins when present; otherwise mirror the radio app's session
    val hardwareRadio: StateFlow<HardwareRadioState?> =
        combine(_mcuRadio, nowPlaying) { mcu, np ->
            mcu ?: np?.let { parseSessionRadio(it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun radioSessionController() = nowPlaying.value?.controller?.takeIf { c ->
        c.packageName?.let { isRadioSessionPackage(it) } == true
    }

    // Send a radio keyCode to the MCU via the EventService broadcast channel.
    // Permission com.szchoiceway.permission.broadcast (prot=normal) is required and declared.
    // keyCodes: 15=seek_up, 14=seek_down, 30=fm_cycle, 31=am
    private fun sendMcuByteArray(bytes: ByteArray) {
        runCatching {
            val intent = Intent("com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT").apply {
                putExtra("EventUtils.MCU_CMD_DATA", bytes)
            }
            getApplication<Application>().sendBroadcast(intent)
        }
    }

    private fun sendMcuBytes(vararg bytes: Byte) = sendMcuByteArray(bytes)

    private val mcuActive get() = _mcuRadio.value != null

    // Seek routes to the MCU when that backend is live, otherwise to the radio
    // app's MediaSession — skip next/prev is how these apps expose seek.
    fun radioSeekUp()   { if (mcuActive) sendMcuBytes(0x02, 0x0f) else radioSessionController()?.transportControls?.skipToNext() }
    fun radioSeekDown() { if (mcuActive) sendMcuBytes(0x02, 0x0e) else radioSessionController()?.transportControls?.skipToPrevious() }
    // Band switching and direct tuning only exist on the MCU backend
    fun radioCycleFm()  { if (mcuActive) sendMcuBytes(0x02, 0x1e) }
    fun radioSwitchAm() { if (mcuActive) sendMcuBytes(0x02, 0x1f) }
    fun radioStart()    { sendMcuBytes(0x01, 0x01) }
    fun launchHardwareRadioApp() {
        if (hasSzchoicewayMcu) radioStart()
        val pkg = prefs.radioPackage.ifEmpty {
            when {
                hasSzchoicewayMcu -> "com.szchoiceway.radio"
                else              -> radioSessionController()?.packageName ?: ""
            }
        }
        if (pkg.isNotEmpty()) {
            runCatching {
                val intent = getApplication<Application>().packageManager
                    .getLaunchIntentForPackage(pkg)
                    ?: Intent(Intent.ACTION_MAIN).apply {
                        `package` = pkg
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            }
        }
        // Resume playback if the session is just paused
        radioSessionController()?.transportControls?.play()
    }

    // ── Voltage ───────────────────────────────────────────────────────────────
    // Most aftermarket head units have no real internal battery — the vendor ROM
    // wires the car's 12V input straight into Android's standard battery-voltage
    // reporting (the same field a phone would use for its own cell), so this reads
    // through the public BatteryManager API rather than anything unit-specific.
    private val _voltage = MutableStateFlow<Float?>(null)
    val voltage: StateFlow<Float?> = _voltage

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val millivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            // A real car electrical system is never anywhere near 0V while the unit
            // is on — this unit's standard-Android battery field turned out to be a
            // meaningless placeholder, not the real CAN-bus-fed voltage sensor its
            // own status bar reads from. Better to show nothing than a false 0.0V.
            if (millivolts >= 5000) _voltage.value = millivolts / 1000f
        }
    }

    private fun startVoltageObserver() {
        runCatching {
            getApplication<Application>().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    override fun onCleared() {
        super.onCleared()
        radioObserver?.let { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
        radioObserver = null
        runCatching { getApplication<Application>().unregisterReceiver(batteryReceiver) }
    }

    init {
        loadInstalledApps()
        startVoltageObserver()
        if (hasSzchoicewayMcu) startHardwareRadioObserver()
    }
}
