package com.openlauncher.app.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * System tuning beyond MMI itself. Root tweaks live only in memory (nothing is
 * written to the system partition): MMI re-applies the switched-on ones at every
 * boot, so switching one off and restarting returns the unit to stock.
 */
object SystemTuner {
    enum class Tweak(val label: String, val detail: String) {
        ZRAM("Compressed memory (ZRAM)", "About 1.5x usable RAM on 2 GB units; skipped if the unit already uses swap"),
        STORAGE("Faster storage", "Larger read-ahead and a weekly trim of the flash storage"),
        LOGGING("Lighter system logging", "Smaller log buffer, less background writing"),
        CPU("Full CPU speed", "The 4 cores stay at top speed instead of ramping up: smoother map, the unit runs a little warmer"),
    }

    private fun prefs(context: Context) = context.getSharedPreferences("mmi_tuner", Context.MODE_PRIVATE)

    fun isOn(context: Context, t: Tweak) = prefs(context).getBoolean(t.name, false)

    fun setOn(context: Context, t: Tweak, on: Boolean) = prefs(context).edit().putBoolean(t.name, on).apply()

    private fun commands(context: Context, t: Tweak): String = when (t) {
        // Only when nothing is swapping yet, so a vendor ZRAM setup is never touched.
        Tweak.ZRAM -> """
            if [ -e /sys/block/zram0 ] && ! grep -q zram /proc/swaps; then
              echo 1 > /sys/block/zram0/reset
              (echo lz4 > /sys/block/zram0/comp_algorithm) 2>/dev/null
              echo 805306368 > /sys/block/zram0/disksize
              mkswap /dev/block/zram0 && swapon /dev/block/zram0 -p 32758
            fi
            echo 100 > /proc/sys/vm/swappiness
        """.trimIndent()
        Tweak.STORAGE -> {
            val trim = System.currentTimeMillis() - prefs(context).getLong("last_trim", 0) > 7 * 86_400_000L
            buildString {
                append("for q in /sys/block/mmcblk*/queue /sys/block/sd*/queue; do [ -e \$q/read_ahead_kb ] && echo 512 > \$q/read_ahead_kb; done")
                if (trim) append("; fstrim /data; fstrim /cache")
            }
        }
        Tweak.LOGGING -> "logcat -G 256K"
        Tweak.CPU -> "for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo performance > \$g; done"
    }

    /** Applies every switched-on root tweak (at boot and when switched on). */
    suspend fun applyEnabled(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!Root.available()) return@withContext false
        val on = Tweak.entries.filter { isOn(context, it) }
        if (on.isEmpty()) return@withContext true
        val ok = Root.run(on.joinToString("\n") { commands(context, it) }, requireSuccess = false) != null
        if (ok && Tweak.STORAGE in on) prefs(context).edit().putLong("last_trim", System.currentTimeMillis()).apply()
        ok
    }

    /**
     * Closes idle background apps to free memory, never MMI or the car's own
     * software. Root closes every cached process; without root, Android only
     * allows closing other apps' background processes.
     */
    suspend fun freeMemory(context: Context): Long = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun avail() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val before = avail()
        if (Root.available()) {
            Root.run("am kill-all", requireSuccess = false)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .forEach { runCatching { am.killBackgroundProcesses(it.packageName) } }
        }
        kotlinx.coroutines.delay(800)
        ((avail() - before) shr 20).coerceAtLeast(0)
    }

    /**
     * Compiles MMI fully ahead of time (and lets Android finish pending
     * optimisation of other apps), so screens open without stutter. Root only;
     * the PC install script does the same for units without root.
     */
    suspend fun compileApps(context: Context): Boolean = withContext(Dispatchers.IO) {
        Root.run("cmd package compile -m speed -f ${context.packageName}; cmd package bg-dexopt-job", requireSuccess = false) != null
    }

    /** Hottest thermal sensor in °C, or null if the unit doesn't expose one. */
    fun cpuTemperature(): Int? = runCatching {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.mapNotNull { z -> File(z, "temp").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull() }
            ?.map { if (it > 1000) it / 1000 else it }
            ?.filter { it in 1..150 }
            ?.maxOrNull()
    }.getOrNull()

    fun memoryLine(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${mi.availMem shr 20} MB free of ${mi.totalMem shr 20} MB"
    }
}
