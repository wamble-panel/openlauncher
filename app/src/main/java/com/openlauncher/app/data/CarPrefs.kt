package com.openlauncher.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The few user settings the MMI shell has, in plain SharedPreferences. */
class CarPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE)

    private val _metric = MutableStateFlow(prefs.getBoolean("metric", true))
    val metric: StateFlow<Boolean> = _metric

    /** Package of the vendor radio app; empty = detect automatically. */
    val radioPackage: String get() = prefs.getString("radio_package", "")!!

    fun setMetric(on: Boolean) {
        prefs.edit().putBoolean("metric", on).apply()
        _metric.value = on
    }
}
