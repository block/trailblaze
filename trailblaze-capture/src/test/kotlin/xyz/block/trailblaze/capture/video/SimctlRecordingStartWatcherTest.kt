package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where clip time zero comes from on iOS. The lines below are the real output of
 * `xcrun simctl io <udid> recordVideo` on a booted simulator, in the order it emits them, so the
 * assertions are about simctl's actual behavior rather than a guessed format.
 */
class SimctlRecordingStartWatcherTest {

  /** Host clock the test advances by hand, so each line is observed at a known instant. */
  private var now: Long = 0

  private fun watcher() = SimctlRecordingStartWatcher { now }

  @Test
  fun `the window is anchored on the first-frame announcement, not on the spawn`() {
    // Real epochs from a measured run: spawned at ...003485, display note at ...003860, first
    // frame announced at ...003874. The 389 ms in between is what a spawn-anchored window
    // swallowed, and the recording's duration came up short by exactly that much.
    val watcher = watcher()

    now = 1_789_929_003_860L
    watcher.observe("Note: No display specified. Defaulting to display: 5D11A8B7 (screenID: 1, name: LCD)")
    now = 1_789_929_003_874L
    watcher.observe("Recording started")

    assertEquals(
      1_789_929_003_874L,
      watcher.recordingStartedAtMs,
      "clip time zero is the instant simctl reported a frame — anchoring on anything earlier " +
        "claims footage the recording does not have, and the report then shows every early step " +
        "a frame from later than it should be",
    )
  }

  @Test
  fun `later output cannot move an anchor that already has footage behind it`() {
    val watcher = watcher()

    now = 1_000L
    watcher.observe("Recording started")
    now = 9_999L
    watcher.observe("Recording started")
    watcher.observe("Recording completed. Writing to disk.")

    assertEquals(
      1_000L,
      watcher.recordingStartedAtMs,
      "the first announcement wins: nine seconds of footage already sit behind this anchor, so a " +
        "second one — from an Xcode that re-announces on a display change — must not drag the " +
        "window forward on top of them",
    )
  }

  @Test
  fun `clip zero prefers the announced frame over the spawn`() {
    val spawnedAtMs = 1_789_929_003_485L
    val watcher = watcher()

    now = 1_789_929_003_874L
    watcher.observe("Recording started")

    assertEquals(
      1_789_929_003_874L,
      watcher.clipZeroMs(spawnedAtMs),
      "the measured first frame wins over the spawn — the 389 ms between them is footage the " +
        "recording does not contain",
    )
  }

  @Test
  fun `clip zero falls back to the spawn when no frame was ever announced`() {
    val spawnedAtMs = 1_789_929_003_485L
    val watcher = watcher()

    now = 1_789_929_003_860L
    watcher.observe("Note: No display specified. Defaulting to display: 5D11A8B7 (screenID: 1, name: LCD)")

    assertEquals(
      spawnedAtMs,
      watcher.clipZeroMs(spawnedAtMs),
      "a slightly-too-wide window still lets the report place steps; no window at all makes the " +
        "report drop the recording, which is a worse trade for one missing log line",
    )
  }

  @Test
  fun `only simctl's own announcement is the announcement`() {
    assertTrue(
      SimctlRecordingStartWatcher.isRecordingStartedLine("Recording started"),
      "simctl's first-frame line must be recognized",
    )
    assertTrue(
      SimctlRecordingStartWatcher.isRecordingStartedLine("  Recording started on display LCD  "),
      "surrounding space and a future suffix must still count — the line is matched as a prefix, " +
        "so a wordier Xcode does not silently fall back to the spawn",
    )
    assertFalse(
      SimctlRecordingStartWatcher.isRecordingStartedLine("Wrote video to: /sessions/Recording started/video.mp4"),
      "a session path that happens to contain the phrase is not an announcement; matching it " +
        "would anchor the window at the END of the recording",
    )
    assertFalse(
      SimctlRecordingStartWatcher.isRecordingStartedLine("Recording completed. Writing to disk."),
      "completion is not the start",
    )
  }

  @Test
  fun `a recorder that never announced a frame leaves the anchor unset`() {
    val watcher = watcher()

    now = 5_000L
    watcher.observe("An error was encountered processing the command (domain=NSPOSIXErrorDomain, code=35):")
    watcher.observe("Host recording is already in progress.")

    assertNull(
      watcher.recordingStartedAtMs,
      "no announcement means no measured anchor, so the caller falls back to the spawn instant " +
        "rather than inventing one",
    )
  }
}
