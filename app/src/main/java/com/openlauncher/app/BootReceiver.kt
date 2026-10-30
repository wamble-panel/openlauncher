package com.openlauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Confirmed on-device: even with OpenLauncher correctly set as the default
 * home app, the OEM launcher still wins actual cold-boot foreground — it
 * very likely self-launches from its own BOOT_COMPLETED receiver, which
 * isn't gated by "who's the default home app" at all. Gives OpenLauncher
 * the same self-assertion, so it's competing on equal footing instead of
 * just losing by default.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Root tweaks live only in memory; re-apply the switched-on ones after every start.
        val pending = goAsync()
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { com.openlauncher.app.util.SystemTuner.applyEnabled(context) } }
            pending.finish()
        }.start()
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
