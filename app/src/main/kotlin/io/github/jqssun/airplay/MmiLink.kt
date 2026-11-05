package io.github.jqssun.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService

/**
 * MMI: the link to the MMI car screens (com.openlauncher.app). Both apps are signed
 * with the owner's key and share a signature permission, so only MMI can start this
 * receiver or read its state, and only this app can send that state to MMI.
 */
object MmiLink {
    const val PERMISSION = "com.openlauncher.permission.MMI_LINK"
    const val MMI_PACKAGE = "com.openlauncher.app"
    const val ACTION_START = "com.openlauncher.airplay.START"
    const val ACTION_QUERY = "com.openlauncher.airplay.QUERY"
    const val ACTION_STATE = "com.openlauncher.airplay.STATE"

    data class State(
        val running: Boolean,
        val mirroring: Boolean,
        val audio: Boolean,
        val video: Boolean,
        val title: String,
        val artist: String,
    )

    @Volatile private var last = State(false, false, false, false, "", "")

    fun send(c: Context, s: State) {
        last = s
        val i = Intent(ACTION_STATE).setPackage(MMI_PACKAGE)
            .putExtra("running", s.running)
            .putExtra("mirroring", s.mirroring)
            .putExtra("audio", s.audio)
            .putExtra("video", s.video)
            .putExtra("title", s.title)
            .putExtra("artist", s.artist)
            .putExtra("name", c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)[Prefs.SERVER_NAME].ifBlank { Prefs.SERVER_NAME.def })
        runCatching { c.sendBroadcast(i, PERMISSION) }
    }

    /** MMI asks to start the receiver, or for the current state. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER),
                    )
                }
                ACTION_QUERY -> send(context, last)
            }
        }
    }
}
