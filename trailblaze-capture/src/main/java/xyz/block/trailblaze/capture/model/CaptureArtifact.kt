package xyz.block.trailblaze.capture.model

import java.io.File

/** Describes a captured artifact (video, logcat, etc.) from a session. */
data class CaptureArtifact(
  val file: File,
  val type: CaptureType,
  val startTimestampMs: Long,
  val endTimestampMs: Long? = null,
)

enum class CaptureType {
  VIDEO,
  VIDEO_FRAMES,
  LOGCAT,
}
