package com.openlauncher.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Album covers for Bluetooth music: this unit's Bluetooth module only passes the
 * song, artist and timing, never a picture. Covers are looked up by artist and
 * title (iTunes Search, no key needed) when the unit is online, and kept on the
 * unit so the same song shows its cover offline afterwards.
 */
object CoverLookup {
    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    private fun key(artist: String, title: String) =
        (artist.trim().lowercase() + "|" + title.trim().lowercase()).hashCode().toUInt().toString(16)

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
    }

    suspend fun cover(context: Context, artist: String, title: String): Bitmap? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val k = key(artist, title)
        val file = File(dir(context), "$k.jpg")
        val miss = File(dir(context), "$k.none")
        if (file.isFile) return@withContext BitmapFactory.decodeFile(file.path)
        // Recently not found: don't ask again for a day.
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < 86_400_000L) return@withContext null
        runCatching {
            val term = URLEncoder.encode("$artist $title".trim(), "UTF-8")
            val json = get("https://itunes.apple.com/search?term=$term&entity=song&limit=1").inputStream.bufferedReader().use { it.readText() }
            val art = JSONObject(json).optJSONArray("results")?.optJSONObject(0)?.optString("artworkUrl100")?.takeIf { it.isNotEmpty() }
                ?: run { miss.writeText(""); return@runCatching null }
            val big = art.replace("100x100bb", "600x600bb")
            get(big).inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            BitmapFactory.decodeFile(file.path)
        }.getOrNull()
    }
}
