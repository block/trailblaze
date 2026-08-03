package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the stale-recording discrimination — the load-bearing rule that a fresh recorder
 * cleans up a crashed Trailblaze recording of *either* iOS video name (the whole-session `video.mp4`
 * and the mid-session `video.simctl.mp4` remainder), while leaving a non-Trailblaze recording alone.
 * Pure regex logic, no pgrep / simulator.
 */
class IosVideoCaptureTest {

  private val deviceId = "ABCD-1234"

  private fun cmd(path: String) = "xcrun simctl io $deviceId recordVideo --codec=h264 --force $path"

  @Test
  fun `matches both Trailblaze video names for the device`() {
    assertTrue(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/s1/video.mp4")),
      "whole-session video.mp4 must match",
    )
    assertTrue(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/s1/video.simctl.mp4")),
      "the mid-session remainder video.simctl.mp4 must match",
    )
    assertTrue(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/s1/video.baguette.mp4")),
      "any video<*>.mp4 basename must match",
    )
  }

  @Test
  fun `does not match a non-Trailblaze recording or a different device`() {
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/ci/logs/simulator_recording.mp4")),
      "a deliberate non-Trailblaze recording (basename not video*.mp4) must be left alone",
    )
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/s1/clip.mp4")),
      "an unrelated basename must not match",
    )
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(
        deviceId,
        "xcrun simctl io OTHER-DEVICE recordVideo --force /sessions/s1/video.mp4",
      ),
      "a recording for a different device id must not match",
    )
  }

  @Test
  fun `wildcard stays within a single path component`() {
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/video-run/other.mp4")),
      "a 'video' directory with a non-video basename must not match",
    )
  }
}
