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
    private val PACKAGES = listOf(
        "com.tw.bt", "com.tw.music", "com.tw.radio", "com.tw.keypad", "com.tw.service", "com.tw.service.xt",
        "com.tw.core", "com.tw.coreservice", "com.tw.reverse", "com.tw.eq", "com.tw.carchoose", "com.tw.auxin",
        "com.dofun.carsetting",
    )
    private const val PART_LIMIT = 18L * 1024 * 1024
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
        withContext(Dispatchers.Main) { onProgress("Looking for the car library…") }
        frameworkFiles().forEach { files += "framework/${it.parentFile?.name}/${it.name}" to it }
        nativeLibs().forEach { files += "lib/${it.parentFile?.name}/${it.name}" to it }
        if (files.isEmpty()) return@withContext null

        val names = ArrayList<String>()
        var part = 0
        var zip: ZipOutputStream? = null
        var current: File? = null
        fun next() {
            zip?.close()
            part++
            current = File(out, "mmi-car-apps-$part.zip").also { names += it.name }
            zip = ZipOutputStream(current!!.outputStream().buffered())
        }
        next()
        files.forEachIndexed { i, (path, file) ->
            if (current!!.length() > PART_LIMIT) next()
            withContext(Dispatchers.Main) { onProgress("Packing ${i + 1} / ${files.size}…") }
            runCatching {
                zip!!.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip!!) }
                zip!!.closeEntry()
                zip!!.flush()
            }
        }
        zip?.close()
        names
    }
}
