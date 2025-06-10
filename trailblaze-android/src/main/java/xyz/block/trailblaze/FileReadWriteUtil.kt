package xyz.block.trailblaze

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utilities when running with Instrumentation and UiAutomation.
 */
object FileReadWriteUtil {

  fun getDownloadsFileUri(context: Context, fileName: String): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(MediaStore.Downloads._ID)
    val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(fileName)

    context.contentResolver.query(collection, projection, selection, selectionArgs, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
          ContentUris.withAppendedId(collection, id)
        } else {
          null
        }
      }
  } else {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val file = File(downloadsDir, fileName)
    if (file.exists()) Uri.fromFile(file) else null
  }

  fun deleteFromDownloadsIfExists(context: Context, fileName: String) {
    val uri = getDownloadsFileUri(context, fileName)
    if (uri != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.delete(uri, null, null)
      } else {
        val file = File(uri.path ?: return)
        if (file.exists()) file.delete()
      }
    }
  }

  fun writeToDownloadsFile(
    context: Context,
    fileName: String,
    contentBytes: ByteArray,
    directory: String?,
  ): Uri? {
    val resolver = context.contentResolver
    deleteFromDownloadsIfExists(context, fileName)

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Scoped storage: use MediaStore
      val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/json")
        put(MediaStore.Downloads.IS_PENDING, 1)
        directory?.let {
          put(MediaStore.Downloads.RELATIVE_PATH, "Download/$directory")
        }
      }

      val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      val uri = resolver.insert(collection, values)

      uri?.let {
        resolver.openOutputStream(it)?.use { output ->
          output.write(contentBytes)
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(it, values, null, null)
      }

      uri
    } else {
      // Legacy direct filesystem write
      val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      val recordingDir = if (directory != null) {
        File(downloadsDir, directory)
      } else {
        downloadsDir
      }
      if (!recordingDir.exists()) recordingDir.mkdirs()
      val file = File(recordingDir, fileName)

      // Ensure parent directories exist
      file.parentFile?.mkdirs()

      FileOutputStream(file).use { output ->
        output.write(contentBytes)
      }

      Uri.fromFile(file)
    }
  }

  fun readFromDownloadsFile(context: Context, fileName: String): String? {
    val uri = getDownloadsFileUri(context, fileName) ?: return null

    return try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        input.bufferedReader().use { it.readText() }
      }
    } catch (e: Exception) {
      Log.e("FileRead", "Failed to read $fileName from Downloads", e)
      null
    }
  }
}
