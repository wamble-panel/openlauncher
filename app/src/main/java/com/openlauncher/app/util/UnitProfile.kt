package com.openlauncher.app.util

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * What this head unit is and what MMI can do on it, worked out by itself on every
 * start (no setup). Chinese Android units come from a few platform families; each is
 * recognised by its system library, build name or the vendor's own packages.
 *
 * For every feature MMI picks one of:
 *  - "mmi":    MMI does it itself (its own page, its own link to the car).
 *  - "app":    the unit's own app does it; MMI opens that app (pkg).
 *  - "system": the unit does it by itself (e.g. the reverse camera); MMI stays out of the way.
 *  - "none":   not on this unit; MMI hides it.
 */
object UnitProfile {
    enum class Platform(val label: String) {
        TW("TW (Topway)"),
        WJ("WJ · MediaTek"),
        FYT("FYT / UIS"),
        KSW("KSW / ZXW"),
        MTC("Microntek"),
        GENERIC("Android"),
    }

    data class Feature(val key: String, val mode: String, val pkg: String? = null)

    @Volatile private var cached: Pair<Long, Map<String, Any?>>? = null
    @Volatile private var platformCache: Platform? = null

    fun platform(c: Context): Platform = platformCache ?: detect(c).also { platformCache = it }

    private fun prop(name: String): String = runCatching {
        @Suppress("PrivateApi")
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    private fun hasClass(name: String) = runCatching { Class.forName(name) }.isSuccess

    private fun hasPkg(c: Context, prefix: String): Boolean =
        runCatching { c.packageManager.getInstalledApplications(0).any { it.packageName.startsWith(prefix) } }.getOrDefault(false)

    private fun detect(c: Context): Platform {
        val build = (Build.DISPLAY + " " + prop("ro.build.display.id") + " " + prop("ro.build.version.incremental")).uppercase()
        return when {
            hasClass("android.tw.john.TWUtil") || prop("ro.systemtw.version").isNotEmpty() -> Platform.TW
            build.contains("WJ_") || hasPkg(c, "com.hcn.") -> Platform.WJ
            hasPkg(c, "com.syu.") || prop("ro.fyt.uiid").isNotEmpty() -> Platform.FYT
            hasPkg(c, "com.szchoiceway.") -> Platform.KSW
            hasClass("android.microntek.CarManager") || hasPkg(c, "com.microntek.") -> Platform.MTC
            else -> Platform.GENERIC
        }
    }

    private data class App(val label: String, val pkg: String)

    private fun launchable(c: Context): List<App> {
        val pm = c.packageManager
        return runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != c.packageName }
                .distinctBy { it.pkg }
        }.getOrDefault(emptyList())
    }

    /** The unit's own app for a job: by its label first, then its package name. Phone-camera and store apps never match. */
    private fun find(apps: List<App>, labels: List<String>, pkgWords: List<String>, not: List<String> = emptyList()): String? {
        val ok = apps.filter { a -> not.none { a.pkg.contains(it, true) } && !a.pkg.startsWith("com.google.") }
        return labels.firstNotNullOfOrNull { l -> ok.firstOrNull { it.label.equals(l, true) } }?.pkg
            ?: labels.firstNotNullOfOrNull { l -> ok.firstOrNull { it.label.contains(l, true) } }?.pkg
            ?: pkgWords.firstNotNullOfOrNull { w -> ok.firstOrNull { it.pkg.contains(w, true) } }?.pkg
    }

    fun features(c: Context): List<Feature> {
        val p = platform(c)
        val tw = p == Platform.TW
        val apps = launchable(c)
        fun app(pkg: String?, orElse: String = "none") = if (pkg != null) Feature("", "app", pkg) else Feature("", orElse)
        val radioApp = find(apps, listOf("Radio", "FM", "收音机"), listOf(".radio", ".fm"))
        val btApp = find(apps, listOf("Bluetooth", "BT", "Phone", "蓝牙"), listOf("bluetooth", ".bt", "btphone"), not = listOf("com.android.settings"))
        val carApp = find(apps, listOf("CarInfo", "Car Info", "Car information", "Vehicle", "Canbus", "原车"), listOf("carinfo", "canbus", "vehicle"))
        val camApp = find(apps, listOf("Reverse", "AVM", "360", "Camera"), listOf("hcamera", "avm", "reverse", "backcar", "avin"), not = listOf("com.android.camera", "snapcam"))
        val swcApp = find(apps, listOf("SWC", "Steering", "Wheel key", "方控"), listOf("swc", "steer", "wheelkey"))
        val acApp = find(apps, listOf("Climate", "Air condition", "AC", "空调"), listOf("aircon", "climate", ".ac."))
        val linkApp = find(apps, listOf("ZLink", "CarLink", "AutoKit", "CarPlay"), listOf("zlink", "carlink", "autokit", "easyconnect", "phonelink"))
        val list = listOf(
            "radio" to if (tw) Feature("", "mmi") else app(radioApp),
            "phone" to if (tw) Feature("", "mmi") else app(btApp),
            "carinfo" to if (tw) Feature("", "mmi") else app(carApp),
            "camera" to if (tw) Feature("", "mmi") else Feature("", "system", camApp),
            "keys" to if (tw) Feature("", "mmi") else app(swcApp, orElse = "system"),
            "climate" to when {
                tw && CarProfile.gmFrames -> Feature("", "mmi")
                acApp != null -> Feature("", "app", acApp)
                else -> Feature("", "none")
            },
            // On TW units MMI's Phone Link page runs the engine hidden; elsewhere the unit's app.
            "phonelink" to if (tw && linkApp != null) Feature("", "mmi", linkApp) else app(linkApp),
            "sound" to Feature("", "mmi"),
            "music" to Feature("", "mmi"),
            "nav" to Feature("", "mmi"),
            "apps" to Feature("", "mmi"),
            "weather" to Feature("", "mmi"),
        )
        return list.map { (k, f) -> f.copy(key = k) }
    }

    /** For the screens: the platform, the unit's names and each feature's mode (kept 20 s). */
    fun describe(c: Context): Map<String, Any?> {
        cached?.let { (at, m) -> if (System.currentTimeMillis() - at < 20_000) return m }
        val p = platform(c)
        val m = mapOf(
            "platform" to p.name,
            "platformLabel" to p.label,
            "chip" to listOf(Build.HARDWARE, prop("ro.board.platform")).firstOrNull { it.isNotBlank() },
            "build" to Build.DISPLAY,
            "android" to Build.VERSION.RELEASE,
            "features" to features(c).associate { it.key to mapOf("mode" to it.mode, "pkg" to it.pkg) },
        )
        cached = System.currentTimeMillis() to m
        return m
    }
}
