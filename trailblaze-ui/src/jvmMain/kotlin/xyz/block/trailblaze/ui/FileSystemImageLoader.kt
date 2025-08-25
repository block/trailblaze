package xyz.block.trailblaze.ui

import xyz.block.trailblaze.ui.images.ImageLoader

class FileSystemImageLoader(private val basePath: String) : ImageLoader {
  override fun getImageModel(sessionId: String, screenshotFile: String?): String? {
    return screenshotFile?.let { filename ->
      if (filename.startsWith("/") || filename.contains("://")) {
        // Already an absolute path or URL
        filename
      } else {
        // Construct file system path
        "$basePath/$sessionId/$filename"
      }
    }
  }
}