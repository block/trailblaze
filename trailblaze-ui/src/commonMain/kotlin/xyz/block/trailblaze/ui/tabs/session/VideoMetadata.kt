package xyz.block.trailblaze.ui.tabs.session

import kotlinx.serialization.Serializable

/**
 * A session's screen recording as the desktop app knows it: where the file is and the run-clock
 * window it covers. Compose has no video decoder, so the session views scrub per-step screenshots
 * and offer the recording as a "Watch Video" hand-off to the system player; the HTML report is
 * where it plays inline.
 */
data class VideoMetadata(
  /** Absolute file system path to the recording (`video.webm`, or `video.mp4` from a host with no VP9 encoder or a legacy session). */
  val filePath: String,
  /** Epoch millis when video recording started. */
  val startTimestampMs: Long,
  /** Epoch millis when video recording ended, or null if still recording. */
  val endTimestampMs: Long?,
)

/**
 * Serializable model matching the capture_metadata.json format written by CaptureSession. Duplicated
 * from trailblaze-capture (JVM-only) so commonMain code can parse it.
 */
@Serializable
internal data class CaptureMetadataModel(
  val artifacts: List<ArtifactEntry>,
) {
  @Serializable
  data class ArtifactEntry(
    val filename: String,
    val type: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long? = null,
  )
}
