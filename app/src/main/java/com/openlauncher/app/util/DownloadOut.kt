package com.openlauncher.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Writes a file into Download (or a folder inside it) on any Android version:
 * straight to the folder where storage permission allows, else through
 * Android's Downloads store (Android 10+, no permission needed), else into
 * MMI's own folder on the internal storage as a last resort.
 */
object DownloadOut {
    class Target(val stream: OutputStream, val where: String)

    fun open(context: Context, folder: String?, name: String, mime: String): Target {
        val sub = folder?.let { "$it/" } ?: ""
        runCatching {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub).apply { mkdirs() }
            return Target(File(dir, name).outputStream(), "Download/$sub")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val path = "${Environment.DIRECTORY_DOWNLOADS}/$sub"
                // Replace an earlier copy we wrote, instead of piling up "name (1)".
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, path),
                )
                val uri = resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                })!!
                return Target(resolver.openOutputStream(uri)!!, "Download/$sub")
            }
        }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, sub).apply { mkdirs() }
        return Target(File(dir, name).outputStream(), "Android/data/${context.packageName}/files/$sub")
    }

    fun writeText(context: Context, folder: String?, name: String, text: String): String {
        // Only .txt as text: Android may otherwise rename "x.sh" to "x.sh.txt".
        val t = open(context, folder, name, if (name.endsWith(".txt")) "text/plain" else "application/octet-stream")
        t.stream.use { it.write(text.toByteArray()) }
        return t.where
    }
}
