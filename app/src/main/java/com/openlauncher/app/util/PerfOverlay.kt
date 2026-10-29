package com.openlauncher.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Switch for the on-screen performance readout (frames, slow frames, free memory). */
object PerfOverlay {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    fun init(context: Context) {
        _enabled.value = context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).getBoolean("perf_overlay", false)
    }

    fun setEnabled(context: Context, on: Boolean) {
        _enabled.value = on
        context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).edit().putBoolean("perf_overlay", on).apply()
    }
}
