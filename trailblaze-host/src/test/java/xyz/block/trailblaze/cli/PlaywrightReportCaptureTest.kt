package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
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

  // The cross-language half of the export-dwell invariant. The floor a step is held for lives in
  // TypeScript (EXPORT_GAP_MIN_MS in run-report-playback.ts); the cadence it must clear lives here
  // in Kotlin. Each side's own tests can only pin its own constant, so raising FRAME_INTERVAL_MS
  // would silently start dropping steps from every export with the TypeScript guard still green.
  // This reads the other language's source and asserts the ordering from this side.

  /**
   * The `.ts` is excluded from `processResources`, so it isn't on the test classpath and has to be
   * found in the source tree. Resolved by walking ancestors for a sibling
   * `trailblaze-report/src/main/resources/...`, which lands the same way whichever depth the
   * sibling modules sit at — this test must not care how far down the tree its module lives.
   */
  private fun readPlaybackTs(): String {
    val suffix = "trailblaze-report/src/main/resources/xyz/block/trailblaze/" +
      "trailrunner/web/app/run-report-playback.ts"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    val tried = mutableListOf<String>()
    while (dir != null) {
      val candidate = File(dir, suffix)
      tried += candidate.path
      if (candidate.isFile) return candidate.readText()
      dir = dir.parentFile
    }
    // Fail loudly rather than skipping: a "can't find it" that passes would retire the invariant
    // silently, which is the exact failure this test exists to prevent.
    fail("Could not locate run-report-playback.ts. Looked for:\n" + tried.joinToString("\n"))
  }

  @Test fun `the export dwell floor clears the capture cadence`() {
    val ts = readPlaybackTs()
    val floorMs = Regex("""const\s+EXPORT_GAP_MIN_MS\s*=\s*(\d+)\s*;""")
      .find(ts)
      ?.groupValues
      ?.get(1)
      ?.toLong()
      ?: fail("EXPORT_GAP_MIN_MS not found in run-report-playback.ts — was it renamed?")

    assertTrue(
      PlaywrightReportCapture.FRAME_INTERVAL_MS <= floorMs,
      "FRAME_INTERVAL_MS (${PlaywrightReportCapture.FRAME_INTERVAL_MS}ms) must stay at or below the " +
        "export dwell floor EXPORT_GAP_MIN_MS (${floorMs}ms) in run-report-playback.ts. A step held " +
        "for less than one shutter period can fall between two captures and appear in no frame of " +
        "the exported animation. Lower the cadence, raise the floor, or switch the capture loop to " +
        "shooting on step transitions instead of on a timer.",
    )
  }
}
