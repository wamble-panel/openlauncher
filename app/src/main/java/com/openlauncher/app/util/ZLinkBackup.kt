package com.openlauncher.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Backs up the unit's phone-link engine (ZLink: CarPlay / Android Auto,
 * com.zjinnova.zlink) and uploads it to the owner's PRIVATE GitHub repo, using the
 * very same sign-in as "Send to developer" (see [DevUpload]) — one GitHub login
 * serves both.
 *
 * The backup is a single zip: the installed APK(s) always, plus the app's saved
 * settings and phone pairings when the unit is rooted (that data lives in
 * /data/data and only root can read it), plus any readable external config. A copy
 * is also kept in the Download folder as a fallback if the upload can't finish.
 */
object ZLinkBackup {
    private const val PKG = PhoneLink.PKG // "com.zjinnova.zlink"

    /**
     * Builds the backup, saves a copy to Download, then uploads it to GitHub.
     * Reports each step through [onStatus]; throws on any failure (the caller shows
     * the message). The local Download copy survives a failed upload.
     */
    suspend fun performBackup(context: Context, onStatus: (String) -> Unit) {
        val rooted = Root.available()
        onStatus(if (rooted) "Packing the ZLink app, settings & pairings…" else "Packing the ZLink app (settings need root)…")
        val zip = withContext(Dispatchers.IO) { build(context, rooted) }

        onStatus("Saving a copy to Download…")
        val where = withContext(Dispatchers.IO) { copyToDownload(context, zip) }

        onStatus("Signing in to GitHub…")
        if (!DevUpload.ensureSignedIn(context, onStatus))
            error("GitHub sign-in not finished. Backup saved to $where — tap Start again to upload it.")

        // Upload through the very same sender as "Send to developer".
        val n = DevUpload.send(context, listOf(zip), onStatus).getOrElse {
            error((it.message ?: "GitHub upload failed") + " Backup saved to $where.")
        }
        onStatus("Done. Sent $n file to GitHub and saved to $where.")
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())

    /** Builds the backup zip in the cache dir. Throws when there is nothing to back up. */
    private fun build(context: Context, rooted: Boolean): File {
        val dir = File(context.cacheDir, "zlink-backup").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "zlink-backup-${stamp()}.zip")

        var wrote = 0
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(PKG, 0) }.getOrNull()

        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            // (a) The installed APK(s) — readable without root.
            info?.applicationInfo?.let { ai ->
                val apks = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                apks.forEachIndexed { i, p ->
                    val f = File(p)
                    if (f.isFile && f.canRead()) wrote += add(z, "apk/zlink${if (i == 0) "" else ".split$i"}.apk", f)
                }
            }

            // (b) App data + pairings — lives in /data/data, so only root can read it.
            if (rooted) {
                val tar = File(dir, "zlink-data.tar")
                val ok = Root.run(
                    "tar -cf '${tar.absolutePath}' -C /data/data $PKG 2>/dev/null; chmod 644 '${tar.absolutePath}'",
                    requireSuccess = false,
                ) != null
                if (ok && tar.isFile && tar.length() > 0) wrote += add(z, "data/zlink-data.tar", tar)
                tar.delete()
            }

            // (c) Any readable external config the app keeps on shared storage.
            listOf("/sdcard/Android/data/$PKG", "/sdcard/zlink", "/sdcard/.zlink", "/sdcard/ZLINK")
                .map(::File)
                .filter { it.exists() && it.canRead() }
                .forEach { root ->
                    root.walkTopDown()
                        .filter { it.isFile && it.canRead() && it.length() < 50L * 1024 * 1024 }
                        .forEach { f -> wrote += add(z, "external/${root.name}/${f.toRelativeString(root)}", f) }
                }

            // A small manifest so a later restore knows what this is.
            val manifest = buildString {
                appendLine("ZLink backup")
                appendLine("package: $PKG")
                appendLine("made: ${Date()}")
                appendLine("rooted: $rooted")
                appendLine("installed: " + (info?.let { "${it.versionName} (${PackageInfoCompat(it)})" } ?: "not installed"))
            }
            z.putNextEntry(ZipEntry("manifest.txt"))
            z.write(manifest.toByteArray())
            z.closeEntry()
        }

        if (wrote == 0) {
            zip.delete()
            error("Nothing to back up: ZLink ($PKG) isn't installed and no data was readable" + if (!rooted) " (settings need root)." else ".")
        }
        return zip
    }

    @Suppress("DEPRECATION", "FunctionName")
    private fun PackageInfoCompat(info: android.content.pm.PackageInfo): Long =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun add(z: ZipOutputStream, entry: String, file: File): Int = runCatching {
        z.putNextEntry(ZipEntry(entry))
        file.inputStream().use { it.copyTo(z, 64 * 1024) }
        z.closeEntry()
        1
    }.getOrDefault(0)

    /** Keeps a copy in Download/ZLinkBackup; returns where it landed. */
    private fun copyToDownload(context: Context, zip: File): String {
        val t = DownloadOut.open(context, "ZLinkBackup", zip.name, "application/zip")
        t.stream.use { out -> zip.inputStream().use { it.copyTo(out) } }
        return t.where + zip.name
    }
}
