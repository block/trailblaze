package xyz.block.trailblaze.ui

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.capture.logcat.LogcatParser
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.tabs.session.CaptureMetadataModel
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

actual fun getPlatform(): Platform {
  return Platform.JVM
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

      fun resolveFile(artifact: CaptureMetadataModel.ArtifactEntry?): File? =
        artifact?.let { File(logsDir, "$sessionId/${it.filename}") }?.takeIf { it.exists() }

      // The recording itself: the live VP9 encode on Android (VIDEO_WEBM), the muxed mp4 elsewhere
      // (VIDEO). A session captured before sprite sheets were retired lists only the sheet
      // (VIDEO_FRAMES), which nothing here can play — but its bookends are the recorder's, so the
      // video file such a session left on disk is offered under them. A multi-device session lists
      // one recording per device, the start device's first: that device's is the session's video,
      // even when a companion's was encoded in the preferred format and its own was not.
      val videos = metadata.artifacts.filter { it.type == "VIDEO_WEBM" || it.type == "VIDEO" }
      val startDevice = videos.firstOrNull()?.deviceName
      val startDeviceVideos = videos.filter { it.deviceName == startDevice }
      val recording = startDeviceVideos.firstOrNull { it.type == "VIDEO_WEBM" } ?: startDeviceVideos.firstOrNull()
      val recordingFile = resolveFile(recording)
      if (recording != null && recordingFile != null) {
        return@withContext VideoMetadata(
          filePath = recordingFile.absolutePath,
          startTimestampMs = recording.startTimestampMs,
          endTimestampMs = recording.endTimestampMs,
        )
      }
      val legacySheet = metadata.artifacts.firstOrNull { it.type == "VIDEO_FRAMES" } ?: return@withContext null
      val legacyFile = listOf("video.webm", "video.mp4")
        .map { File(logsDir, "$sessionId/$it") }
        .firstOrNull { it.exists() } ?: return@withContext null
      VideoMetadata(
        filePath = legacyFile.absolutePath,
        startTimestampMs = legacySheet.startTimestampMs,
        endTimestampMs = legacySheet.endTimestampMs,
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
    val length = logFile.length()
    if (length == 0L) return@withContext null
    try {
      if (length <= MAX_DEVICE_LOG_BYTES) {
        logFile.readText()
      } else {
        // device.log can grow to gigabytes during a long live session. Reading it whole
        // into a String exhausts the heap (a 1 GB file is ~2 GB of UTF-16), and once it
        // crosses ~2 GB the array allocation itself fails ("Required array length … too
        // large"), which previously crashed the app even on reopen. The panel only shows
        // the most recent output, so read just the tail.
        readFileTail(logFile, MAX_DEVICE_LOG_BYTES)
      }
    } catch (_: Exception) {
      null
    }
  }
}

/** Max bytes loaded into memory for the Device Logs panel. The panel renders recent lines. */
private const val MAX_DEVICE_LOG_BYTES = 8L * 1024 * 1024

/**
 * Reads the last [maxBytes] of [file] as UTF-8, dropping the (likely partial) first line and
 * prepending a truncation marker so the panel makes clear it isn't showing the whole file.
 */
private fun readFileTail(file: File, maxBytes: Long): String {
  java.io.RandomAccessFile(file, "r").use { raf ->
    val fileLength = raf.length()
    val start = (fileLength - maxBytes).coerceAtLeast(0L)
    raf.seek(start)
    val bytes = ByteArray((fileLength - start).toInt())
    raf.readFully(bytes)
    var text = bytes.toString(Charsets.UTF_8)
    if (start > 0L) {
      val firstNewline = text.indexOf('\n')
      if (firstNewline >= 0) text = text.substring(firstNewline + 1)
      text = "… [device log truncated — showing last ${maxBytes / (1024 * 1024)} MB] …\n$text"
    }
    return text
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
