package com.openlauncher.app.map

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Progress of a map install, shown on the Navigation import screen. */
data class InstallState(
    val running: Boolean = false,
    val source: String = "",
    val file: String = "",
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Installs the Egypt map package without the file picker: either downloads it
 * once over Wi-Fi/hotspot (resuming if the connection drops), or copies it from
 * a USB stick, SD card or the unit's Download folder when the files are found
 * there. Runs process-wide so leaving the page doesn't stop it.
 */
object MapInstaller {
    /** The four package files, smallest first so the quick ones land early. */
    val FILES = listOf("egypt-safety.db", "egypt-routing.graph", "egypt-search.db", "egypt.mbtiles")
    private const val RELEASE_URL = "https://github.com/wamble-panel/openlauncher/releases/download/maps-egypt/"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(InstallState())
    val state: StateFlow<InstallState> = _state

    private fun target(context: Context, name: String) = File(MapData.dir(context), name)

    fun cancel() {
        job?.cancel()
        _state.value = InstallState()
    }

    // ── Download ─────────────────────────────────────────────────────────────

    fun download(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = InstallState(running = true, source = "Downloading")
            runCatching {
                val sizes = FILES.associateWith { remoteSize(it) }
                val total = sizes.values.sum()
                var before = 0L
                for (name in FILES) {
                    val size = sizes.getValue(name)
                    val dest = target(app, name)
                    if (dest.length() == size) {
                        before += size
                        continue
                    }
                    fetch(name, File(dest.parentFile, "$name.part"), size) { got ->
                        _state.value = _state.value.copy(file = name, doneBytes = before + got, totalBytes = total)
                    }
                    File(dest.parentFile, "$name.part").renameTo(dest)
                    before += size
                }
            }.onSuccess {
                NavigationEngine.reloadData()
                _state.value = InstallState(finished = true, source = "Downloaded")
            }.onFailure { e ->
                if (isActive) _state.value = _state.value.copy(running = false, error = downloadError(e))
            }
        }
    }

    private fun downloadError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet. Connect the screen to your phone's hotspot and try again."
        is java.io.FileNotFoundException -> "The map package isn't published yet."
        else -> "Download stopped (${e.javaClass.simpleName}). Tap Download again — it continues where it stopped."
    }

    private fun open(name: String, from: Long = 0): HttpURLConnection {
        var url = URL(RELEASE_URL + name)
        // Follow GitHub's redirect to its file storage by hand (HttpURLConnection
        // won't across hosts), keeping the Range header on each hop.
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
            } else {
                if (code == 404) throw java.io.FileNotFoundException(name)
                if (code >= 400) throw java.io.IOException("HTTP $code")
                return c
            }
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun remoteSize(name: String): Long {
        val c = open(name)
        val size = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        c.disconnect()
        if (size <= 0) throw java.io.IOException("Unknown size for $name")
        return size
    }

    private suspend fun fetch(name: String, part: File, size: Long, onProgress: (Long) -> Unit) {
        var attempts = 0
        if (part.length() > size) part.delete() // left over from an older package
        while (part.length() < size) {
            try {
                val from = part.length()
                val c = open(name, from)
                // A server that ignores Range sends the whole file again: start over.
                val append = from > 0 && c.responseCode == HttpURLConnection.HTTP_PARTIAL
                c.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        var got = if (append) from else 0L
                        val buf = ByteArray(1 shl 16)
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (got - lastReport > 512 * 1024) {
                                lastReport = got
                                onProgress(got)
                            }
                        }
                        onProgress(got)
                    }
                }
                c.disconnect()
            } catch (e: java.io.IOException) {
                if (e is java.io.FileNotFoundException || ++attempts > 20) throw e
                delay(3000)
            }
        }
        if (part.length() != size) throw java.io.IOException("Size mismatch for $name")
    }

    // ── USB / SD / Download folder ───────────────────────────────────────────

    /** Whether the app may read USB sticks and the Download folder directly. */
    fun hasFileAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Map files found on removable storage or in the unit's own storage, by file name. */
    fun findLocal(context: Context): Map<String, File> {
        val roots = LinkedHashSet<File>()
        runCatching { roots += Environment.getExternalStorageDirectory() }
        listOf("/storage", "/mnt/media_rw", "/mnt/usb_storage", "/mnt").forEach { dir ->
            runCatching { File(dir).listFiles()?.filter { it.isDirectory && it.canRead() }?.let { roots += it } }
        }
        val own = MapData.dir(context).absolutePath
        val found = HashMap<String, File>()
        fun scan(dir: File, depth: Int) {
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (f in children) {
                if (f.absolutePath.startsWith(own)) continue
                if (f.isFile && f.name in FILES && f.length() > 0) {
                    val prev = found[f.name]
                    if (prev == null || f.lastModified() > prev.lastModified()) found[f.name] = f
                } else if (f.isDirectory && depth > 0 && f.name != "Android" && !f.name.startsWith(".")) {
                    scan(f, depth - 1)
                }
            }
        }
        roots.forEach { scan(it, 3) }
        return found
    }

    fun installLocal(context: Context, files: Map<String, File>) {
        if (job?.isActive == true || files.isEmpty()) return
        val app = context.applicationContext
        job = scope.launch {
            val total = files.values.sumOf { it.length() }
            _state.value = InstallState(running = true, source = "Copying", totalBytes = total)
            runCatching {
                var before = 0L
                for ((name, src) in files.entries.sortedBy { it.value.length() }) {
                    val dest = target(app, name)
                    val part = File(dest.parentFile, "$name.part")
                    src.inputStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            var got = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (got % (4 shl 20) < buf.size) {
                                    _state.value = _state.value.copy(file = name, doneBytes = before + got)
                                }
                            }
                        }
                    }
                    if (!part.renameTo(dest)) {
                        part.copyTo(dest, overwrite = true)
                        part.delete()
                    }
                    before += src.length()
                }
            }.onSuccess {
                NavigationEngine.reloadData()
                _state.value = InstallState(finished = true, source = "Copied")
            }.onFailure { e ->
                _state.value = _state.value.copy(running = false, error = "Copy failed (${e.javaClass.simpleName}). Check the USB stick and try again.")
            }
        }
    }
}
