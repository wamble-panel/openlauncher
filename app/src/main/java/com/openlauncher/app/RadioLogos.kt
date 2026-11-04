package com.openlauncher.app

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Station logos for the Radio page. Looked up once per station name in the free
 * radio-browser.info directory (Egyptian stations first), saved in the app's files
 * and shown from there afterwards, so they also work offline. Your own picture in
 * MMI/radio/<frequency>.png and the logos built into MMI come first.
 */
internal object RadioLogos {
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = HashSet<String>()

    private fun key(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun dir(ctx: Context) = File(ctx.filesDir, "radio_logos").apply { mkdirs() }

    /**
     * Cairo FM stations by frequency, for when a station sends no RDS name (most
     * Egyptian ones don't): its name on the Radio page and in the favourites, and the
     * name its logo is looked up by.
     */
    private val CAIRO = mapOf(
        // Greater Cairo FM (Wikipedia's list, from fmscan.org), plus two relays.
        87.8f to "Mix FM", 88.2f to "Radio Hits", 88.4f to "Holy Quran Radio", 88.7f to "Radio Masr",
        89.5f to "Middle East Radio", 90.9f to "El Radio 9090", 91.5f to "Cultural Programme", 92.1f to "NRJ Egypt",
        92.7f to "Mega FM", 93.7f to "On Sport FM", 94.1f to "Radio Masr", 95.0f to "Shaabi FM",
        95.4f to "European Programme", 98.2f to "Holy Quran Radio", 98.8f to "Music Programme", 99.0f to "99 FM",
        100.6f to "Nogoum FM", 102.2f to "Greater Cairo Radio", 102.7f to "Nile News", 104.2f to "Nile FM",
        105.3f to "Nagham FM", 105.8f to "Egyptian Songs", 106.3f to "Voice of the Arabs",
    )

    private fun fm(freq: Float) = String.format(java.util.Locale.US, "%.1f", freq)

    /** The station's name from the table, when it is a known Cairo FM frequency. */
    fun stationName(freq: Float): String? =
        if (freq <= 0f || freq > 200f) null else CAIRO.entries.firstOrNull { kotlin.math.abs(it.key - freq) < 0.05f }?.value

    /**
     * The logo for the station on [freq] (named [name] by RDS, a favourite or the table):
     *  1. your own picture: MMI/radio/<frequency>.png (or .jpg / .webp), e.g. MMI/radio/100.6.png;
     *  2. a logo built into MMI for that frequency;
     *  3. one downloaded once by the station's name (radio-browser.info), kept offline.
     * Null for now when none is saved yet (the download then starts in the background).
     */
    fun file(ctx: Context, name: String?, freq: Float): File? {
        val am = freq > 200f
        if (freq > 0f && !am) local(ctx, fm(freq))?.let { return it }
        val n = name?.takeIf { it.isNotBlank() } ?: stationName(freq) ?: return null
        val k = key(n)
        if (k.isEmpty()) return null
        val f = File(dir(ctx), "$k.png")
        if (f.length() > 0) return f
        val none = File(dir(ctx), "$k.none")
        // Not found last time: try again after a week.
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 86_400_000L) return null
        synchronized(pending) { if (!pending.add(k)) return null }
        worker.execute {
            try {
                if (download(n, f)) none.delete() else none.writeText("")
            } catch (_: Exception) {
            } finally {
                synchronized(pending) { pending.remove(k) }
            }
        }
        return null
    }

    // Own and built-in logos per frequency, looked at most every 15 s: the Radio page
    // asks often, and this runs on the screen thread.
    private val localCache = HashMap<String, Pair<Long, File?>>()

    private fun local(ctx: Context, f: String): File? {
        synchronized(localCache) {
            val hit = localCache[f]
            if (hit != null && System.currentTimeMillis() - hit.first < 15_000) return hit.second
            val own = File(android.os.Environment.getExternalStorageDirectory(), "MMI/radio")
            val file = listOf("png", "jpg", "jpeg", "webp").map { File(own, "$f.$it") }.firstOrNull { it.isFile && it.length() > 0 }
                ?: builtIn(ctx, f)
            localCache[f] = System.currentTimeMillis() to file
            return file
        }
    }

    /** A logo shipped with MMI (assets/radio_logos/<frequency>.png), copied out once so the screens can show it. */
    @Volatile private var shipped: Set<String>? = null
    @Volatile private var installed: Long? = null

    private fun builtIn(ctx: Context, f: String): File? {
        // Named after the installed version, so an update brings its new logos.
        val v = installed ?: runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L).also { installed = it }
        val out = File(dir(ctx), "builtin_${v}_$f.png")
        if (out.length() > 0) return out
        if (shipped == null) shipped = runCatching { ctx.assets.list("radio_logos")?.toSet() }.getOrNull() ?: emptySet()
        if ("$f.png" !in shipped!!) return null
        return runCatching {
            ctx.assets.open("radio_logos/$f.png").use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        }.getOrNull()
    }

    private fun download(name: String, out: File): Boolean {
        val q = URLEncoder.encode(name, "UTF-8")
        for (country in listOf("&countrycode=EG", "")) {
            val json = get("https://de1.api.radio-browser.info/json/stations/search?name=$q$country&limit=8&hidebroken=true&order=clickcount&reverse=true")
                ?.toString(Charsets.UTF_8) ?: continue
            val list = JSONArray(json)
            for (i in 0 until list.length()) {
                val icon = list.getJSONObject(i).optString("favicon").takeIf { it.startsWith("http") } ?: continue
                val bytes = get(icon) ?: continue
                // Only real pictures of a usable size.
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth < 48 || o.outHeight < 48) continue
                out.writeBytes(bytes)
                return true
            }
        }
        return false
    }

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "MMI/2.0")
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().takeIf { b -> b.size < 2_000_000 } }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
