package com.openlauncher.app.util

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speedMps: Float = 0f,
    val satellitesVisible: Int = -1,
    val satellitesUsed: Int = -1,
    val timestamp: Long = 0L
)

class LocationCompassManager(context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location  = MutableStateFlow<LocationData?>(null)
    private val _bearing   = MutableStateFlow(0f)
    val location: StateFlow<LocationData?> = _location
    val bearing: StateFlow<Float> = _bearing

    // Heading comes from GPS only: a magnetometer behind a dashboard full of metal
    // and speakers is unreliable, and polling it all the time cost frames.
    private var lastLocationForBearing: Location? = null
    // Both GPS and Network providers are registered at once (see start()), and
    // Network fixes (cell/WiFi) can be hundreds of meters coarser than GPS —
    // without gating, a coarse Network update stomps over a good GPS fix and
    // the displayed position visibly jumps between neighbourhoods every time
    // the two providers alternate. Track the best-accepted fix instead of
    // blindly taking whichever callback fires most recently.
    private var bestLocation: Location? = null
    private var satellitesVisible = -1
    private var satellitesUsed = -1

    private val gnssStatusCallback = @RequiresApi(Build.VERSION_CODES.N)
    object : GnssStatus.Callback() {
        @RequiresApi(Build.VERSION_CODES.N)
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var visible = 0
            var used = 0
            for (index in 0 until status.satelliteCount) {
                visible++
                if (status.usedInFix(index)) used++
            }
            satellitesVisible = visible
            satellitesUsed = used
            _location.value?.let { current ->
                _location.value = current.copy(
                    satellitesVisible = visible,
                    satellitesUsed = used
                )
            }
        }
    }

    /**
     * GPS (Doppler) speed is accurate to about 1 km/h once moving, but drifts a few
     * km/h while parked. Two thresholds: the car counts as moving above 5 km/h and
     * as stopped again below 2 km/h, so crawling in traffic still shows its speed
     * while a parked car reads a steady 0. A reading within its own error is 0 too.
     */
    @Volatile private var moving = false

    private fun parkedAsZero(loc: Location): Float {
        if (!loc.hasSpeed()) return 0f
        val v = loc.speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasSpeedAccuracy() && loc.speedAccuracyMetersPerSecond >= v) {
            moving = false
            return 0f
        }
        moving = if (moving) v > 0.55f else v > 1.4f
        return if (moving) v else 0f
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val best = bestLocation
            if (!isBetterLocation(loc.time, loc.accuracy, loc.provider, best?.time, best?.accuracy, best?.provider)) return
            bestLocation = loc

            _location.value = LocationData(
                latitude  = loc.latitude,
                longitude = loc.longitude,
                altitude  = loc.altitude,
                accuracy  = loc.accuracy,
                speedMps  = parkedAsZero(loc),
                satellitesVisible = satellitesVisible,
                satellitesUsed = satellitesUsed,
                timestamp = loc.time
            )

            // 1. If GPS has a hardware-computed bearing, use it (works offline)
            if (loc.hasBearing() && loc.bearing != 0f) {
                _bearing.value = loc.bearing
            } else {
                // 2. Math fallback: Calculate bearing between consecutive location points (works offline & sensor-less!)
                val lastLoc = lastLocationForBearing
                if (lastLoc != null) {
                    val distance = lastLoc.distanceTo(loc)
                    // Ensure the distance is enough to overcome GPS jitter (e.g. 3 meters)
                    if (distance > 1f) {
                        val computedBearing = lastLoc.bearingTo(loc)
                        // Normalize bearing to 0-360
                        _bearing.value = (computedBearing + 360f) % 360f
                        lastLocationForBearing = loc
                    }
                } else {
                    lastLocationForBearing = loc
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        // Must be overridden explicitly: the interface only gained default
        // implementations in API 30, so omitting them throws AbstractMethodError
        // on older head units when the GPS provider is toggled.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        // Location — Robust offline-first registration
        // GPS Provider (Works 100% offline, sat-based)
        try {
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback)
                }
                locationManager.requestLocationUpdates(
                    // As fast as the receiver delivers (often 1 Hz, some chips 5-10 Hz).
                    LocationManager.GPS_PROVIDER, 0L, 0f, locationListener
                )
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    locationListener.onLocationChanged(it)
                }
            }
        } catch (_: Exception) {}

        // Network Provider (Works online, cell/wifi-based)
//        try {
//            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
//                locationManager.requestLocationUpdates(
//                    LocationManager.NETWORK_PROVIDER, 200L, 0f, locationListener
//                )
//                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
//                    locationListener.onLocationChanged(it)
//                }
//            }
//        } catch (_: Exception) {}
    }

    fun stop() {
        locationManager.removeUpdates(locationListener)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
        lastLocationForBearing = null
        bestLocation = null
        satellitesVisible = -1
        satellitesUsed = -1
    }
}
