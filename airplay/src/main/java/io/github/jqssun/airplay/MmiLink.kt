package io.github.jqssun.airplay

import android.content.Context
import android.content.Intent

/**
 * MMI: the receiver's state for MMI's screens. The receiver runs in its own process
 * (":airplay") inside MMI, so a crash there never takes the launcher down; this
 * broadcast stays inside MMI (own package, own signature permission).
 */
object MmiLink {
    const val PERMISSION = "com.openlauncher.permission.MMI_LINK"
    const val ACTION_STATE = "com.openlauncher.airplay.STATE"
    /** MMI tells the screen whether the car is moving (video is hidden then). */
    const val ACTION_DRIVING = "com.openlauncher.airplay.DRIVING"

    data class State(
        val running: Boolean,
        val mirroring: Boolean,
        val audio: Boolean,
        val video: Boolean,
        val title: String,
        val artist: String,
    )

    fun send(c: Context, s: State) {
        val i = Intent(ACTION_STATE).setPackage(c.packageName)
            .putExtra("running", s.running)
            .putExtra("mirroring", s.mirroring)
            .putExtra("audio", s.audio)
            .putExtra("video", s.video)
            .putExtra("title", s.title)
            .putExtra("artist", s.artist)
            .putExtra("name", c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)[Prefs.SERVER_NAME].ifBlank { Prefs.SERVER_NAME.def })
        runCatching { c.sendBroadcast(i, PERMISSION) }
    }
}
