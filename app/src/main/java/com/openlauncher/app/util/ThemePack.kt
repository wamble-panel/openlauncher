package com.openlauncher.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** A loaded theme pack: home background, chrome menu icons and where the ring lies in the background. */
class LoadedTheme(
    val background: ImageBitmap?,
    val icons: Map<String, ImageBitmap>,
    /** Ring centre line as fractions of the background: cx, cy, rx, ry. */
    val ring: FloatArray?,
)

/**
 * Owner-supplied artwork (a zip imported on the unit), so the home screen can use
 * the real MMI background and chrome icons without them being part of the app.
 * Pack contents: background.jpg/png, dashboard/video/settings/music/phone/navigation/apps.png
 * and theme.txt with "ring=cx,cy,rx,ry".
 */
object ThemePack {
    private val ALLOWED = setOf(
        "background.jpg", "background.png", "dashboard.png", "video.png", "settings.png",
        "music.png", "phone.png", "navigation.png", "apps.png", "radio.png", "theme.txt",
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _theme = MutableStateFlow<LoadedTheme?>(null)
    val theme: StateFlow<LoadedTheme?> = _theme

    private fun dir(context: Context) = File(context.filesDir, "theme")

    /** True when the owner imported their own pack (otherwise the built-in MMI art is used). */
    fun isImported(context: Context) = dir(context).listFiles()?.isNotEmpty() == true

    private fun prefs(context: Context) = context.getSharedPreferences("mmi_theme", Context.MODE_PRIVATE)

    /** Whether the artwork is shown at all (off = the drawn theme). */
    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        scope.launch { _theme.value = load(context.applicationContext) }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch { _theme.value = load(app) }
    }

    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = dir(context).apply { deleteRecursively(); mkdirs() }
            var count = 0
            ZipInputStream(context.contentResolver.openInputStream(uri)!!.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Only known file names, never paths, so a pack can't write anywhere else.
                    val name = entry.name.substringAfterLast('/').lowercase()
                    if (!entry.isDirectory && name in ALLOWED) {
                        File(target, name).outputStream().use { zip.copyTo(it) }
                        count++
                    }
                }
            }
            _theme.value = load(context)
            count > 0
        }.getOrDefault(false)
    }

    /** Drops the imported pack; the built-in MMI art takes over again. */
    fun remove(context: Context) {
        dir(context).deleteRecursively()
        scope.launch { _theme.value = load(context.applicationContext) }
    }

    private fun load(context: Context): LoadedTheme? {
        if (!isEnabled(context)) return null
        val d = dir(context)
        val imported = isImported(context)
        // Imported pack first, else the MMI artwork shipped in the app.
        fun open(name: String): java.io.InputStream? =
            if (imported) File(d, name).takeIf { it.exists() }?.inputStream()
            else runCatching { context.assets.open("theme-default/$name") }.getOrNull()
        fun image(name: String, maxWidth: Int): ImageBitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (open(name) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            open(name)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap()
        }.getOrNull()
        val background = image("background.jpg", 1600) ?: image("background.png", 1600)
        val icons = listOf("dashboard", "video", "settings", "music", "phone", "navigation", "apps", "radio")
            .mapNotNull { key -> image("$key.png", 256)?.let { key to it } }.toMap()
        val ring = runCatching {
            open("theme.txt")?.bufferedReader()?.use { it.readLines() }?.firstOrNull { it.startsWith("ring=") }
                ?.removePrefix("ring=")?.split(',')?.map { it.trim().toFloat() }?.takeIf { it.size == 4 }?.toFloatArray()
        }.getOrNull()
        if (background == null && icons.isEmpty()) return null
        return LoadedTheme(background, icons, ring)
    }
}
