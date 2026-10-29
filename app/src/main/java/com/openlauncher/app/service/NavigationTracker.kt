package com.openlauncher.app.service

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live turn-by-turn guidance mirrored from a navigation app's ongoing notification. */
data class NavGuidance(
    val instruction: String,   // e.g. "200 m" or "Turn left"
    val detail: String?,       // e.g. "onto High Street" / "toward Liverpool"
    val eta: String?,          // e.g. "12 min · 5.1 km · 15:42 ETA"
    val arrow: Bitmap?,        // the app's manoeuvre arrow, when it provides one
    val sourcePackage: String,
)

/**
 * Google Maps, Waze and others post an ongoing "navigation" notification while
 * guidance is active. MediaListenerService feeds every posted/removed
 * notification through here; we keep the latest guidance from a known nav app.
 */
object NavigationTracker {
    private val NAV_APPS = setOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.here.app.maps",
        "com.sygic.aura",
        "net.osmand",
        "net.osmand.plus",
        "app.organicmaps",
    )

    private val _guidance = MutableStateFlow<NavGuidance?>(null)
    val guidance: StateFlow<NavGuidance?> = _guidance

    private var activeKey: String? = null

    fun onPosted(sbn: StatusBarNotification, context: Context) {
        if (sbn.packageName !in NAV_APPS) return
        val n = sbn.notification ?: return
        val isNavigation = n.category == "navigation" ||
            (sbn.packageName == "com.waze" && n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (!isNavigation) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (title.isNullOrEmpty()) return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

        activeKey = sbn.key
        _guidance.value = NavGuidance(
            instruction = title,
            detail = text?.takeIf { it.isNotEmpty() },
            eta = sub?.takeIf { it.isNotEmpty() },
            arrow = largeIcon(n, context),
            sourcePackage = sbn.packageName,
        )
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.key == activeKey) clear()
    }

    fun clear() {
        activeKey = null
        _guidance.value = null
    }

    private fun largeIcon(n: Notification, context: Context): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (n.getLargeIcon()?.loadDrawable(context) as? BitmapDrawable)?.bitmap
        } else {
            @Suppress("DEPRECATION")
            n.extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap
        }
    }.getOrNull()
}
