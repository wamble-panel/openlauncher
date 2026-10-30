package com.openlauncher.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates MMI from the GitHub "install-mmi" release: every build there carries
 * MMI.apk plus version.txt with its version code. With root the new build is
 * installed silently; otherwise Android's installer opens with it.
 */
object AppUpdater {
    private const val BASE = "https://github.com/wamble-panel/openlauncher/releases/download/install-mmi/"

    fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

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

    /** Downloads the newest MMI.apk; returns the file or null on failure. */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        runCatching {
            val target = apkFile(context).apply { parentFile?.mkdirs(); delete() }
            val part = File(target.path + ".part")
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
            part.renameTo(target)
            target
        }.getOrNull()
    }

    /**
     * Installs the downloaded build. Root installs silently (MMI restarts as the
     * home screen); otherwise Android's installer asks once to confirm.
     * Returns false when the installer can't be started yet (e.g. "unknown apps"
     * must first be allowed for MMI — that screen is opened instead).
     */
    suspend fun install(context: Context, apk: File): Boolean {
        if (Root.available()) {
            val ok = withContext(Dispatchers.IO) {
                val tmp = "/data/local/tmp/mmi-update.apk"
                Root.run("cp '${apk.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp; rm -f $tmp", requireSuccess = true) != null
            }
            if (ok) return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
