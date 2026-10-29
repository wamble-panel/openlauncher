package com.openlauncher.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * One GPS/compass listener for the whole process. The home screen and the
 * navigation service each hold a reference, so guidance keeps receiving fixes
 * while another app (ZLINK, the radio) is in front.
 */
object CarLocation {
    private var manager: LocationCompassManager? = null
    private val holders = HashSet<String>()

    fun get(context: Context): LocationCompassManager =
        manager ?: LocationCompassManager(context.applicationContext).also { manager = it }

    @Synchronized
    fun acquire(context: Context, holder: String) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted || !holders.add(holder)) return
        if (holders.size == 1) runCatching { get(context).start() }
    }

    @Synchronized
    fun release(holder: String) {
        if (!holders.remove(holder)) return
        if (holders.isEmpty()) manager?.stop()
    }
}
