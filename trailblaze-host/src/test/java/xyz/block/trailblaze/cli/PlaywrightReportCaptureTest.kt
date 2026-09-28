package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Covers the `MAX_PLAYBACK_WAIT_MS` override resolution shared by all three exporters
 * (`--gif`, `--webp`, `--video`) — the escape hatch the timeout warnings advertise.
 * `--video` previously ignored it and hit a hardcoded Playwright timeout (https://github.com/block/trailblaze/issues/173).
 * Also pins where the frame-stepped capture puts its frames and how long each is held.
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

  // Frames are stepped through playback time, one on every timeline event and evenly spaced
  // between them, so the artifact shows each tap at the moment it happened.

  @Test fun `every event gets its own frame and no frame covers more than the interval`() {
    // The export schedule for four steps at 0/250/500/1500ms ending at 1750ms.
    val times = PlaywrightReportCapture.frameTimesFor(1_750, listOf(0, 250, 500, 1_500))
    assertEquals(listOf<Long>(0, 125, 250, 375, 500, 700, 900, 1_100, 1_300, 1_500, 1_625, 1_750), times)
    assertTrue(times.containsAll(listOf(0L, 250L, 500L, 1_500L)))
    assertTrue(times.zipWithNext().all { (a, b) -> b - a in 1..PlaywrightReportCapture.FRAME_INTERVAL_MS })
  }

  @Test fun `frameTimesFor tolerates unsorted, duplicate and out-of-range events`() {
    assertEquals(listOf<Long>(0, 200, 400), PlaywrightReportCapture.frameTimesFor(400, listOf(400, 900, -5, 200, 200)))
    assertEquals(listOf<Long>(0), PlaywrightReportCapture.frameTimesFor(0, emptyList())) // nothing to play: the end state
  }

  @Test fun `fractional instants round up so the frame shows that instant`() {
    assertEquals(251L, PlaywrightReportCapture.wholeMsAtOrAfter(250.25))
    assertEquals(250L, PlaywrightReportCapture.wholeMsAtOrAfter(250))
  }

  @Test fun `each frame is held until the next one, and the last for one interval`() {
    assertEquals(listOf(125, 125, 200), PlaywrightReportCapture.frameDurationsFor(listOf(0, 125, 250)))
    assertEquals(emptyList(), PlaywrightReportCapture.frameDurationsFor(emptyList()))
  }

  @Test fun `gif and webp load the stepped variant, video the real-time one`() {
    val html = File("/tmp/report.html")
    assertTrue(PlaywrightReportCapture.buildReportUrl(html, stepped = true).endsWith("?autoplay=step"))
    assertTrue(PlaywrightReportCapture.buildReportUrl(html).endsWith("?autoplay=1"))
  }
}
