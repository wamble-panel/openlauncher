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

    fun isSet(context: Context) = dir(context).listFiles()?.isNotEmpty() == true

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

    fun remove(context: Context) {
        dir(context).deleteRecursively()
        _theme.value = null
    }

    private fun load(context: Context): LoadedTheme? {
        val d = dir(context)
        if (!d.isDirectory) return null
        fun image(name: String, maxWidth: Int): ImageBitmap? {
            val f = File(d, name)
            if (!f.exists()) return null
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        val background = image("background.jpg", 1600) ?: image("background.png", 1600)
        val icons = listOf("dashboard", "video", "settings", "music", "phone", "navigation", "apps", "radio")
            .mapNotNull { key -> image("$key.png", 256)?.let { key to it } }.toMap()
        val ring = runCatching {
            File(d, "theme.txt").readLines().firstOrNull { it.startsWith("ring=") }
                ?.removePrefix("ring=")?.split(',')?.map { it.trim().toFloat() }?.takeIf { it.size == 4 }?.toFloatArray()
        }.getOrNull()
        if (background == null && icons.isEmpty()) return null
        return LoadedTheme(background, icons, ring)
    }
}
