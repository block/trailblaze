package xyz.block.trailblaze.ui

import androidx.compose.runtime.Composable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.capture.logcat.LogcatParser
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.tabs.session.CaptureMetadataModel
import xyz.block.trailblaze.ui.tabs.session.SpriteSheetInfo
import xyz.block.trailblaze.ui.tabs.session.VideoMetadata
import xyz.block.trailblaze.ui.utils.JsonDefaults

// Internal variable to store the logs directory - can be set by MainTrailblazeApp
private var _logsDirectory: File? = null

// Function to set the logs directory - should be called from MainTrailblazeApp during initialization
fun setLogsDirectory(logsDir: File) {
  _logsDirectory = logsDir
  // Cache is keyed on absolute path (which embeds the logs dir), so a new dir naturally
  // means new keys — but clear on switch anyway so tests that swap temp dirs don't see
  // stale entries from a previous session.
  networkLogReadCache.clear()
}

/**
 * Memoizes the contents of `network.ndjson` per file by `(lastModified, length)` so the
 * 1s poll loop in `SessionCombinedView` doesn't re-read tens of MB on every tick when
 * the file hasn't grown. Per-path so concurrent sessions don't thrash a single slot.
 * Cleared by JVM exit; not LRU — the working set is the small number of currently-open
 * sessions, so unbounded growth isn't a practical concern.
 */
private data class NetworkLogCacheEntry(
  val lastModified: Long,
  val length: Long,
  val content: String,
)

private val networkLogReadCache = ConcurrentHashMap<String, NetworkLogCacheEntry>()

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
      val metadata = JsonDefaults.FORWARD_COMPATIBLE
        .decodeFromString<CaptureMetadataModel>(metadataFile.readText())

      // Require a sprite sheet (VIDEO_FRAMES) for the video-frame timeline mode —
      // without one the UI has no way to render frames and sticks on "Loading frame...".
      // When only a raw VIDEO is present (e.g., ffmpeg missing on the CI runner so
      // sprite extraction was skipped), return null so the timeline falls back to
      // the screenshot slideshow.
      val spritesArtifact = metadata.artifacts.firstOrNull { it.type == "VIDEO_FRAMES" }
        ?: return@withContext null

      fun resolveFile(artifact: CaptureMetadataModel.ArtifactEntry?): File? =
        artifact?.let { File(logsDir, "$sessionId/${it.filename}") }?.takeIf { it.exists() }

      val spritesFile = resolveFile(spritesArtifact) ?: return@withContext null
      val spriteInfo = parseSpriteMetadata(File(logsDir, "$sessionId/video_sprites.txt"))
      // The original video.mp4 still exists on disk after sprite generation but
      // isn't listed as a separate artifact. Probe for it so "Watch Video" works.
      val rawVideoFile = File(logsDir, "$sessionId/video.mp4").takeIf { it.exists() }
      VideoMetadata(
        url = spritesFile.toURI().toString(),
        filePath = spritesFile.absolutePath,
        startTimestampMs = spritesArtifact.startTimestampMs,
        endTimestampMs = spritesArtifact.endTimestampMs,
        spriteInfo = spriteInfo,
        videoFilePath = rawVideoFile?.absolutePath,
      )
    } catch (e: Exception) {
      null
    }
  }
}

actual suspend fun loadDeviceLogs(sessionId: String): String? {
  val logsDir = _logsDirectory ?: return null
  return withContext(Dispatchers.IO) {
    // Check session dir first (completed sessions)
    val sessionDir = File(logsDir, sessionId)
    val logFile = LogcatParser.findDeviceLogFile(sessionDir)
      // During live sessions, device.log is in a capture temp dir. Scan for it.
      ?: findDeviceLogInCaptureTempDirs()
      ?: return@withContext null
    if (logFile.length() == 0L) return@withContext null
    try {
      logFile.readText()
    } catch (_: Exception) {
      null
    }
  }
}

/**
 * Reads `<logsDir>/<sessionId>/network.ndjson`. Both `WebNetworkCapture` and the on-device mobile
 * sinks write to this exact path with the same `NetworkEvent` schema — single loader for every
 * engine. Returns null if the file is missing or empty. Uses an mtime+length cache to skip the
 * I/O when the file hasn't grown since the last poll (long live sessions can poll faster than new
 * bytes arrive); returning the same cached String reference also lets the panel's
 * `remember(source, rawContent)` parse cache stay warm across ticks.
 */
actual suspend fun loadNetworkLogs(sessionId: String): String? {
  val logsDir = _logsDirectory ?: return null
  return withContext(Dispatchers.IO) {
    val ndjson = File(logsDir, "$sessionId/network.ndjson")
    if (!ndjson.exists()) return@withContext null
    val length = ndjson.length()
    if (length == 0L) return@withContext null
    val mtime = ndjson.lastModified()
    val key = ndjson.absolutePath
    val cached = networkLogReadCache[key]
    if (cached != null && cached.lastModified == mtime && cached.length == length) {
      return@withContext cached.content
    }
    try {
      val content = ndjson.readText()
      networkLogReadCache[key] = NetworkLogCacheEntry(mtime, length, content)
      content
    } catch (_: Exception) {
      null
    }
  }
}

/** Scans temp capture directories for an in-progress device.log during live sessions. */
private fun findDeviceLogInCaptureTempDirs(): File? {
  val tmpDir = File(System.getProperty("java.io.tmpdir"))
  val captureDirs = tmpDir.listFiles { file ->
    file.isDirectory && file.name.startsWith("trailblaze-capture-")
  } ?: return null
  // Return the most recently modified device.log across all capture dirs
  return captureDirs
    .sortedByDescending { it.lastModified() }
    .firstNotNullOfOrNull { LogcatParser.findDeviceLogFile(it) }
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
