package xyz.block.trailblaze.ui

import xyz.block.trailblaze.ui.images.ImageLoader
import java.io.File

// Internal variable to store the logs directory - can be set by MainTrailblazeApp
private var _logsDirectory: File? = null

// Function to set the logs directory - should be called from MainTrailblazeApp during initialization
fun setLogsDirectory(logsDir: File) {
  _logsDirectory = logsDir
}

actual fun createLogsFileSystemImageLoader(): ImageLoader {
  // Use the set logs directory, system property, or default fallback
  val logsDir = _logsDirectory?.absolutePath
    ?: System.getProperty("trailblaze.logs.dir")
    ?: "logs"

  return FileSystemImageLoader(logsDir)
}

actual fun getCurrentUrl(): String? {
  // URL detection doesn't make sense on JVM
  return null
}

actual fun getPlatform(): Platform {
  return Platform.JVM
}
