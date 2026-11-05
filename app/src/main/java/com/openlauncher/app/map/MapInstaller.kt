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
    // The map is drawn from the road network, so the large tile file isn't needed.
    val FILES = listOf("egypt-safety.db", "egypt-routing.graph", "egypt-search.db")
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
                // Published fingerprints ("hash  name" per line). Required: a map file is only
                // used once its content matches, never on its size alone.
                val hashes = open("maps.sha256").let { c ->
                    try {
                        c.inputStream.bufferedReader().use { r ->
                            r.readLines().mapNotNull { l -> l.trim().split(Regex("\\s+")).takeIf { it.size == 2 }?.let { it[1].removePrefix("*") to it[0].lowercase() } }.toMap()
                        }
                    } finally {
                        c.disconnect()
                    }
                }
                for (name in FILES) if (hashes[name] == null) throw java.io.IOException("No fingerprint published for $name")
                val total = sizes.values.sum()
                var before = 0L
                for (name in FILES) {
                    val size = sizes.getValue(name)
                    val expected = hashes.getValue(name)
                    val dest = target(app, name)
                    // Already here: keep it only if its content is the published one.
                    if (dest.length() == size && verified(dest, expected)) {
                        before += size
                        continue
                    }
                    val part = File(dest.parentFile, "$name.part")
                    fetch(name, part, size) { got ->
                        _state.value = _state.value.copy(file = name, doneBytes = before + got, totalBytes = total)
                    }
                    if (!com.openlauncher.app.util.AppUpdater.sha256(part).equals(expected, ignoreCase = true)) {
                        part.delete()
                        throw java.io.IOException("Fingerprint mismatch for $name")
                    }
                    // Same folder, so the move is a rename; a failed move is a failed install.
                    if (!part.renameTo(dest)) {
                        dest.delete()
                        if (!part.renameTo(dest)) throw java.io.IOException("Could not install $name")
                    }
                    markVerified(dest, expected)
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

    /** A file's content was checked against [hash] (remembered, so it isn't hashed on every press). */
    private fun verified(f: File, hash: String): Boolean {
        val mark = File(f.parentFile, f.name + ".ok")
        if (mark.isFile && mark.readText().trim() == "$hash ${f.length()} ${f.lastModified()}") return true
        val ok = com.openlauncher.app.util.AppUpdater.sha256(f).equals(hash, ignoreCase = true)
        if (ok) markVerified(f, hash) else { f.delete(); mark.delete() }
        return ok
    }

    private fun markVerified(f: File, hash: String) {
        runCatching { File(f.parentFile, f.name + ".ok").writeText("$hash ${f.length()} ${f.lastModified()}") }
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
                // ...and a partial answer must start exactly where the file stopped.
                val append = from > 0 && c.responseCode == HttpURLConnection.HTTP_PARTIAL &&
                    c.getHeaderField("Content-Range")?.startsWith("bytes $from-") == true
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
                    // A short copy (stick pulled, read error) is never installed.
                    if (part.length() != src.length()) {
                        part.delete()
                        throw java.io.IOException("Incomplete copy of $name")
                    }
                    // With the package's maps.sha256 next to the files, the content is checked too.
                    val sums = File(src.parentFile, "maps.sha256")
                    val expected = runCatching {
                        sums.readLines().firstNotNullOfOrNull { l ->
                            l.trim().split(Regex("\\s+")).takeIf { it.size == 2 && it[1].removePrefix("*") == name }?.get(0)
                        }
                    }.getOrNull()
                    if (expected != null && !com.openlauncher.app.util.AppUpdater.sha256(part).equals(expected, ignoreCase = true)) {
                        part.delete()
                        throw java.io.IOException("Fingerprint mismatch for $name")
                    }
                    if (!part.renameTo(dest)) {
                        dest.delete()
                        if (!part.renameTo(dest)) throw java.io.IOException("Could not install $name")
                    }
                    if (expected != null) markVerified(dest, expected)
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
