package com.openlauncher.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Send to developer": uploads MMI's reports and car-app exports straight to the
 * owner's PRIVATE GitHub repo (call logs and vendor software never go public),
 * one dated folder per send, so nothing has to be picked by hand in a browser. Signing in uses GitHub's device flow: MMI shows
 * a short code once, the owner approves it on their phone, and the token is kept
 * on the unit for later uploads.
 */
object DevUpload {
    /** Client ID of the owner's "MMI" OAuth app (device flow enabled). Empty until set up. */
    const val CLIENT_ID = "Ov23limBTF4PpuP8nyzN"
    private const val REPO = "wamble-panel/mmi-unit-data"

    val configured: Boolean get() = CLIENT_ID.isNotEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences("mmi_dev_upload", Context.MODE_PRIVATE)
    fun signedIn(context: Context) = prefs(context).getString("token", null) != null
    fun signOut(context: Context) = prefs(context).edit().remove("token").apply()

    class Code(val userCode: String, val url: String, val deviceCode: String, val interval: Int)

    private fun form(url: String, fields: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { o ->
            o.write(fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }.toByteArray())
        }
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    /** Step 1: the code to type at github.com/login/device. */
    suspend fun requestCode(): Code? = withContext(Dispatchers.IO) {
        runCatching {
            val r = form("https://github.com/login/device/code", mapOf("client_id" to CLIENT_ID, "scope" to "repo"))
            Code(r.getString("user_code"), r.getString("verification_uri"), r.getString("device_code"), r.optInt("interval", 5))
        }.getOrNull()
    }

    /** Step 2: waits (up to ~10 minutes) until the owner approves the code. */
    suspend fun awaitApproval(context: Context, code: Code): Boolean = withContext(Dispatchers.IO) {
        var wait = code.interval.coerceAtLeast(5)
        repeat(120) {
            delay(wait * 1000L)
            val r = runCatching {
                form(
                    "https://github.com/login/oauth/access_token",
                    mapOf("client_id" to CLIENT_ID, "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"),
                )
            }.getOrNull() ?: return@repeat
            r.optString("access_token").takeIf { it.isNotEmpty() }?.let {
                prefs(context).edit().putString("token", it).apply()
                return@withContext true
            }
            when (r.optString("error")) {
                "slow_down" -> wait += 5
                "expired_token", "access_denied" -> return@withContext false
            }
        }
        false
    }

    private fun api(context: Context, method: String, url: String, body: ByteArray? = null, type: String = "application/json"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer " + prefs(context).getString("token", ""))
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 20_000
        c.readTimeout = 120_000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        return code to text
    }

    /**
     * PUT a file to the contents API, streamed: the JSON body is written piece by
     * piece with the file base64-encoded on the fly, so a 20 MB zip never sits in
     * memory (the unit gives MMI only 128 MB).
     */
    private fun putFile(context: Context, url: String, file: File): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 20_000
        c.readTimeout = 180_000
        c.setRequestProperty("Authorization", "Bearer " + prefs(context).getString("token", ""))
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Content-Type", "application/json")
        val head = "{\"message\":\"MMI unit data: ${file.name}\",\"content\":\"".toByteArray()
        val tail = "\"}".toByteArray()
        // Exact length: base64 of n bytes is 4 * ceil(n / 3) characters.
        c.setFixedLengthStreamingMode(head.size + 4L * ((file.length() + 2) / 3) + tail.size)
        c.outputStream.use { out ->
            out.write(head)
            val b64 = android.util.Base64OutputStream(out, android.util.Base64.NO_WRAP or android.util.Base64.NO_CLOSE)
            file.inputStream().use { it.copyTo(b64, 48 * 1024) }
            b64.close()
            out.write(tail)
        }
        return c.responseCode
    }

    /** MMI's report files (scan, package list, car-message log, car-app zips) gathered in one folder. */
    fun reportFiles(context: Context): List<File> {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        WifiShare.NAMES.filter { it.endsWith(".txt") }.forEach { name ->
            runCatching { WifiShare.open(context, name)?.use { i -> File(dir, name).outputStream().use { i.copyTo(it) } } }
        }
        return dir.listFiles { f -> f.isFile && f.length() > 0 }?.sortedBy { it.name }.orEmpty()
    }

    /**
     * Step 3: uploads every report file. An interrupted send resumes: the same
     * folder is reused until everything is up, and files already there (same
     * name and size) are skipped. Returns how many files are in the folder.
     */
    suspend fun send(context: Context, files: List<File>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, _) = api(context, "GET", "https://api.github.com/repos/$REPO")
            if (code == 401) { signOut(context); error("GitHub sign-in needed — tap Send to developer again") }
            if (code == 404) error("Private repo $REPO not found — create it on GitHub first")
            if (code != 200) error("GitHub answered $code")
            val folder = prefs(context).getString("send_folder", null)
                ?: java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
                    .also { prefs(context).edit().putString("send_folder", it).apply() }
            // What already made it into this folder on an earlier, interrupted try.
            val (listCode, listing) = api(context, "GET", "https://api.github.com/repos/$REPO/contents/$folder")
            val present = HashMap<String, Long>()
            if (listCode == 200) runCatching {
                val arr = org.json.JSONArray(listing)
                for (i in 0 until arr.length()) arr.getJSONObject(i).let { present[it.getString("name")] = it.optLong("size") }
            }
            val todo = files.filter { it.isFile }
            var done = 0
            todo.forEachIndexed { i, f ->
                if (present[f.name] == f.length()) { done++; return@forEachIndexed }
                withContext(Dispatchers.Main) { onProgress("Sending ${i + 1} / ${todo.size}: ${f.name}") }
                val url = "https://api.github.com/repos/$REPO/contents/$folder/${URLEncoder.encode(f.name, "UTF-8")}"
                var up = runCatching { putFile(context, url, f) }.getOrDefault(-1)
                if (up !in 200..299) {
                    // One failed request isn't a lost sign-in: check, then try once more.
                    if (api(context, "GET", "https://api.github.com/user").first == 401) {
                        signOut(context)
                        error("GitHub sign-in needed — tap Send to developer again; it continues where it stopped")
                    }
                    up = runCatching { putFile(context, url, f) }.getOrDefault(-1)
                }
                if (up in 200..299) done++ else error("Couldn't send ${f.name} (GitHub $up) — tap again to continue")
            }
            // Everything is up: the next send starts a new folder.
            prefs(context).edit().remove("send_folder").apply()
            done
        }
    }
}
