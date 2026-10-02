package com.sphy.airconcontroller.storage

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/** Saves text files to the shared Download/OpenDiKey folder, visible to file managers and adb. */
object PublicDownloads {
    private const val TAG = "PublicDownloads"
    private const val SUBDIR = "OpenDiKey"

    /**
     * Writes [text] to Download/OpenDiKey/[name], replacing a copy this install wrote earlier.
     * MediaStore renames to "name (1).txt" if another install left a file with the same name.
     * Returns the absolute path, or null if the write failed.
     */
    fun saveText(context: Context, name: String, text: String): String? = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/"
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, relativePath)
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
        resolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val savedName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        File(downloads, "$SUBDIR/$savedName").absolutePath
    }.onFailure { Log.w(TAG, "saveText $name", it) }.getOrNull()
}
