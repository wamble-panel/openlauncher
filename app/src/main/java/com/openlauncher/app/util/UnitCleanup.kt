package com.openlauncher.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Verdict(val label: String) { KEEP("Keep — needed in the car"), OFF("Switch off — not needed in a car"), UNSURE("Unsure — check first") }

data class UnitApp(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
    val verdict: Verdict,
    val reason: String,
    val memoryMb: Int?,
)

/**
 * Sorts everything installed on the head unit into what the car needs, what a car
 * never needs, and what to check first; then switches the junk off (root) or writes
 * an ADB script for a PC. Switching off is `pm disable-user`: nothing is deleted
 * and every step has an undo.
 */
object UnitCleanup {

    /** Core Android and car plumbing: never touch. */
    private val KEEP_EXACT = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.bluetooth",
        "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.externalstorage",
        "com.android.documentsui", "com.google.android.documentsui", "com.android.server.telecom", "com.android.location.fused",
        "com.android.networkstack", "com.android.wifi.resources", "com.android.webview", "com.google.android.webview",
        "com.google.android.tts", "com.svox.pico", "com.android.keychain", "com.android.certinstaller",
        "com.android.inputdevices", "com.android.statementservice", "com.android.se", "com.android.nfc",
        "com.android.media.module", "com.android.mtp", "com.android.vpndialogs", "com.android.wallpaperbackup",
    )
    private val KEEP_PREFIX = listOf(
        "com.android.providers.", "com.android.inputmethod", "com.google.android.inputmethod", "com.android.server.",
        "android.ext.", "com.android.networkstack", "com.android.wifi", "com.android.bluetooth", "com.mediatek.ims",
        "com.android.cellbroadcast", "com.android.carrierconfig", "com.android.overlay", "android.overlay",
    )
    /** Words that mark the vendor's car software: MCU bridge, CAN box, radio, keys, camera, sound. */
    private val CAR_WORDS = listOf(
        "mcu", "canbus", "can_", ".can.", "carservice", "car.service", "carinfo", "radio", "fmradio", "tuner",
        "steer", "swc", "wheelkey", "keylearn", "bt", "bluetooth", "btphone", "dial", "camera", "avm", "reverse",
        "backcar", "rearview", "amp", "eq", "dsp", "sound", "audio", "factory", "setting", "mcuupdate", "ime",
        "input", "launcher.provider", "systemservice", "eventcenter", "szchoiceway", "service",
    )
    /** Things a car screen never needs. */
    private val OFF_WORDS = listOf(
        "browser", "email", "calendar", "gallery", "gallery3d", "video", "player", "movie", "camera2", "photos",
        "youtube", "game", "demo", "wallpaper", "livewallpaper", "theme", "news", "weather", "clock", "deskclock",
        "calculator", "notes", "recorder", "soundrecorder", "mtklogger", "logger", "log.", "feedback", "analytics",
        "stk", "launcher", "facebook", "instagram", "tiktok", "store", "market", "printspooler", "bookmark",
        "partnerbookmarks", "dreams", "screensaver", "magnifier", "easter", "chrome",
    )
    /** Decide case by case: may be in use (phone projection, Google services, file transfer). */
    private val UNSURE_WORDS = listOf(
        "zlink", "carplay", "androidauto", "autolink", "projection", "gms", "gsf", "vending", "googlequicksearchbox",
        "maps", "sendfile", "tv", "update", "ota", "music", "media", "lion", "speech", "assistant", "voice",
    )

    private fun classify(pkg: String, label: String, system: Boolean, own: String): Pair<Verdict, String> {
        val p = pkg.lowercase()
        val l = label.lowercase()
        fun any(words: List<String>) = words.firstOrNull { it in p || it in l }
        if (pkg == own) return Verdict.KEEP to "MMI itself"
        if (pkg in KEEP_EXACT || KEEP_PREFIX.any { p.startsWith(it) }) return Verdict.KEEP to "Core Android"
        // Car words win over "launcher"/"player" etc. (e.g. "bt music player", "radio launcher").
        any(listOf("mcu", "canbus", "can_", ".can.", "carservice", "car.service", "radio", "tuner", "steer", "swc",
            "keylearn", "bluetooth", "btphone", "avm", "reverse", "backcar", "rearview", "eventcenter", "szchoiceway", "factory"))
            ?.let { return Verdict.KEEP to "Car hardware ($it)" }
        any(UNSURE_WORDS)?.let { return Verdict.UNSURE to "Might be in use ($it)" }
        any(OFF_WORDS)?.let { return Verdict.OFF to "Not needed in a car ($it)" }
        any(CAR_WORDS)?.let { return Verdict.UNSURE to "Possibly car-related ($it)" }
        return if (system) Verdict.UNSURE to "System app, unknown purpose" else Verdict.OFF to "Installed app, not needed in a car"
    }

    suspend fun scan(context: Context): List<UnitApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val memory = if (Root.available()) memoryByPackage() else emptyMap()
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_DISABLED_COMPONENTS or PackageManager.GET_UNINSTALLED_PACKAGES
        pm.getInstalledApplications(flags).map { ai ->
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val (verdict, reason) = classify(ai.packageName, label, system, context.packageName)
            UnitApp(ai.packageName, label, system, ai.enabled, verdict, reason, memory[ai.packageName])
        }.sortedWith(compareBy<UnitApp> { it.verdict.ordinal }.thenByDescending { it.memoryMb ?: 0 }.thenBy { it.label.lowercase() })
    }

    /** Resident memory per package from `dumpsys meminfo` (needs root). */
    private fun memoryByPackage(): Map<String, Int> {
        val out = Root.run("dumpsys meminfo") ?: return emptyMap()
        val map = HashMap<String, Int>()
        // Lines like: "   123,456K: com.vendor.app (pid 1234)"
        val re = Regex("""^\s*([\d,]+)K: ([\w.]+)(?::\w+)? \(pid""")
        for (line in out.lineSequence()) {
            val m = re.find(line) ?: continue
            val kb = m.groupValues[1].replace(",", "").toIntOrNull() ?: continue
            val pkg = m.groupValues[2]
            map[pkg] = (map[pkg] ?: 0) + kb / 1024
        }
        return map
    }

    private fun prefs(context: Context) = context.getSharedPreferences("mmi_cleanup", Context.MODE_PRIVATE)

    /** Packages MMI switched off, so "undo" restores exactly those. */
    fun switchedOff(context: Context): Set<String> = prefs(context).getStringSet("off", emptySet())!!.toSet()

    /** Switches packages off with root and remembers them for undo. */
    suspend fun applyWithRoot(context: Context, off: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (off.isEmpty()) return@withContext true
        val ok = Root.run(off.joinToString("; ") { "pm disable-user --user 0 $it >/dev/null 2>&1" }) != null
        if (ok) prefs(context).edit().putStringSet("off", switchedOff(context) + off).apply()
        ok
    }

    /** Turns back on everything MMI switched off. */
    suspend fun undoWithRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        val off = switchedOff(context)
        if (off.isEmpty()) return@withContext true
        val ok = Root.run(off.joinToString("; ") { "pm enable $it >/dev/null 2>&1" }) != null
        if (ok) prefs(context).edit().remove("off").apply()
        ok
    }

    /**
     * Writes cleanup + undo scripts and the package list to Download, for running
     * from a PC over ADB (no root needed) or sending over for review.
     */
    suspend fun writeScripts(context: Context, apps: List<UnitApp>, off: Set<String>): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val tweaks = speedTweakCommands(context.packageName)
            val bat = buildString {
                appendLine("@echo off")
                appendLine("rem MMI unit cleanup: switches off apps a car does not need (nothing is deleted).")
                appendLine("rem Undo with mmi-cleanup-undo.bat")
                appendLine("adb devices")
                off.forEach { appendLine("adb shell pm disable-user --user 0 $it") }
                tweaks.forEach { appendLine("adb shell $it") }
                appendLine("echo Done. Restart the unit.")
                appendLine("pause")
            }
            val sh = "#!/bin/sh\n# MMI unit cleanup (nothing is deleted). Undo: mmi-cleanup-undo.sh\nadb devices\n" +
                off.joinToString("") { "adb shell pm disable-user --user 0 $it\n" } +
                tweaks.joinToString("") { "adb shell $it\n" } + "echo Done. Restart the unit.\n"
            val undo = off.joinToString("") { "adb shell pm enable $it\n" }
            File(dl, "mmi-cleanup.bat").writeText(bat.replace("\n", "\r\n"))
            File(dl, "mmi-cleanup.sh").writeText(sh)
            File(dl, "mmi-cleanup-undo.bat").writeText(("@echo off\nadb devices\n" + undo + "pause\n").replace("\n", "\r\n"))
            File(dl, "mmi-cleanup-undo.sh").writeText("#!/bin/sh\nadb devices\n$undo")
            File(dl, "mmi-packages.txt").writeText(apps.joinToString("\n") {
                "${it.verdict.name}\t${if (it.enabled) "on" else "off"}\t${it.memoryMb ?: "-"} MB\t${it.pkg}\t${it.label}\t${it.reason}"
            })
            dl
        }.getOrNull()
    }

    /** System speed settings: snappier animations, no idle Wi-Fi/BLE scanning, MMI kept awake. */
    fun speedTweakCommands(pkg: String): List<String> = listOf(
        "settings put global window_animation_scale 0.5",
        "settings put global transition_animation_scale 0.5",
        "settings put global animator_duration_scale 0.5",
        "settings put global wifi_scan_always_enabled 0",
        "settings put global ble_scan_always_enabled 0",
        "settings put global package_verifier_enable 0",
        "dumpsys deviceidle whitelist +$pkg",
        "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
    )

    /** Applies the speed settings from MMI itself (root, or WRITE_SECURE_SETTINGS granted over ADB). */
    suspend fun applySpeedTweaks(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (Root.available()) return@withContext Root.run(speedTweakCommands(context.packageName).joinToString("; ")) != null
        runCatching {
            val cr = context.contentResolver
            listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").forEach {
                android.provider.Settings.Global.putFloat(cr, it, 0.5f)
            }
            android.provider.Settings.Global.putInt(cr, "wifi_scan_always_enabled", 0)
            android.provider.Settings.Global.putInt(cr, "ble_scan_always_enabled", 0)
            true
        }.getOrDefault(false)
    }

    fun speedTweaksApplied(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(context.contentResolver, "animator_duration_scale", 1f) <= 0.5f
    }.getOrDefault(false)
}

/** Minimal su helper. */
object Root {
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su", "/vendor/bin/su")

    fun available(): Boolean = SU_PATHS.any { File(it).exists() }

    /** Runs a shell command as root; returns its output, or null if root was refused or failed. */
    fun run(command: String): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    }.getOrNull()
}
