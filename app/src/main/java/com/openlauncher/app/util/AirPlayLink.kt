package com.openlauncher.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.MmiLink
import io.github.jqssun.airplay.mmi.MirrorActivity
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

/**
 * MMI's built-in AirPlay (airplay module): the iPhone's screen and music over Wi-Fi.
 * The receiver runs in its own process (":airplay"); this side starts it, follows its
 * state for the Phone Link page, and tells its screen when the car is moving (the
 * picture is hidden then). Broadcasts stay inside MMI (own package and permission).
 */
object AirPlayLink {
    data class State(
        val running: Boolean = false,
        val mirroring: Boolean = false,
        val audio: Boolean = false,
        val video: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val name: String = "MMI",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    @Volatile private var driving = false
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The receiver's native libraries are in this build (not in an emulator build without them). */
    fun available(c: Context): Boolean =
        File(c.applicationInfo.nativeLibraryDir, "libairplay_native.so").isFile

    private fun register(c: Context, r: BroadcastReceiver, action: String) {
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, MmiLink.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
        else c.registerReceiver(r, f, MmiLink.PERMISSION, null)
    }

    /** Starts the receiver (in the background after start-up) and follows it. */
    fun start(c: Context) {
        val app = c.applicationContext
        if (!available(app)) return
        if (!started) {
            started = true
            register(app, object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
                    // GPS speed for the driving lock while the iPhone's picture is showing
                    // (MMI's own screens, which normally hold GPS, are behind it then).
                    val showing = i.getBooleanExtra("mirroring", false) || i.getBooleanExtra("video", false)
                    if (showing) CarLocation.acquire(app, "airplay") else CarLocation.release("airplay")
                    _state.value = State(
                        running = i.getBooleanExtra("running", false),
                        mirroring = i.getBooleanExtra("mirroring", false),
                        audio = i.getBooleanExtra("audio", false),
                        video = i.getBooleanExtra("video", false),
                        title = i.getStringExtra("title")?.takeIf { it.isNotBlank() },
                        artist = i.getStringExtra("artist")?.takeIf { it.isNotBlank() },
                        name = i.getStringExtra("name") ?: "MMI",
                    )
                }
            }, MmiLink.ACTION_STATE)
            // The iPhone's screen asks whether the car is moving when it opens.
            register(app, object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) = sendDriving(app)
            }, "com.openlauncher.airplay.ASK_DRIVING")
            // Moving: the car's speed (CAN box) or GPS, above walking pace.
            scope.launch {
                combine(CarCore.data, CarLocation.get(app).location) { car, loc ->
                    val kmh = car.speedKmh?.toFloat() ?: ((loc?.speedMps ?: 0f) * 3.6f)
                    kmh >= 8f
                }.distinctUntilChanged().collect {
                    driving = it
                    sendDriving(app)
                }
            }
        }
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER))
        }
    }

    private fun sendDriving(c: Context) {
        runCatching {
            c.sendBroadcast(Intent(MmiLink.ACTION_DRIVING).setPackage(c.packageName).putExtra("driving", driving), MmiLink.PERMISSION)
        }
    }

    /** The iPhone's screen (it also opens by itself when the iPhone starts mirroring). */
    fun open(c: Context): Boolean = runCatching {
        c.startActivity(Intent(c, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
