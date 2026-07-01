package xyz.block.trailblaze.cli

import kotlin.test.assertEquals
import org.junit.Test

/**
 * Covers the `MAX_PLAYBACK_WAIT_MS` override resolution shared by all three exporters
 * (`--gif`, `--webp`, `--video`) — the escape hatch the timeout warnings advertise.
 * `--video` previously ignored it and hit a hardcoded Playwright timeout (https://github.com/block/trailblaze/issues/173).
 */
class PlaywrightReportCaptureTest {

  private val default = PlaywrightReportCapture.DEFAULT_MAX_PLAYBACK_WAIT_MS

  @Test fun `null and blank fall back to the default`() {
    assertEquals(default, PlaywrightReportCapture.resolveMaxPlaybackWaitMs(null))
    assertEquals(default, PlaywrightReportCapture.resolveMaxPlaybackWaitMs(""))
    assertEquals(default, PlaywrightReportCapture.resolveMaxPlaybackWaitMs("   "))
  }

  @Test fun `non-numeric and non-positive fall back to the default`() {
    assertEquals(default, PlaywrightReportCapture.resolveMaxPlaybackWaitMs("soon"))
    assertEquals(default, PlaywrightReportCapture.resolveMaxPlaybackWaitMs("0"))
    assertEquals(default, PlaywrightReportCapture.resolveMaxPlaybackWaitMs("-1000"))
  }

  @Test fun `a valid positive override wins`() {
    assertEquals(1_800_000L, PlaywrightReportCapture.resolveMaxPlaybackWaitMs("1800000"))
    assertEquals(1_800_000L, PlaywrightReportCapture.resolveMaxPlaybackWaitMs("  1800000  "))
  }

  // computeFps — frames are emitted on a fixed 200ms (5fps) cadence; the rate is measured
  // from real elapsed time and clamped to [1, 20] so a degenerate window can't produce a
  // non-physical encode rate. Relevant to the truncated fail-soft capture path (#173).

  @Test fun `computeFps returns the nominal rate when no time elapsed`() {
    assertEquals(5, PlaywrightReportCapture.computeFps(frameCount = 1, elapsedMs = 0))
  }

  @Test fun `computeFps measures real-time rate for a normal capture`() {
    // 30 frames over 6s = 5fps.
    assertEquals(5, PlaywrightReportCapture.computeFps(frameCount = 30, elapsedMs = 6_000))
    // A slow capture (screenshots lagged the cadence) under-reports honestly, not clamped up.
    assertEquals(2, PlaywrightReportCapture.computeFps(frameCount = 12, elapsedMs = 6_000))
    // A fast tail (e.g. 8fps) is within the physical ceiling and passes through unclamped.
    assertEquals(8, PlaywrightReportCapture.computeFps(frameCount = 48, elapsedMs = 6_000))
  }

  @Test fun `computeFps clamps the degenerate slow and non-physical fast extremes`() {
    // One frame over a long truncated window -> floor of 1, never 0.
    assertEquals(1, PlaywrightReportCapture.computeFps(frameCount = 1, elapsedMs = 60_000))
    // A non-physical spike (clock skew / near-empty capture) -> capped at 4x nominal (20).
    assertEquals(20, PlaywrightReportCapture.computeFps(frameCount = 50, elapsedMs = 100))
  }
}
