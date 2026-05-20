package xyz.block.trailblaze.capture.model

import java.io.File

/**
 * Describes a captured artifact (video, logcat, etc.) from a session.
 *
 * **Timing semantics.** [startTimestampMs] and [endTimestampMs] are recorder-observed
 * wall-clock bookends, captured *before* any platform-specific finalize step (Android's
 * ffmpeg concat+wrap, iOS's `SIGINT` + `xcrun` flush, Playwright's `BrowserContext.close`).
 * Downstream consumers — notably the report viewer's timeline math and
 * `VideoSpriteExtractor`'s wall-clock-vs-mp4 sanity check — treat these as the user-perceived
 * recording window, not the artifact-write window. Capturing them post-finalize would inflate
 * the window by seconds on Android (ffmpeg wrap time) or iOS (simulator moov-atom flush) and
 * skew everything downstream.
 */
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
