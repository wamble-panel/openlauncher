package com.openlauncher.app.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Hands MMI's report files to a phone on the same Wi-Fi/hotspot: a tiny web page
 * on port 8080 lists them for download. Only these named files are served, and
 * the server switches itself off after 15 minutes.
 */
object WifiShare {
    private val NAMES = listOf(
        "mmi-carlink.txt", "mmi-packages.txt", "mmi-cleanup.bat", "mmi-cleanup.sh", "mmi-cleanup-undo.bat", "mmi-cleanup-undo.sh",
    )
    private var server: ServerSocket? = null
    @Volatile var running = false
        private set

    /** The unit's address on the local network, e.g. 192.168.43.120. */
    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /** Opens one of MMI's files wherever DownloadOut put it. */
    private fun open(context: Context, name: String): InputStream? {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
            .takeIf { it.canRead() }?.let { f -> runCatching { return f.inputStream() } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                        return context.contentResolver.openInputStream(uri)
                    }
                }
            }
        }
        File(context.getExternalFilesDir(null) ?: context.filesDir, name).takeIf { it.canRead() }?.let { return it.inputStream() }
        return null
    }

    /** Port actually in use (8080 may already be taken by vendor software). */
    @Volatile var port = 0
        private set
    @Volatile var visits = 0
        private set

    /**
     * Starts sharing and checks it from the unit itself. Returns null when it
     * works, or the reason it doesn't, to show on screen.
     */
    suspend fun start(context: Context): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (running) return@withContext null
        val app = context.applicationContext
        var lastError: Throwable? = null
        val socket = listOf(8080, 8765, 8888, 0).firstNotNullOfOrNull { p ->
            runCatching {
                ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(p)) }
            }.onFailure { lastError = it }.getOrNull()
        } ?: return@withContext "Couldn't open a port: ${lastError?.javaClass?.simpleName} ${lastError?.message.orEmpty()}"
        server = socket
        port = socket.localPort
        visits = 0
        running = true
        val until = System.currentTimeMillis() + 15 * 60_000L
        thread(name = "mmi-share", isDaemon = true) {
            runCatching { socket.soTimeout = 2000 }
            while (running && System.currentTimeMillis() < until) {
                val client = try {
                    socket.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    break
                }
                visits++
                thread(isDaemon = true) { runCatching { serve(app, client) }; runCatching { client.close() } }
            }
            stop()
        }
        // Self-test: fetch the page from the unit itself.
        runCatching {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".toByteArray())
                val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                if (!line.contains("200")) return@withContext "Started, but the self-test got: $line"
            }
        }.onFailure { return@withContext "Started, but the self-test failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}" }
        null
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun serve(context: Context, client: Socket) {
        client.soTimeout = 10_000
        val request = client.getInputStream().bufferedReader().readLine() ?: return
        val path = request.split(' ').getOrNull(1) ?: "/"
        val out = client.getOutputStream()
        val name = path.removePrefix("/f/").takeIf { path.startsWith("/f/") }
        if (name != null && name in NAMES) {
            val input = open(context, name)
            if (input != null) {
                out.write(
                    ("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Disposition: attachment; filename=\"$name\"\r\nConnection: close\r\n\r\n").toByteArray()
                )
                input.use { it.copyTo(out) }
                out.flush()
                return
            }
        }
        val links = NAMES.filter { n -> open(context, n)?.also { it.close() } != null }
            .joinToString("") { "<p><a href=\"/f/$it\">$it</a></p>" }
            .ifEmpty { "<p>No files yet. Run Save PC script or Car link scan on the unit first.</p>" }
        val html = "<!doctype html><meta name=viewport content='width=device-width'><title>MMI files</title>" +
            "<body style='font-family:sans-serif;font-size:20px;padding:16px;background:#0a0b0d;color:#e8eaed'>" +
            "<h2>MMI files</h2>$links<p style='color:#8a9099;font-size:15px'>Tap a file to download it.</p></body>"
        out.write("HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$html".toByteArray())
        out.flush()
    }
}
