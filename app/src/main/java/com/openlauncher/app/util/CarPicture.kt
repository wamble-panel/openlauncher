package com.openlauncher.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.openlauncher.app.map.CarProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The owner's own photo of each car for the home screen, imported on the unit
 * (never shipped with the app). Photos on a white background are cut out here.
 */
object CarPicture {
    private val _version = MutableStateFlow(0)
    /** Bumped when a picture is imported or removed. */
    val version: StateFlow<Int> = _version

    private fun file(context: Context, car: CarProfile) = File(context.filesDir, "car-${car.name.lowercase()}.png")

    fun has(context: Context, car: CarProfile) = file(context, car).length() > 0

    suspend fun load(context: Context, car: CarProfile): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(context, car)
        if (f.length() > 0) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null
    }

    fun remove(context: Context, car: CarProfile) {
        file(context, car).delete()
        _version.value++
    }

    suspend fun import(context: Context, uri: Uri, car: CarProfile): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
            val cut = cutOut(src.copy(Bitmap.Config.ARGB_8888, true))
            val scaled = if (cut.width > 900) Bitmap.createScaledBitmap(cut, 900, cut.height * 900 / cut.width, true) else cut
            file(context, car).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            _version.value++
            true
        }.getOrDefault(false)
    }

    /**
     * Removes a plain light background: flood-fills near-white pixels from the
     * borders (so white paint inside the car is kept), feathers the edge, crops.
     * Photos that already have transparency are only cropped.
     */
    private fun cutOut(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hasAlpha = px.any { Color.alpha(it) < 250 }
        if (!hasAlpha) {
            fun isBg(c: Int): Boolean {
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                return r > 232 && g > 232 && b > 232 && max(r, max(g, b)) - min(r, min(g, b)) < 14
            }
            val bg = BooleanArray(w * h)
            val queue = IntArray(w * h)
            var head = 0
            var tail = 0
            fun seed(i: Int) {
                if (!bg[i] && isBg(px[i])) { bg[i] = true; queue[tail++] = i }
            }
            for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
            for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0) seed(i - 1)
                if (x < w - 1) seed(i + 1)
                if (y > 0) seed(i - w)
                if (y < h - 1) seed(i + w)
            }
            for (i in px.indices) {
                if (bg[i]) { px[i] = 0; continue }
                // Feather: pixels touching the background become half transparent.
                val x = i % w
                val y = i / w
                val edge = (x > 0 && bg[i - 1]) || (x < w - 1 && bg[i + 1]) || (y > 0 && bg[i - w]) || (y < h - 1 && bg[i + w])
                if (edge) px[i] = (px[i] and 0x00FFFFFF) or (0x80 shl 24)
            }
        }
        var left = w; var right = -1; var top = h; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(px[y * w + x]) > 20) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return if (right > left && bottom > top) Bitmap.createBitmap(out, left, top, right - left + 1, bottom - top + 1) else out
    }
}
