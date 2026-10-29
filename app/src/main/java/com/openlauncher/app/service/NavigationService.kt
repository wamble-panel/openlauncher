package com.openlauncher.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openlauncher.app.util.CarLocation

/**
 * Keeps GPS, voice guidance and the road-memory sensor alive while a route is
 * active or the car is moving, even with another app (ZLINK, the radio) in front.
 */
class NavigationService : Service() {

    override fun onCreate() {
        super.onCreate()
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        CarLocation.acquire(this, HOLDER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        CarLocation.release(HOLDER)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("MMI is running")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 7
        private const val HOLDER = "navigation"

        private val reasons = HashSet<String>()

        /** Keeps the service up for [reason] ("route", "drive") until [release]d. */
        @Synchronized
        fun acquire(context: Context, reason: String) {
            if (reasons.add(reason) && reasons.size == 1) {
                runCatching { ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java)) }
            }
        }

        @Synchronized
        fun release(context: Context, reason: String) {
            if (reasons.remove(reason) && reasons.isEmpty()) {
                runCatching { context.stopService(Intent(context, NavigationService::class.java)) }
            }
        }

        fun start(context: Context) = acquire(context, "route")

        fun stop(context: Context) = release(context, "route")
    }
}
