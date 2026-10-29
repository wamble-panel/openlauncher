package com.openlauncher.app.data

import android.content.Context
import android.text.format.DateFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The few user settings the MMI shell has, in plain SharedPreferences, shared process-wide. */
class CarPrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE)

    private val _metric = MutableStateFlow(prefs.getBoolean("metric", true))
    val metric: StateFlow<Boolean> = _metric

    private val _clock24 = MutableStateFlow(prefs.getBoolean("clock24", DateFormat.is24HourFormat(context)))
    val clock24: StateFlow<Boolean> = _clock24

    private val _hiddenApps = MutableStateFlow(prefs.getStringSet("hidden_apps", emptySet())!!.toSet())
    val hiddenApps: StateFlow<Set<String>> = _hiddenApps

    /** Package of the vendor radio app; empty = detect automatically. */
    val radioPackage: String get() = prefs.getString("radio_package", "")!!

    fun setMetric(on: Boolean) {
        prefs.edit().putBoolean("metric", on).apply()
        _metric.value = on
    }

    fun setClock24(on: Boolean) {
        prefs.edit().putBoolean("clock24", on).apply()
        _clock24.value = on
    }

    fun toggleHidden(pkg: String) {
        val next = _hiddenApps.value.let { if (pkg in it) it - pkg else it + pkg }
        prefs.edit().putStringSet("hidden_apps", next).apply()
        _hiddenApps.value = next
    }

    companion object {
        @Volatile
        private var instance: CarPrefs? = null

        fun get(context: Context): CarPrefs =
            instance ?: synchronized(this) { instance ?: CarPrefs(context.applicationContext).also { instance = it } }
    }
}
