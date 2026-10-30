package com.openlauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.openlauncher.app.util.AppUpdater

/** Result of an MMI update handed to Android's installer. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Not device owner: Android asks once ("Do you want to update MMI?").
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // MMI restarts on the new version.
            else -> AppUpdater.reportFailure(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed")
        }
    }
}
