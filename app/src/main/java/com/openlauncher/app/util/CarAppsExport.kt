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
    // Only what hasn't been received yet. Already in: Bluetooth, music, radio, steering
    // wheel, service, reverse, climate, CarSetting, EQ, car info, car choice, AUX, the
    // vendor home ("Theme"), CarMate and framework.jar. Now: the phone-link app
    // (CarPlay / Android Auto), camera / DVR / TV / colour apps and the boot animation.
    private val PACKAGES = listOf(
        "com.zjinnova.zlink", "com.tw.rightview", "com.tw.dvr", "com.tw.tv", "com.tw.color",
        "com.tw.bootanimation", "com.tw.radio.theme",
        // Car-data side: TPMS, CAN-box UART and the car apps bundle.
        "com.dofun.tpms", "com.tw.canuart", "com.tw.carapps",
    )
    /** Loose files: the per-car data names CarInfoService uses for this car. */
    private val EXTRA_FILES = listOf(
        "/system/etc/CarServiceTheme.apk",
        "/sdcard/.ResourcePicture/Apk/CarServiceTheme.apk",
    )
    // GitHub's web upload takes files up to 25 MB: parts stay well below that.
    private const val PART_LIMIT = 20L * 1024 * 1024
    private const val CHUNK = 15L * 1024 * 1024

    fun dir(context: Context) = File(context.filesDir, "reports").apply { mkdirs() }

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
        EXTRA_FILES.map(::File).filter { it.isFile && it.canRead() }.forEach { f ->
            files += "files" + f.path to f
        }
        // The car library's native side, if readable (framework.jar is already in).
        nativeLibs().filter { it.name.contains("twutil", ignoreCase = true) || it.name.startsWith("libtw") }
            .forEach { files += "lib/${it.parentFile?.name}/${it.name}" to it }
        // What the firmware build needs to know: partitions, boot-time services, build.
        runCatching { File(out, "mmi-system.txt").writeText(systemReport()) }
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

    /**
     * Facts about the unit's system for building MMI's firmware: build properties,
     * partitions and mounts, CPU / memory, which services start at boot (init .rc
     * files) and which apps are preinstalled where. No personal data.
     */
    private fun systemReport(): String = buildString {
        fun section(title: String, body: () -> String) {
            append("\n==== ").append(title).append(" ====\n")
            append(runCatching(body).getOrElse { "(not readable: ${it.message})" }).append('\n')
        }
        for (f in listOf("/system/build.prop", "/vendor/build.prop", "/product/build.prop", "/odm/etc/build.prop")) {
            section(f) { File(f).readLines().filter { !it.contains("serial", true) && !it.contains("fingerprint", true) }.joinToString("\n") }
        }
        for (f in listOf("/proc/partitions", "/proc/mounts", "/proc/cpuinfo", "/proc/meminfo", "/proc/version", "/proc/cmdline")) {
            section(f) { File(f).readText().lines().filter { !it.contains("serial", true) }.joinToString("\n") }
        }
        section("/dev/block/by-name") {
            listOf("/dev/block/by-name", "/dev/block/platform").map(::File).flatMap { d -> d.walkTopDown().maxDepth(3).toList() }
                .joinToString("\n") { it.path + (runCatching { " -> " + it.canonicalPath }.getOrDefault("")) }
        }
        for (dir in listOf("/system/etc/init", "/vendor/etc/init", "/system/etc/init/hw", "/odm/etc/init", "/product/etc/init")) {
            File(dir).listFiles()?.filter { it.name.endsWith(".rc") }?.sortedBy { it.name }?.forEach { rc ->
                section(rc.path) { rc.readText() }
            }
        }
        section("init.*.rc in /") { File("/").listFiles()?.filter { it.name.endsWith(".rc") }?.joinToString("\n") { it.name + "\n" + runCatching { it.readText() }.getOrDefault("(not readable)") } ?: "" }
        for (dir in listOf("/system/app", "/system/priv-app", "/vendor/app", "/vendor/priv-app", "/product/app", "/product/priv-app", "/system/framework")) {
            section(dir) { File(dir).listFiles()?.sortedBy { it.name }?.joinToString("\n") { it.name } ?: "(empty)" }
        }
        section("native libraries named tw / mcu / can / bt") {
            listOf("/system/lib", "/system/lib64", "/vendor/lib", "/vendor/lib64").map(::File)
                .flatMap { d -> d.listFiles()?.toList().orEmpty() }
                .filter { f -> listOf("tw", "mcu", "can", "bt", "goc", "uart", "audio").any { f.name.contains(it, true) } }
                .joinToString("\n") { "${it.path} ${it.length()}" }
        }
    }
}
