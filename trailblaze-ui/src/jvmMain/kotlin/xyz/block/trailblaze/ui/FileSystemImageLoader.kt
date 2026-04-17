package xyz.block.trailblaze.ui

import java.io.File
import xyz.block.trailblaze.ui.images.ImageLoader

class FileSystemImageLoader(private val basePath: String) : ImageLoader {
  override fun getImageModel(sessionId: String, screenshotFile: String?): String? {
    return screenshotFile?.let { filename ->
      if (filename.contains("://")) {
        // Already a URL (file://, http://, etc.)
        filename
      } else if (filename.startsWith("/")) {
        // Absolute path — convert to file:// URI for Coil
        File(filename).toURI().toString()
      } else {
        // Relative path — construct full path and convert to file:// URI
        File("$basePath/$sessionId/$filename").toURI().toString()
      }
    }
  }
}