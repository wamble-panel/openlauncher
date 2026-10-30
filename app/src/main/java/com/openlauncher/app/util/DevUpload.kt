package com.openlauncher.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Send to developer": uploads MMI's reports and car-app exports straight to the
 * owner's GitHub repo (as files on the install-mmi release), so nothing has to
 * be picked by hand in a browser. Signing in uses GitHub's device flow: MMI shows
 * a short code once, the owner approves it on their phone, and the token is kept
 * on the unit for later uploads.
 */
object DevUpload {
    /** Client ID of the owner's "MMI" OAuth app (device flow enabled). Empty until set up. */
    const val CLIENT_ID = "Ov23limBTF4PpuP8nyzN"
    private const val REPO = "wamble-panel/openlauncher"
    private const val TAG = "install-mmi"
    private val SKIP = setOf("MMI.apk", "version.txt") // the release's own files

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
            val r = form("https://github.com/login/device/code", mapOf("client_id" to CLIENT_ID, "scope" to "public_repo"))
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

    /** MMI's report files (scan, package list, car-message log, car-app zips) gathered in one folder. */
    fun reportFiles(context: Context): List<File> {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        WifiShare.NAMES.filter { it.endsWith(".txt") }.forEach { name ->
            runCatching { WifiShare.open(context, name)?.use { i -> File(dir, name).outputStream().use { i.copyTo(it) } } }
        }
        return dir.listFiles { f -> f.isFile && f.length() > 0 }?.sortedBy { it.name }.orEmpty()
    }

    /** Step 3: uploads every report file; returns how many went up, or an error message. */
    suspend fun send(context: Context, files: List<File>, onProgress: (String) -> Unit): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, release) = api(context, "GET", "https://api.github.com/repos/$REPO/releases/tags/$TAG")
            if (code == 401) { signOut(context); error("GitHub sign-in expired — tap Send to developer again") }
            if (code != 200) error("GitHub answered $code")
            val json = JSONObject(release)
            val id = json.getLong("id")
            val existing = json.optJSONArray("assets") ?: JSONArray()
            var sent = 0
            files.filter { it.isFile && it.name !in SKIP }.forEachIndexed { i, f ->
                withContext(Dispatchers.Main) { onProgress("Sending ${i + 1} / ${files.size}: ${f.name}") }
                // Replace an older copy of the same file.
                for (a in 0 until existing.length()) {
                    val asset = existing.getJSONObject(a)
                    if (asset.getString("name") == f.name) api(context, "DELETE", "https://api.github.com/repos/$REPO/releases/assets/${asset.getLong("id")}")
                }
                val (up, _) = api(
                    context, "POST", "https://uploads.github.com/repos/$REPO/releases/$id/assets?name=${URLEncoder.encode(f.name, "UTF-8")}",
                    f.readBytes(), "application/octet-stream",
                )
                if (up in 200..299) sent++
            }
            sent
        }
    }
}
