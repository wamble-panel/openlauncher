package com.openlauncher.app.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The owner's startup video (e.g. the car maker's logo animation), imported once
 * from USB. Kept on the unit only — it is not part of the app's source.
 */
object IntroVideo {
    fun file(context: Context) = File(context.filesDir, "intro.mp4")

    fun isSet(context: Context) = file(context).length() > 0

    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = file(context)
            val tmp = File(target.parentFile, "intro.part")
            context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target) || run { tmp.copyTo(target, overwrite = true); tmp.delete() }
        }.getOrDefault(false)
    }

    fun remove(context: Context) {
        file(context).delete()
    }
}
