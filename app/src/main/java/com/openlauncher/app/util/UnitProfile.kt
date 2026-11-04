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

    @Volatile private var cached: Map<String, Any?>? = null

    /** Goes up when a re-check finds the unit changed (an app installed or removed); the screens reload then. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * At start, on a background thread: the saved answer (or a first check) at once, then
     * a full re-check once start-up is over. Never on the screen thread.
     */
    fun start(c: Context) {
        val app = c.applicationContext
        Thread({
            runCatching { describe(app) }
            Thread.sleep(45_000)
            runCatching { if (refresh(app)) version.value++ }
        }, "mmi-unit").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }
    @Volatile private var platformCache: Platform? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("mmi_unit", Context.MODE_PRIVATE)

    /**
     * The unit type. Remembered from the last check (same system build), so it costs
     * nothing on the screen thread; only the very first start on a unit works it out.
     */
    fun platform(c: Context): Platform = platformCache ?: run {
        val p = prefs(c)
        val saved = if (p.getString("fingerprint", null) == Build.FINGERPRINT) p.getString("platform", null) else null
        (saved?.let { runCatching { Platform.valueOf(it) }.getOrNull() } ?: detect(c)).also { platformCache = it }
    }

    private fun prop(name: String): String = runCatching {
        @Suppress("PrivateApi")
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    private fun hasClass(name: String) = runCatching { Class.forName(name) }.isSuccess

    private fun detect(c: Context): Platform {
        val build = (Build.DISPLAY + " " + prop("ro.build.display.id") + " " + prop("ro.build.version.incremental")).uppercase()
        // Cheap checks first; the package list (one query) only when they don't decide.
        if (hasClass("android.tw.john.TWUtil") || prop("ro.systemtw.version").isNotEmpty()) return Platform.TW
        if (build.contains("WJ_")) return Platform.WJ
        if (prop("ro.fyt.uiid").isNotEmpty()) return Platform.FYT
        if (hasClass("android.microntek.CarManager")) return Platform.MTC
        val pkgs = runCatching { c.packageManager.getInstalledApplications(0).map { it.packageName } }.getOrDefault(emptyList())
        fun has(prefix: String) = pkgs.any { it.startsWith(prefix) }
        return when {
            has("com.hcn.") -> Platform.WJ
            has("com.syu.") -> Platform.FYT
            has("com.szchoiceway.") -> Platform.KSW
            has("com.microntek.") -> Platform.MTC
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

    /**
     * For the screens: the platform, the unit's names and each feature's mode. Answers
     * at once from memory or from the last check saved on the unit; the full check
     * (reading the app list) runs only in [refresh], off the screen thread.
     */
    fun describe(c: Context): Map<String, Any?> {
        cached?.let { return it }
        val p = prefs(c)
        if (p.getString("fingerprint", null) == Build.FINGERPRINT) {
            p.getString("profile", null)?.let { json -> runCatching { fromJson(org.json.JSONObject(json)) }.getOrNull() }?.let {
                cached = it
                return it
            }
        }
        return compute(c).also { save(c, it) }
    }

    /**
     * Checks the unit again (apps can be installed or removed) and saves the result.
     * Run on a background thread a little after start. Returns true when anything changed.
     */
    fun refresh(c: Context): Boolean {
        val before = cached
        platformCache = null
        val p = prefs(c)
        p.edit().remove("platform").apply()
        val now = compute(c)
        save(c, now)
        return before != null && before != now
    }

    private fun compute(c: Context): Map<String, Any?> {
        val p = platform(c)
        return mapOf(
            "platform" to p.name,
            "platformLabel" to p.label,
            "chip" to listOf(Build.HARDWARE, prop("ro.board.platform")).firstOrNull { it.isNotBlank() },
            "build" to Build.DISPLAY,
            "android" to Build.VERSION.RELEASE,
            "features" to features(c).associate { it.key to mapOf("mode" to it.mode, "pkg" to it.pkg) },
        )
    }

    private fun save(c: Context, m: Map<String, Any?>) {
        cached = m
        val json = org.json.JSONObject()
        m.forEach { (k, v) ->
            if (v is Map<*, *>) {
                val f = org.json.JSONObject()
                v.forEach { (fk, fv) -> f.put(fk.toString(), org.json.JSONObject((fv as Map<*, *>).filterValues { it != null }.mapKeys { it.key.toString() })) }
                json.put(k, f)
            } else if (v != null) json.put(k, v)
        }
        prefs(c).edit()
            .putString("fingerprint", Build.FINGERPRINT)
            .putString("platform", m["platform"] as String)
            .putString("profile", json.toString())
            .apply()
    }

    private fun fromJson(j: org.json.JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        for (k in listOf("platform", "platformLabel", "chip", "build", "android")) out[k] = j.optString(k).takeIf { it.isNotEmpty() }
        val f = j.getJSONObject("features")
        out["features"] = f.keys().asSequence().associateWith { k ->
            val o = f.getJSONObject(k)
            mapOf("mode" to o.optString("mode", "none"), "pkg" to o.optString("pkg").takeIf { it.isNotEmpty() })
        }
        return out
    }
}
