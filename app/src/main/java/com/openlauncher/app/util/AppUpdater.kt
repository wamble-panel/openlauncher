package com.openlauncher.app.util

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.openlauncher.app.UpdateReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates MMI from the GitHub "install-mmi" release: every build there carries
 * MMI.apk plus version.txt with its version code. New builds are fetched quietly
 * in the background whenever the unit is online, then installed through
 * Android's installer: silently with root or as device owner (only while parked),
 * otherwise with a single "Update MMI?" confirmation.
 */
object AppUpdater {
    private const val BASE = "https://github.com/wamble-panel/openlauncher/releases/download/install-mmi/"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Version code of a downloaded build waiting to be installed, if any. */
    private val _ready = MutableStateFlow<Long?>(null)
    val ready: StateFlow<Long?> = _ready
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure

    fun reportFailure(message: String) { _failure.value = message }

    fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    fun isDeviceOwner(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    /** Version code of the newest build on GitHub, or null when offline. */
    suspend fun latestVersion(): Long? = withContext(Dispatchers.IO) {
        runCatching { open(BASE + "version.txt").inputStream.bufferedReader().use { it.readText().trim().toLong() } }.getOrNull()
    }

    private fun open(url: String): HttpURLConnection {
        var conn = URL(url).openConnection() as HttpURLConnection
        // GitHub answers with a redirect to its file host.
        repeat(5) {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code !in 300..399) {
                if (code != 200) throw java.io.IOException("HTTP $code")
                return conn
            }
            val next = conn.getHeaderField("Location") ?: throw java.io.IOException("redirect without location")
            conn.disconnect()
            conn = URL(URL(url), next).openConnection() as HttpURLConnection
        }
        throw java.io.IOException("too many redirects")
    }

    private fun apkFile(context: Context) = File(context.cacheDir, "updates/MMI.apk")
    private fun prefs(context: Context) = context.getSharedPreferences("mmi_update", Context.MODE_PRIVATE)

    /**
     * Background updates: shortly after start and then every few hours, while
     * online, fetch a newer build so it's ready to install with one tap. As
     * device owner it installs by itself once the car is parked.
     */
    fun startBackground(context: Context, parked: () -> Boolean) {
        val app = context.applicationContext
        // A build left over from before is still good if it's newer than what runs.
        val kept = prefs(app).getLong("ready", 0L)
        if (kept > installedVersion(app) && apkFile(app).isFile) _ready.value = kept
        scope.launch {
            delay(90_000)
            while (true) {
                if (online(app)) runCatching { prefetch(app) }
                if (_ready.value != null && isDeviceOwner(app) && parked()) runCatching { install(app, apkFile(app)) }
                delay(if (_ready.value != null) 10 * 60_000L else 3 * 3600_000L)
            }
        }
    }

    private fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** Downloads the newest build if it's newer than the one running and not fetched yet. */
    suspend fun prefetch(context: Context, onProgress: (Int) -> Unit = {}): Long? = lock.withLock {
        val latest = latestVersion() ?: return@withLock null
        if (latest <= installedVersion(context)) return@withLock null
        if (_ready.value == latest && apkFile(context).isFile) return@withLock latest
        download(context, onProgress) ?: return@withLock null
        prefs(context).edit().putLong("ready", latest).apply()
        _ready.value = latest
        latest
    }

    private suspend fun download(context: Context, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        runCatching {
            val target = apkFile(context).apply { parentFile?.mkdirs() }
            val part = File(target.path + ".part").apply { delete() }
            val conn = open(BASE + "MMI.apk")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && part.length() != total) throw java.io.IOException("incomplete download")
            if (!part.renameTo(target)) throw java.io.IOException("couldn't keep the download")
            target
        }.getOrNull()
    }

    /** Installs the downloaded build. Returns false when Android first needs MMI allowed to install apps. */
    suspend fun installReady(context: Context): Boolean {
        val apk = apkFile(context)
        if (!apk.isFile) return false
        return install(context, apk)
    }

    private suspend fun install(context: Context, apk: File): Boolean {
        _failure.value = null
        if (Root.available()) {
            val ok = withContext(Dispatchers.IO) {
                val tmp = "/data/local/tmp/mmi-update.apk"
                Root.run("cp '${apk.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp; rm -f $tmp", requireSuccess = true) != null
            }
            if (ok) return true
        }
        val owner = isDeviceOwner(context)
        if (!owner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        // Android's installer session: no file viewer, and silent for a device owner.
        return withContext(Dispatchers.IO) {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(context.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("MMI.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val result = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
                    session.commit(result.intentSender)
                }
                true
            }.onFailure { _failure.value = it.message ?: it.javaClass.simpleName }.getOrDefault(false)
        }
    }
}
