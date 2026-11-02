package com.openlauncher.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Does the whole "firmware-grade" tune-up from inside MMI, using root when the unit
 * has it (su already present — MMI can use root but cannot create it). Everything is
 * what the USB setup script does, so no laptop is needed on a rooted unit. Each step is
 * reversible; [undo] puts it back.
 */
object RootOptimize {
    const val MMI = "com.openlauncher.app"

    /** Vendor apps MMI fully replaces (the "safe" rows of the Vendor apps list). */
    private val SAFE_OFF = listOf(
        "com.dofun.variety", "com.dofun.carassistant.car", "com.dofun.dofunweather.main",
        "com.tw.video", "com.tw.video.theme", "com.dofun.market",
    )

    data class Status(val rooted: Boolean, val wifiAdbOn: Boolean, val optimized: Boolean)

    fun status(context: Context) = Status(
        rooted = Root.available(),
        wifiAdbOn = runCatching {
            @Suppress("PrivateApi")
            (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "service.adb.tcp.port") as String) == "5555"
        }.getOrDefault(false),
        optimized = context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).getBoolean("root_optimized", false),
    )

    /** Runs the tune-up. Returns the lines of what happened, for the screen. */
    suspend fun optimize(context: Context, enableWifiAdb: Boolean): List<String> = withContext(Dispatchers.IO) {
        if (!Root.available()) return@withContext listOf("No root on this unit — nothing changed.")
        val log = ArrayList<String>()
        fun step(label: String, cmd: String) {
            val ok = Root.run(cmd) != null
            log += (if (ok) "✓ " else "✗ ") + label
        }
        step("MMI compiled to native code", "cmd package compile -m speed -f $MMI")
        step("MMI kept as home", "cmd package set-home-activity $MMI/.MainActivity")
        step("MMI kept awake", "dumpsys deviceidle whitelist +$MMI")
        step("Vendor climate pop-up off", "settings put system AIR_POPUP_WINDOW 0")
        step("Vendor door pop-up off", "settings put system SYSTEM_DOOR2 1")
        step("Faster animations", "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5")
        step("No idle Wi-Fi/BT scanning", "settings put global wifi_scan_always_enabled 0; settings put global ble_scan_always_enabled 0")
        var disabled = 0
        for (p in SAFE_OFF) if (Root.run("pm disable-user --user 0 $p") != null) disabled++
        log += "✓ Disabled $disabled vendor app(s)"
        if (enableWifiAdb) {
            // Turn adb on over the network so a laptop can connect with no cable (adb connect <ip>:5555).
            val ok = Root.run("setprop service.adb.tcp.port 5555; stop adbd; start adbd") != null
            log += (if (ok) "✓ Wi-Fi adb on (port 5555)" else "✗ Wi-Fi adb")
        }
        context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).edit().putBoolean("root_optimized", true).apply()
        log += listOf("", "Done. Restart the unit to finish.")
        log
    }

    suspend fun undo(context: Context): List<String> = withContext(Dispatchers.IO) {
        if (!Root.available()) return@withContext listOf("No root — nothing to undo.")
        val log = ArrayList<String>()
        Root.run("settings put system AIR_POPUP_WINDOW 1; settings put system SYSTEM_DOOR2 0")
        Root.run("settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1")
        Root.run("settings delete global wifi_scan_always_enabled; settings delete global ble_scan_always_enabled")
        var enabled = 0
        for (p in SAFE_OFF) if (Root.run("pm enable $p") != null) enabled++
        Root.run("cmd package compile --reset $MMI")
        context.getSharedPreferences("mmi_prefs", Context.MODE_PRIVATE).edit().putBoolean("root_optimized", false).apply()
        log += "✓ Restored vendor apps and settings ($enabled app(s)). Restart the unit."
        log
    }

    /**
     * Backs up the key partitions to a USB stick / SD card at [dir], for building the
     * firmware. Root reads the raw partitions; nothing on the unit changes.
     */
    suspend fun backupPartitions(dir: String): List<String> = withContext(Dispatchers.IO) {
        if (!Root.available()) return@withContext listOf("Backup needs root.")
        val log = ArrayList<String>()
        Root.run("mkdir -p $dir")
        // The by-name links let us copy each partition by its name.
        val names = Root.run("ls /dev/block/by-name")?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
        if (names.isEmpty()) return@withContext listOf("Couldn't list partitions.")
        // Only the ones a firmware build needs (skip userdata/cache: huge and private).
        val wanted = names.filter { it in setOf("boot", "dtbo", "vbmeta", "vbmeta_system", "vbmeta_vendor", "super", "system_tw", "misc", "env", "bootloader") }
        for (n in wanted) {
            val ok = Root.run("dd if=/dev/block/by-name/$n of=$dir/$n.img bs=4M") != null
            log += (if (ok) "✓ " else "✗ ") + "$n.img"
        }
        log += listOf("", "Saved to $dir. Give these to the developer for the firmware.")
        log
    }
}
