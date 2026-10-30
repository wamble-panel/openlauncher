package com.openlauncher.app.util

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.openlauncher.app.service.MediaListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Learns how the vendor's car software talks, without copying its apps off the
 * unit: reads the broadcast names, device paths and settings keys out of every
 * vendor app's code, then listens to those broadcasts (and key presses) while
 * the owner uses the steering-wheel keys, Bluetooth music and the radio.
 * Everything ends up in one small text file in Download.
 */
object CarLinkScan {
    private class AppStrings(val pkg: String, val label: String, val components: List<String>, val strings: List<String>)

    private var apps: List<AppStrings> = emptyList()
    private val events = ArrayList<String>()
    private var receiver: BroadcastReceiver? = null
    @Volatile var listening = false
        private set

    private val DOTTED = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_\\-]+){2,}$")
    private val SKIP_PREFIX = listOf("android.support.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "com.google.", "org.", "dalvik.", "sun.", "libcore.")
    private val CAR_HINTS = listOf("bt", "bluetooth", "music", "radio", "fm", "car", "can", "mcu", "launcher", "media", "avrcp", "a2dp", "settings", "systemui", "phone", "dialer", "video", "canbus")
    // The TS10 apps' own messages (e.g. com.tw.launcher.btmsg) have lower-case names.
    private val VENDOR_PREFIX = listOf("com.tw.", "dofun.", "net.easyconn.", "com.android.bt.")
    private val DEX_ENTRY = Regex("classes\\d*\\.dex")
    private val PATH_PREFIX = listOf("/dev/", "/sys/", "/proc/", "/data/", "/mnt/", "/storage/")

    /** Vendor apps: system apps that aren't plain Android or Google. */
    private fun vendorApps(context: Context): List<ApplicationInfo> {
        @Suppress("DEPRECATION")
        return context.packageManager.getInstalledApplications(PackageManager.GET_DISABLED_COMPONENTS).filter {
            it.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                !it.packageName.startsWith("com.google.") && !it.packageName.startsWith("android") &&
                it.packageName != context.packageName &&
                // Vendors often reuse com.android.* names for their own car apps; keep the car-related ones.
                (!it.packageName.startsWith("com.android.") || CAR_HINTS.any { h -> it.packageName.contains(h) })
        }
    }

    /** Printable ASCII runs, like the `strings` tool: works on dex, vdex and odex alike. */
    private fun printableRuns(input: InputStream, into: MutableSet<String>) {
        val buf = ByteArray(64 * 1024)
        val run = StringBuilder()
        fun flush() {
            if (run.length in 8..200) {
                val s = run.toString()
                if ((DOTTED.matches(s) && SKIP_PREFIX.none { s.startsWith(it) }) || PATH_PREFIX.any { s.startsWith(it) }) into += s
            }
            run.setLength(0)
        }
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i].toInt() and 0xFF
                if (c in 0x20..0x7E) { if (run.length < 400) run.append(c.toChar()) } else flush()
            }
        }
        flush()
    }

    private fun stringsOf(ai: ApplicationInfo): List<String> {
        val found = LinkedHashSet<String>()
        val apks = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        apks.forEach { path ->
            runCatching {
                ZipFile(path).use { zip ->
                    zip.entries().asSequence().filter { DEX_ENTRY.matches(it.name) }
                        .forEach { e -> zip.getInputStream(e).use { printableRuns(it, found) } }
                }
            }
        }
        // Preinstalled apps often keep their code next to the APK (oat/<arch>/*.vdex|odex).
        File(ai.sourceDir).parentFile?.let { File(it, "oat") }?.walkTopDown()?.maxDepth(2)
            ?.filter { it.isFile && (it.name.endsWith(".vdex") || it.name.endsWith(".odex")) && it.canRead() }
            ?.forEach { f -> runCatching { f.inputStream().use { printableRuns(it, found) } } }
        return found.take(800)
    }

    /** Step 1: read every vendor app. Returns how many apps were read. */
    suspend fun scan(context: Context, onProgress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val list = vendorApps(context)
        val out = ArrayList<AppStrings>()
        list.forEachIndexed { i, ai ->
            runCatching {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(
                    ai.packageName,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS or PackageManager.GET_DISABLED_COMPONENTS,
                )
                val components = buildList {
                    info.activities?.forEach { add("activity  ${it.name}") }
                    info.services?.forEach { add("service   ${it.name}${if (it.exported) " (exported)" else ""}") }
                    info.receivers?.forEach { add("receiver  ${it.name}${if (it.exported) " (exported)" else ""}") }
                    info.providers?.forEach { add("provider  ${it.name} ${it.authority}") }
                }
                out += AppStrings(ai.packageName, runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(""), components, stringsOf(ai))
            }
            withContext(Dispatchers.Main) { onProgress(i + 1, list.size) }
        }
        apps = out
        out.size
    }

    private fun looksLikeAction(s: String): Boolean {
        val last = s.substringAfterLast('.')
        return s.contains("action", ignoreCase = true) || s.contains("intent") ||
            VENDOR_PREFIX.any { s.startsWith(it) } ||
            (last.length >= 3 && last.all { it.isUpperCase() || it.isDigit() || it == '_' })
    }

    private fun stamp() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun describe(extras: Bundle?): String = runCatching {
        extras?.keySet()?.joinToString(", ") { k -> "$k=${extras.get(k)}".take(160) } ?: ""
    }.getOrDefault("?")

    /** Step 2: listen for the vendor broadcasts found in step 1. */
    fun startListening(context: Context): Int {
        stopListening(context)
        val actions = apps.flatMap { it.strings }.filter { DOTTED.matches(it) && looksLikeAction(it) }.distinct().take(3000)
        synchronized(events) { events.clear() }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                synchronized(events) {
                    if (events.size < 5000) events += "${stamp()}  BROADCAST ${intent.action}  ${describe(intent.extras)}"
                }
            }
        }
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        runCatching { ContextCompat.registerReceiver(context.applicationContext, r, filter, ContextCompat.RECEIVER_EXPORTED) }
        receiver = r
        listening = true
        return actions.size
    }

    /** Key presses reaching MMI while listening (steering-wheel keys that arrive as normal keys). */
    fun onKey(event: KeyEvent) {
        if (!listening || event.action != KeyEvent.ACTION_DOWN) return
        synchronized(events) { events += "${stamp()}  KEY ${KeyEvent.keyCodeToString(event.keyCode)} scan=${event.scanCode} source=${event.source} device=${event.device?.name}" }
    }

    fun eventCount() = synchronized(events) { events.size }

    fun stopListening(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        listening = false
    }

    /** Step 3: write everything to Download/mmi-carlink.txt; returns where it went. */
    suspend fun save(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val sessions = runCatching {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                msm.getActiveSessions(ComponentName(context, MediaListenerService::class.java)).joinToString { it.packageName }
            }.getOrDefault("no access")
            val text = buildString {
                appendLine("MMI car link scan  ${Date()}")
                appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ${android.os.Build.HARDWARE} · ${android.os.Build.DISPLAY}")
                appendLine("Active media sessions: $sessions")
                appendLine()
                appendLine("== Heard while listening ==")
                synchronized(events) { events.forEach { appendLine(it) } }
                appendLine()
                appendLine("== Car messages since MMI started ==")
                TwBridge.recent().forEach { appendLine(it) }
                appendLine()
                apps.sortedBy { it.pkg }.forEach { a ->
                    appendLine("== ${a.pkg}  (${a.label}) ==")
                    a.components.forEach { appendLine("  $it") }
                    a.strings.forEach { appendLine("  $it") }
                    appendLine()
                }
            }
            DownloadOut.writeText(context, null, "mmi-carlink.txt", text)
        }.getOrNull()
    }
}
