package com.openlauncher.app.model

/** The unit's FM/AM tuner as mirrored from the vendor MCU or the radio app's media session. */
data class HardwareRadioState(
    val band: String,
    val freq: String,
    val stationName: String? = null,
    // Direct tuning + FM1/FM2/FM3/AM switching — vendor MCU backend only
    val canTune: Boolean = false,
) {
    val display get() = "$band  $freq"
    val isAm get() = band.equals("AM", ignoreCase = true)
}
