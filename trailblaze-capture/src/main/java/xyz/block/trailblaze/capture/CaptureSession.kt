package xyz.block.trailblaze.capture

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.util.Console

/**
 * Orchestrates multiple [CaptureStream]s for a single test session.
 *
 * Start all streams before test execution, stop all after. Artifacts are saved alongside session
 * logs.
 */
class CaptureSession(private val streams: List<CaptureStream>, private val options: CaptureOptions) {

  fun startAll(sessionDir: File, deviceId: String, appId: String?) {
    for (stream in streams) {
      try {
        Console.log("Starting ${stream.type} capture...")
        stream.start(sessionDir, deviceId, appId)
      } catch (e: Exception) {
        Console.log("Failed to start ${stream.type} capture: ${e.message}")
      }
    }
  }

  fun stopAll(): List<CaptureArtifact> {
    val artifacts = mutableListOf<CaptureArtifact>()
    for (stream in streams) {
      try {
        Console.log("Stopping ${stream.type} capture...")
        stream.stop(options)?.let { artifact ->
          artifacts.add(artifact)
          Console.log(
            "${stream.type} captured: ${artifact.file.name} (${artifact.file.length() / 1024}KB)"
          )
        }
      } catch (e: Exception) {
        Console.log("Failed to stop ${stream.type} capture: ${e.message}")
      }
    }
    // Write metadata for timeline integration
    if (artifacts.isNotEmpty()) {
      writeCaptureMetadata(artifacts)
    }
    return artifacts
  }

  private fun writeCaptureMetadata(artifacts: List<CaptureArtifact>) {
    val sessionDir = artifacts.first().file.parentFile ?: return
    val metadata =
      CaptureMetadata(
        artifacts =
          artifacts.map { artifact ->
            CaptureMetadata.ArtifactEntry(
              filename = artifact.file.name,
              type = artifact.type.name,
              startTimestampMs = artifact.startTimestampMs,
              endTimestampMs = artifact.endTimestampMs,
            )
          }
      )
    val metadataFile = File(sessionDir, "capture_metadata.json")
    val json = Json { prettyPrint = true }
    metadataFile.writeText(json.encodeToString(CaptureMetadata.serializer(), metadata))
  }

  companion object {
    /**
     * Creates a [CaptureSession] from [CaptureOptions], building the appropriate platform-specific
     * streams.
     *
     * @param platform The device platform, used to select the correct video capture implementation.
     *   Pass `null` for platforms that don't support capture (e.g., Web).
     */
    fun fromOptions(options: CaptureOptions, platform: String?): CaptureSession? {
      if (!options.hasAnyCaptureEnabled) return null
      val streams = mutableListOf<CaptureStream>()
      if (options.captureVideo) {
        when (platform) {
          "ANDROID" -> streams.add(xyz.block.trailblaze.capture.video.AndroidVideoCapture())
          // TODO: iOS video capture disabled — sprite sheet generation needs WebP migration
          //  (ffmpeg 8.0 JPEG encoder rejects iOS simulator's limited-range YUV, and the
          //  desktop UI's ImageIO.read() doesn't support WebP). See IosVideoCapture.kt for
          //  the recording + stale-lock fixes that are ready once the format is sorted out.
          // "IOS" -> streams.add(xyz.block.trailblaze.capture.video.IosVideoCapture())
        }
      }
      if (options.captureLogcat) {
        when (platform) {
          "ANDROID" -> streams.add(xyz.block.trailblaze.capture.logcat.AndroidLogcatCapture())
          "IOS" -> streams.add(xyz.block.trailblaze.capture.logcat.IosLogCapture())
        }
      }
      if (streams.isEmpty()) return null
      return CaptureSession(streams, options)
    }
  }
}

@Serializable
data class CaptureMetadata(val artifacts: List<ArtifactEntry>) {
  @Serializable
  data class ArtifactEntry(
    val filename: String,
    val type: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long?,
  )
}
