package xyz.block.trailblaze.capture.model

import java.io.File

/**
 * Describes a captured artifact (video, logcat, etc.) from a session.
 *
 * **Timing semantics.** [startTimestampMs] and [endTimestampMs] are recorder-observed
 * wall-clock bookends, captured *before* any platform-specific finalize step (Android's
 * ffmpeg concat+wrap, iOS's `SIGINT` + `xcrun` flush, Playwright's `BrowserContext.close`).
 * Downstream consumers — notably the report viewer's timeline math, which scales clip time onto
 * this window — treat these as the user-perceived recording window, not the artifact-write
 * window. Capturing them post-finalize would inflate the window by seconds on Android (ffmpeg
 * wrap time) or iOS (simulator moov-atom flush) and skew everything downstream.
 *
 * **Which clock.** Every video recorder bookends on the **host** clock, because that is the clock
 * the timeline math runs on: readers put a session's mixed host- and device-stamped logs onto the
 * host clock (`normalizedToHostClock`) before placing any of them on the recording. A recorder that
 * "helpfully" converts its window to the device clock puts the skew back. The logcat window is the
 * exception and says so at its own recorder — its bookends describe device-stamped log lines.
 *
 * **What the window means.** One contract, every platform: **clip time 0 is [startTimestampMs], and
 * the recording's last frame is [endTimestampMs]**. The window describes the footage in the file,
 * not the lifetime of the process that produced it — a recorder takes time to attach to a display
 * before its first frame, and keeps capturing while a stop signal is in flight. Bookending on the
 * spawn and the stop *call* instead claims footage the file does not contain, and the report, which
 * scales clip time onto this window, then shows the wrong frame for a step. Each recorder documents
 * how it observes those two instants:
 *  - Android and iOS-baguette read them off the mux, which stamps each frame as it arrives.
 *  - iOS simctl reads the first from the recorder announcing its first frame, and the last from
 *    when its stop signal was delivered.
 *  - The web screencast owns its frames' timestamps and pads the muxed timeline to the window, so
 *    the two agree by construction.
 *  - Playwright owns its own recording, so neither bookend is a call this pipeline makes: the first
 *    comes from the browser manager reporting the page it films, and the last from the delivered
 *    file's own duration. See `PlaywrightVideoCapture`.
 */
data class CaptureArtifact(
  val file: File,
  val type: CaptureType,
  val startTimestampMs: Long,
  val endTimestampMs: Long? = null,
)

enum class CaptureType {
  /**
   * The session recording as an H.264 mp4 — only on a host whose ffmpeg can't encode VP9, and in
   * sessions captured before recordings were WebM. The report plays it the same way.
   */
  VIDEO,

  /**
   * The session recording as a VP9 WebM, the container browsers play inline — what the HTML report
   * embeds and plays as the session's timeline. Every platform writes this given a VP9 encoder:
   * live during the session on Android and iOS (baguette), at stop elsewhere. See `RecordingFormat`.
   */
  VIDEO_WEBM,
  LOGCAT,
  /** Android memory samples, written as the `memory` session-event stream (no standalone artifact). */
  MEMORY,
}
