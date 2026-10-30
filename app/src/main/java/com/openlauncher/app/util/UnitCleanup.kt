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
        // Fallback home screen (if MMI ever crashes) and, on Android 10+, the Recents screen.
        "com.android.launcher3", "com.android.quickstep", "com.google.android.apps.nexuslauncher",
    )
    private val KEEP_PREFIX = listOf(
        "com.android.providers.", "com.android.inputmethod", "com.google.android.inputmethod", "com.android.server.",
        "android.ext.", "com.android.networkstack", "com.android.wifi", "com.android.bluetooth", "com.mediatek.ims",
        "com.android.cellbroadcast", "com.android.carrierconfig", "com.android.overlay", "android.overlay",
    )
    /** The vendor's car hardware software (MCU bridge, CAN box, radio, keys, camera): always kept. */
    private val CAR_HARDWARE_WORDS = listOf(
        "mcu", "canbus", "can_", ".can.", "carservice", "car.service", "carinfo", "radio", "fmradio", "tuner",
        "steer", "swc", "wheelkey", "keylearn", "bluetooth", "btphone", "avm", "reverse", "backcar", "rearview",
        "eventcenter", "szchoiceway", "factory", "mcuupdate",
    )
    /** Weaker hints of car software: kept as "unsure" rather than switched off. */
    private val CAR_WORDS = listOf(
        "bt", "dial", "camera", "amp", "eq", "dsp", "sound", "audio", "setting", "ime", "input",
        "launcher.provider", "systemservice", "service",
    )
    /** Things a car screen never needs. */
    private val OFF_WORDS = listOf(
        "browser", "email", "calendar", "gallery", "gallery3d", "video", "player", "movie", "camera2", "photos",
        "youtube", "game", "demo", "wallpaper", "livewallpaper", "theme", "news", "weather", "clock", "deskclock",
        "calculator", "notes", "recorder", "soundrecorder", "mtklogger", "logger", "log.", "feedback", "analytics",
        "stk", "facebook", "instagram", "tiktok", "store", "market", "printspooler", "bookmark",
        "partnerbookmarks", "dreams", "screensaver", "magnifier", "easter", "chrome",
    )
    /** Decide case by case: may be in use (phone projection, Google services, file transfer). */
    private val UNSURE_WORDS = listOf(
        "zlink", "carplay", "androidauto", "autolink", "projection", "gms", "gsf", "vending", "googlequicksearchbox",
        "maps", "sendfile", "tv", "update", "ota", "music", "media", "lion", "speech", "assistant", "voice",
        "launcher",
    )

    private fun classify(pkg: String, label: String, system: Boolean, own: String): Pair<Verdict, String> {
        val p = pkg.lowercase()
        val l = label.lowercase()
        fun any(words: List<String>) = words.firstOrNull { it in p || it in l }
        if (pkg == own) return Verdict.KEEP to "MMI itself"
        if (pkg in KEEP_EXACT || KEEP_PREFIX.any { p.startsWith(it) }) return Verdict.KEEP to "Core Android"
        // Car hardware wins over "launcher"/"player" etc. (e.g. "bt music player", "radio launcher").
        any(CAR_HARDWARE_WORDS)?.let { return Verdict.KEEP to "Car hardware ($it)" }
        any(UNSURE_WORDS)?.let { return Verdict.UNSURE to if (it == "launcher") "Spare home screen — keep one" else "Might be in use ($it)" }
        any(OFF_WORDS)?.let { return Verdict.OFF to "Not needed in a car ($it)" }
        any(CAR_WORDS)?.let { return Verdict.UNSURE to "Possibly car-related ($it)" }
        // Apps the owner installed (Waze, Torque, ...) are never pre-ticked.
        return Verdict.UNSURE to if (system) "System app, unknown purpose" else "Installed by you"
    }

    suspend fun scan(context: Context): List<UnitApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val memory = if (Root.available()) cachedMemory() else emptyMap()
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_DISABLED_COMPONENTS or PackageManager.GET_UNINSTALLED_PACKAGES
        pm.getInstalledApplications(flags).map { ai ->
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val (verdict, reason) = classify(ai.packageName, label, system, context.packageName)
            UnitApp(ai.packageName, label, system, ai.enabled, verdict, reason, memory[ai.packageName])
        }.sortedWith(compareBy<UnitApp> { it.verdict.ordinal }.thenByDescending { it.memoryMb ?: 0 }.thenBy { it.label.lowercase() })
    }

    @Volatile private var memoryCache: Pair<Long, Map<String, Int>>? = null

    /** Memory per app, fetched as root at most every 5 minutes (dumpsys is slow and shows a root toast). */
    private fun cachedMemory(): Map<String, Int> {
        val now = System.currentTimeMillis()
        memoryCache?.let { (at, map) -> if (now - at < 300_000) return map }
        return memoryByPackage().also { memoryCache = now to it }
    }

    /** PSS per package from the "Total PSS by process" section of `dumpsys meminfo` (needs root). */
    private fun memoryByPackage(): Map<String, Int> {
        val out = Root.run("dumpsys meminfo") ?: return emptyMap()
        val map = HashMap<String, Int>()
        // Lines like: "   123,456K: com.vendor.app (pid 1234)". Each process is listed again under
        // other sections (by OOM adjustment, RSS), so only this one section is counted.
        val re = Regex("""^\s*([\d,]+)K: ([\w.]+)(?::\w+)? \(pid""")
        var inSection = false
        for (line in out.lineSequence()) {
            if (line.startsWith("Total PSS by process")) { inSection = true; continue }
            if (!inSection) continue
            if (line.isNotEmpty() && !line[0].isWhitespace()) break // next section header
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

    /** Runs [command] for each package in one root shell; returns the packages it succeeded for. */
    private fun forEachPackage(command: String, packages: Collection<String>): Set<String>? {
        if (packages.isEmpty()) return emptySet()
        val script = packages.joinToString("; ") { "$command $it >/dev/null 2>&1 && echo OK:$it" }
        // The last command's exit code says nothing about the others, so read each OK line.
        val out = Root.run(script, requireSuccess = false) ?: return null
        return out.lineSequence().filter { it.startsWith("OK:") }.map { it.removePrefix("OK:").trim() }.toSet()
    }

    data class RootResult(val done: Int, val failed: List<String>)

    /** Switches packages off with root and remembers exactly the ones that went off, for undo. */
    suspend fun applyWithRoot(context: Context, off: List<String>): RootResult? = withContext(Dispatchers.IO) {
        val done = forEachPackage("pm disable-user --user 0", off) ?: return@withContext null
        if (done.isNotEmpty()) prefs(context).edit().putStringSet("off", switchedOff(context) + done).apply()
        RootResult(done.size, off.filter { it !in done })
    }

    /** Turns back on everything MMI switched off; anything that fails stays listed for another try. */
    suspend fun undoWithRoot(context: Context): RootResult? = withContext(Dispatchers.IO) {
        val off = switchedOff(context)
        val done = forEachPackage("pm enable", off) ?: return@withContext null
        prefs(context).edit().putStringSet("off", off - done).apply()
        RootResult(done.size, off.filter { it !in done })
    }

    /**
     * Writes cleanup + undo scripts and the package list to Download, for running
     * from a PC over ADB (no root needed) or sending over for review.
     */
    suspend fun writeScripts(context: Context, apps: List<UnitApp>, off: Set<String>): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val tweaks = speedTweakCommands(context.packageName)
            val revert = revertCommands(context)
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
            val undo = off.joinToString("") { "adb shell pm enable $it\n" } + revert.joinToString("") { "adb shell $it\n" }
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

    /**
     * Copies the APK of every vendor/system app (Google apps skipped) to
     * Download/mmi-apks, so the vendor's car software can be studied. System app
     * files are world-readable, so this needs no root. Returns how many were copied.
     */
    suspend fun exportApks(context: Context, onProgress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        // Never crash: a unit that refuses writing to Download just reports 0 copied.
        runCatching { exportApksOrThrow(context, onProgress) }.getOrDefault(0)
    }

    private suspend fun exportApksOrThrow(context: Context, onProgress: (Int, Int) -> Unit): Int {
        val dl = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mmi-apks").apply { mkdirs() }
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(PackageManager.GET_DISABLED_COMPONENTS)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && !it.packageName.startsWith("com.google.") }
        var done = 0
        apps.forEachIndexed { i, ai ->
            runCatching {
                val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                files.forEachIndexed { n, path ->
                    val name = if (n == 0) "${ai.packageName}.apk" else "${ai.packageName}.split$n.apk"
                    File(path).copyTo(File(dl, name), overwrite = true)
                }
                done++
            }
            withContext(Dispatchers.Main) { onProgress(i + 1, apps.size) }
        }
        runCatching {
            File(dl, "packages.txt").writeText(apps.joinToString("\n") {
                "${it.packageName}\t${runCatching { pm.getApplicationLabel(it) }.getOrDefault("")}\t${it.sourceDir}"
            })
        }
        return done
    }

    /** Settings changed by the speed tweaks, with the value applied. */
    private val SPEED_SETTINGS = listOf(
        "window_animation_scale" to "0.5",
        "transition_animation_scale" to "0.5",
        "animator_duration_scale" to "0.5",
        "wifi_scan_always_enabled" to "0",
        "ble_scan_always_enabled" to "0",
    )

    /** System speed settings: snappier animations, no idle Wi-Fi/BLE scanning, MMI kept awake. */
    fun speedTweakCommands(pkg: String): List<String> =
        SPEED_SETTINGS.map { (key, value) -> "settings put global $key $value" } + listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
        )

    /** Commands that put the speed settings back to what they were before MMI changed them. */
    private fun revertCommands(context: Context): List<String> {
        val saved = prefs(context)
        return SPEED_SETTINGS.map { (key, _) ->
            val before = saved.getString("before_$key", null)
                ?: runCatching { android.provider.Settings.Global.getString(context.contentResolver, key) }.getOrNull()
            if (before == null) "settings delete global $key" else "settings put global $key $before"
        }
    }

    /** Remembers the current values once, so "off" can restore them exactly. */
    private fun rememberBefore(context: Context) {
        val saved = prefs(context)
        if (saved.getBoolean("speed_saved", false)) return
        val edit = saved.edit().putBoolean("speed_saved", true)
        SPEED_SETTINGS.forEach { (key, _) ->
            runCatching { android.provider.Settings.Global.getString(context.contentResolver, key) }.getOrNull()
                ?.let { edit.putString("before_$key", it) }
        }
        edit.apply()
    }

    /** Applies the speed settings from MMI itself (root, or WRITE_SECURE_SETTINGS granted over ADB). */
    suspend fun applySpeedTweaks(context: Context): Boolean = withContext(Dispatchers.IO) {
        rememberBefore(context)
        if (Root.available()) return@withContext Root.run(speedTweakCommands(context.packageName).joinToString("; ")) != null
        runCatching {
            SPEED_SETTINGS.forEach { (key, value) -> android.provider.Settings.Global.putString(context.contentResolver, key, value) }
            true
        }.getOrDefault(false)
    }

    /** Puts the speed settings back to their previous values. */
    suspend fun revertSpeedTweaks(context: Context): Boolean = withContext(Dispatchers.IO) {
        val saved = prefs(context)
        val ok = if (Root.available()) {
            Root.run(revertCommands(context).joinToString("; ")) != null
        } else runCatching {
            SPEED_SETTINGS.forEach { (key, _) ->
                android.provider.Settings.Global.putString(context.contentResolver, key, saved.getString("before_$key", null) ?: if (key.endsWith("scale")) "1.0" else "1")
            }
            true
        }.getOrDefault(false)
        if (ok) saved.edit().remove("speed_saved").apply { SPEED_SETTINGS.forEach { remove("before_${it.first}") } }.apply()
        ok
    }

    fun speedTweaksApplied(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(context.contentResolver, "animator_duration_scale", 1f) <= 0.5f
    }.getOrDefault(false)
}

/** The one place that knows how to find and use su. */
object Root {
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su", "/vendor/bin/su",
        "/debug_ramdisk/su", // Magisk on newer Android
    )

    /** Path of the su binary, if any. */
    fun path(): String? = SU_PATHS.firstOrNull { File(it).exists() }

    fun available(): Boolean = path() != null

    /**
     * Runs a shell command as root and returns its output (stderr merged in, so a
     * chatty command can't fill an unread pipe and hang). With [requireSuccess],
     * a non-zero exit code returns null.
     */
    fun run(command: String, requireSuccess: Boolean = true): String? = runCatching {
        // The marker only prints if the root shell really ran, so a refused prompt is never "success".
        val p = ProcessBuilder("su", "-c", "$command; echo $MARKER").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        when {
            MARKER !in out -> null
            requireSuccess && code != 0 -> null
            else -> out.replace(MARKER, "").trimEnd()
        }
    }.getOrNull()

    private const val MARKER = "__MMI_ROOT_OK__"
}
