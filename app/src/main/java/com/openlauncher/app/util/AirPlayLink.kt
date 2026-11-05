package com.openlauncher.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MMI AirPlay (com.openlauncher.airplay, a separate app): the iPhone's screen and music
 * over Wi-Fi. Both apps are signed with the owner's key and share a signature
 * permission, so only that app can send MMI its state and only MMI can start it.
 */
object AirPlayLink {
    const val PACKAGE = "com.openlauncher.airplay"
    private const val PERMISSION = "com.openlauncher.permission.MMI_LINK"
    private const val ACTION_STATE = "com.openlauncher.airplay.STATE"
    private const val ACTION_START = "com.openlauncher.airplay.START"
    private const val ACTION_QUERY = "com.openlauncher.airplay.QUERY"
    /** Where the owner installs it from. */
    const val DOWNLOAD = "https://github.com/wamble-panel/openlauncher/releases/download/airplay-latest/MMI-AirPlay.apk"

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
    private var started = false

    fun installed(c: Context): Boolean =
        runCatching { c.packageManager.getApplicationInfo(PACKAGE, 0).enabled }.getOrDefault(false)

    /** Listens for the receiver's state and makes sure it is running (it also starts itself at boot). */
    fun start(c: Context) {
        val app = c.applicationContext
        if (!started) {
            started = true
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
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
            }
            // Only senders holding the shared permission (the AirPlay app) get through.
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, IntentFilter(ACTION_STATE), PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(r, IntentFilter(ACTION_STATE), PERMISSION, null)
            }
        }
        if (!installed(app)) return
        send(app, ACTION_START)
        send(app, ACTION_QUERY)
    }

    private fun send(c: Context, action: String) =
        runCatching { c.sendBroadcast(Intent(action).setPackage(PACKAGE), PERMISSION) }

    /** The receiver's own screen (mirroring shows there; it also opens by itself when the iPhone starts). */
    fun open(c: Context): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}
