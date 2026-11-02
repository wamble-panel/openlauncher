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
 * and shown from there afterwards, so they also work offline. A logo placed by hand
 * at files/radio_logos/<station name>.png wins over the downloaded one.
 */
internal object RadioLogos {
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = HashSet<String>()

    private fun key(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun dir(ctx: Context) = File(ctx.filesDir, "radio_logos").apply { mkdirs() }

    /** The saved logo for [name], or null (a lookup then starts in the background). */
    fun file(ctx: Context, name: String?, freq: Float): File? {
        if (name.isNullOrBlank()) return null
        val k = key(name)
        if (k.isEmpty()) return null
        val f = File(dir(ctx), "$k.png")
        if (f.length() > 0) return f
        val none = File(dir(ctx), "$k.none")
        // Not found last time: try again after a week.
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7 * 86_400_000L) return null
        synchronized(pending) { if (!pending.add(k)) return null }
        worker.execute {
            try {
                if (download(name, f)) none.delete() else none.writeText("")
            } catch (_: Exception) {
            } finally {
                synchronized(pending) { pending.remove(k) }
            }
        }
        return null
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
