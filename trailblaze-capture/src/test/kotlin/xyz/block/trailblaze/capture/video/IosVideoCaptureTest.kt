package xyz.block.trailblaze.capture.video

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
  fun `a stop inside the recorder's startup never yields a window that runs backwards`() {
    // Clip-time zero is written by the drain thread when simctl announces its first frame, so it
    // can land after the stop path stamped its floor. The normal path re-stamps and overtakes it;
    // the path where the kill throws leaves the floor standing.
    val start = 1_700_000_000_500L
    assertEquals(
      start,
      IosVideoCapture.recordingWindowEndMs(stampedEndMs = start - 400, startMs = start),
      "an end before the start must collapse to the start, not stay negative",
    )
    // The ordinary case is untouched — this must not widen a window that was already right.
    assertEquals(
      start + 10_000,
      IosVideoCapture.recordingWindowEndMs(stampedEndMs = start + 10_000, startMs = start),
      "a window that already runs forwards is left exactly as stamped",
    )
  }

  @Test
  fun `recording temp target still matches the stale-recording pattern`() {
    // The temp target moved off java.io.tmpdir (CoreSimulatorTempFiles, boot-volume) — the
    // stale-recording cleanup must keep matching a crashed recorder at the new location.
    val target = IosVideoCapture.createRecordingTempFile()
    try {
      assertTrue(
        IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd(target.absolutePath)),
        "a recorder writing to ${target.absolutePath} must be cleaned up as stale",
      )
    } finally {
      target.delete()
    }
  }

  @Test
  fun `wildcard stays within a single path component`() {
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/video-run/other.mp4")),
      "a 'video' directory with a non-video basename must not match",
    )
  }

  @Test
  fun `the stop-time transcode is bounded by the recording it has to encode, not a flat ceiling`() {
    // The transcode runs inside session teardown, so its cap is what a wedged ffmpeg costs the
    // step. A short session must not be able to stall teardown for the length of a long one.
    val short = IosVideoCapture.transcodeTimeoutSeconds(30_000)
    val long = IosVideoCapture.transcodeTimeoutSeconds(20 * 60 * 1000L)
    assertTrue(short < long, "the bound must follow the recording's length, not sit at one value")

    // A floor, because a one-second recording still needs ffmpeg to start.
    assertTrue(IosVideoCapture.transcodeTimeoutSeconds(0) >= 60, "even an empty recording gets ffmpeg's startup")
    // The encode beats realtime comfortably, so the bound must leave room above the recording.
    assertTrue(long >= 20 * 60, "a 20-minute recording must be allowed at least its own length")
    // And a ceiling, so a mis-stamped window can't hand ffmpeg an unbounded wait.
    assertEquals(1_800L, IosVideoCapture.transcodeTimeoutSeconds(Long.MAX_VALUE / 4), "the cap still applies")
  }

  /** A `video.webm` holding [bytes] bytes, as a killed ffmpeg would leave it. */
  private fun partialWebm(bytes: Int): File =
    File.createTempFile("tb-transcode-", ".webm").apply { deleteOnExit(); writeBytes(ByteArray(bytes)) }

  @Test
  fun `a transcode that did not finish leaves no half-written recording behind`() {
    // ffmpeg destroyed on its timeout has been writing right up to the moment it was killed, and
    // the session then ships simctl's mp4 instead. CI uploads recordings by extension, so a webm
    // left beside that mp4 is published as a recording that no player can open.
    val timedOut = partialWebm(4_096)
    assertFalse(IosVideoCapture.transcodeDelivered(timedOut, exitCode = null), "a killed ffmpeg delivered nothing")
    assertFalse(timedOut.exists(), "the partial webm a killed ffmpeg left must not survive into the session dir")

    val failed = partialWebm(4_096)
    assertFalse(IosVideoCapture.transcodeDelivered(failed, exitCode = 1), "a non-zero exit delivered nothing")
    assertFalse(failed.exists(), "the same holds for the encode that ran to completion and failed")

    val empty = partialWebm(0)
    assertFalse(IosVideoCapture.transcodeDelivered(empty, exitCode = 0), "an empty file is not a recording")
    assertFalse(empty.exists(), "a zero-length target is cleaned up like any other non-delivery")
  }

  @Test
  fun `a transcode that produced a recording keeps it`() {
    // The other half of the contract: cleaning up must not reach the file the report is about to
    // play, which would cost every iOS session its recording.
    val encoded = partialWebm(4_096)
    assertTrue(IosVideoCapture.transcodeDelivered(encoded, exitCode = 0), "a clean exit with bytes is a delivery")
    assertTrue(encoded.exists(), "the delivered recording stays on disk")
  }
}
