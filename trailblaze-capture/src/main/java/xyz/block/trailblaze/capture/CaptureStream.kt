package xyz.block.trailblaze.capture

import java.io.File
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType

/**
 * A single capture stream (video, logcat, etc.) that records during a session.
 *
 * Implementations manage background processes (e.g., `adb screenrecord`, `adb logcat`) and produce
 * a [CaptureArtifact] when stopped.
 */
interface CaptureStream {
  val type: CaptureType

  /**
   * Starts capturing. The output file is written to [sessionDir].
   *
   * @param sessionDir Directory where session logs are stored
   * @param deviceId Device identifier (e.g., "emulator-5554")
   * @param appId Package name of the app under test, if known
   */
  fun start(sessionDir: File, deviceId: String, appId: String?)

  /**
   * Stops capturing and returns the artifact, or null if capture failed.
   *
   * Implementations should gracefully handle cases where the process already exited.
   *
   * @param options Capture options controlling sprite sheet quality, fps, etc.
   */
  fun stop(options: CaptureOptions = CaptureOptions.NONE): CaptureArtifact?
}
