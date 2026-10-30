package com.openlauncher.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Packs just the vendor's car software (Bluetooth, radio, music, steering-wheel,
 * car service) plus the system library they share into zips small enough for a
 * browser upload. They land in MMI's reports folder, which the MMI file picker
 * offers to the browser.
 */
object CarAppsExport {
    // Only what hasn't been received yet. Already in: Bluetooth, music, radio,
    // steering wheel, service, reverse, climate, CarSetting, EQ, car info, car
    // choice, AUX and framework.jar. Next: the vendor home screen ("Theme") and
    // CarMate, to see exactly what the original home sends to the car.
    private val PACKAGES = listOf("com.dofun.variety", "com.dofun.carassistant.car", "com.tw.carinfoservice")
    // GitHub's web upload takes files up to 25 MB: parts stay well below that.
    private const val PART_LIMIT = 20L * 1024 * 1024
    private const val CHUNK = 15L * 1024 * 1024
    private val MARKER = "android/tw/john".toByteArray()

    fun dir(context: Context) = File(context.filesDir, "reports").apply { mkdirs() }

    /** Whether a byte stream contains the TW library's class path. */
    private fun contains(input: InputStream): Boolean {
        val buf = ByteArray(1 shl 16)
        var carry = ByteArray(0)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return false
            val chunk = carry + buf.copyOf(n)
            outer@ for (i in 0..chunk.size - MARKER.size) {
                for (j in MARKER.indices) if (chunk[i + j] != MARKER[j]) continue@outer
                return true
            }
            carry = chunk.copyOfRange(maxOf(0, chunk.size - MARKER.size), chunk.size)
        }
    }

    /** System framework files that hold the TW car library. */
    private fun frameworkFiles(): List<File> {
        val roots = listOf(File("/system/framework"), File("/system_ext/framework"), File("/vendor/framework"))
        return roots.flatMap { r -> r.walkTopDown().maxDepth(2).filter { it.isFile && it.canRead() }.toList() }
            .filter { f ->
                runCatching {
                    when {
                        f.name.endsWith(".jar") || f.name.endsWith(".apk") -> ZipFile(f).use { z ->
                            z.entries().asSequence().any { e -> e.name.endsWith(".dex") && z.getInputStream(e).use { contains(it) } }
                        }
                        f.name.endsWith(".vdex") || f.name.endsWith(".odex") || f.name.endsWith(".oat") -> f.inputStream().use { contains(it) }
                        else -> false
                    }
                }.getOrDefault(false)
            }
    }

    /** Native libraries the TW apps and services load. */
    private fun nativeLibs(): List<File> =
        listOf("/system/lib", "/system/lib64", "/vendor/lib", "/vendor/lib64", "/data/tw").map(::File)
            .flatMap { d -> d.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.canRead() && it.name.endsWith(".so") && it.name.contains("tw", ignoreCase = true) && it.length() < 8L * 1024 * 1024 }

    /** Builds the zips; returns their names, or null if nothing could be read. */
    suspend fun export(context: Context, onProgress: (String) -> Unit): List<String>? = withContext(Dispatchers.IO) {
        val out = dir(context)
        out.listFiles { f -> f.name.startsWith("mmi-car-apps") }?.forEach { it.delete() }
        val pm = context.packageManager
        val files = ArrayList<Pair<String, File>>()
        PACKAGES.forEach { pkg ->
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                val apks = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                apks.forEachIndexed { i, p -> files += "apps/$pkg${if (i == 0) "" else ".split$i"}.apk" to File(p) }
                // Preinstalled apps often keep their code beside the APK in oat/<arch>/.
                File(ai.sourceDir).parentFile?.let { File(it, "oat") }?.walkTopDown()?.filter { it.isFile && it.canRead() }
                    ?.forEach { files += "apps/$pkg/oat/${it.parentFile?.name}/${it.name}" to it }
            }
        }
        // The car library's native side, if readable (framework.jar is already in).
        nativeLibs().filter { it.name.contains("twutil", ignoreCase = true) || it.name.startsWith("libtw") }
            .forEach { files += "lib/${it.parentFile?.name}/${it.name}" to it }
        if (files.isEmpty()) return@withContext null

        val names = ArrayList<String>()
        var part = 0
        var zip: ZipOutputStream? = null
        var current: File? = null
        var entries = 0
        fun next() {
            zip?.close()
            part++
            current = File(out, "mmi-car-apps-$part.zip").also { names += it.name }
            zip = ZipOutputStream(current!!.outputStream().buffered())
            entries = 0
        }
        fun add(name: String, write: (java.io.OutputStream) -> Unit) {
            runCatching {
                zip!!.putNextEntry(ZipEntry(name))
                write(zip!!)
                zip!!.closeEntry()
                zip!!.flush()
                entries++
            }
        }
        next()
        files.forEachIndexed { i, (path, file) ->
            withContext(Dispatchers.Main) { onProgress("Packing ${i + 1} / ${files.size}…") }
            val size = file.length()
            if (size > PART_LIMIT) {
                // Too big for one upload: split into numbered pieces, each in its own zip.
                file.inputStream().use { input ->
                    var piece = 0
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (entries > 0) next()
                        var written = 0L
                        var ended = false
                        add("$path.%03d".format(piece)) { o ->
                            while (written < CHUNK) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), CHUNK - written).toInt())
                                if (n < 0) { ended = true; break }
                                o.write(buf, 0, n)
                                written += n
                            }
                        }
                        piece++
                        if (ended || written < CHUNK) break
                    }
                }
                next()
            } else {
                // Start a new part when this file would push the current one over the limit.
                if (entries > 0 && current!!.length() + size > PART_LIMIT) next()
                add(path) { o -> file.inputStream().use { it.copyTo(o) } }
            }
        }
        zip?.close()
        names
    }
}
