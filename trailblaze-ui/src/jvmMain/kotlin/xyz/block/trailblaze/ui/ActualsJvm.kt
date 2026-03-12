package xyz.block.trailblaze.ui

import androidx.compose.runtime.Composable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.tabs.session.CaptureMetadataModel
import xyz.block.trailblaze.ui.tabs.session.SpriteSheetInfo
import xyz.block.trailblaze.ui.tabs.session.VideoMetadata

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

@Composable
actual fun resolveImageModel(sessionId: String, screenshotFile: String?, imageLoader: ImageLoader): Any? {
  // On JVM, images are loaded via file URLs - no lazy loading needed
  return imageLoader.getImageModel(sessionId, screenshotFile)
}

actual fun openVideoInSystemPlayer(filePath: String) {
  try {
    java.awt.Desktop.getDesktop().open(File(filePath))
  } catch (_: Exception) {
    try {
      ProcessBuilder("open", filePath).start()
    } catch (_: Exception) {}
  }
}

actual suspend fun loadCaptureVideoMetadata(sessionId: String): VideoMetadata? {
  val logsDir = _logsDirectory ?: return null
  return withContext(Dispatchers.IO) {
    try {
      val metadataFile = File(logsDir, "$sessionId/capture_metadata.json")
      if (!metadataFile.exists()) return@withContext null
      val json = Json { ignoreUnknownKeys = true }
      val metadata = json.decodeFromString<CaptureMetadataModel>(metadataFile.readText())

      // Prefer sprite sheet (VIDEO_FRAMES) over raw video (VIDEO)
      val spritesArtifact = metadata.artifacts.firstOrNull { it.type == "VIDEO_FRAMES" }
      if (spritesArtifact != null) {
        val spriteFile = File(logsDir, "$sessionId/${spritesArtifact.filename}")
        if (spriteFile.exists()) {
          val spriteInfo = parseSpriteMetadata(File(logsDir, "$sessionId/video_sprites.txt"))
          return@withContext VideoMetadata(
            url = spriteFile.toURI().toString(),
            filePath = spriteFile.absolutePath,
            startTimestampMs = spritesArtifact.startTimestampMs,
            endTimestampMs = spritesArtifact.endTimestampMs,
            spriteInfo = spriteInfo,
          )
        }
      }

      // Fallback to raw video
      val videoArtifact =
        metadata.artifacts.firstOrNull { it.type == "VIDEO" } ?: return@withContext null
      val videoFile = File(logsDir, "$sessionId/${videoArtifact.filename}")
      if (!videoFile.exists()) return@withContext null
      VideoMetadata(
        url = videoFile.toURI().toString(),
        filePath = videoFile.absolutePath,
        startTimestampMs = videoArtifact.startTimestampMs,
        endTimestampMs = videoArtifact.endTimestampMs,
      )
    } catch (e: Exception) {
      null
    }
  }
}

private fun parseSpriteMetadata(metaFile: File): SpriteSheetInfo? {
  if (!metaFile.exists()) return null
  return try {
    val props = metaFile.readLines().associate {
      val (k, v) = it.split("=", limit = 2)
      k.trim() to v.trim()
    }
    val frameCount = props["frames"]?.toIntOrNull() ?: return null
    val uniqueFrameCount = props["uniqueFrames"]?.toIntOrNull()
    val frameMap = props["frameMap"]?.split(",")?.map { it.toInt() }?.toIntArray()
    SpriteSheetInfo(
      fps = props["fps"]?.toIntOrNull() ?: return null,
      frameCount = frameCount,
      frameHeight = props["height"]?.toIntOrNull() ?: return null,
      columns = props["columns"]?.toIntOrNull() ?: 1,
      rows = props["rows"]?.toIntOrNull() ?: (uniqueFrameCount ?: frameCount),
      uniqueFrameCount = uniqueFrameCount,
      frameMap = frameMap,
    )
  } catch (e: Exception) {
    null
  }
}
