package xyz.block.trailblaze.host

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import xyz.block.trailblaze.exception.TrailblazeException

/**
 * The threshold a snapshot-baseline comparison runs at can arrive from the run request or from
 * `TRAILBLAZE_SNAPSHOT_BASELINE_THRESHOLD` on the executing process. Both need the range check
 * `--snapshot-baseline-threshold` applies at the CLI: outside 0..100 the comparison is
 * meaningless — 500 makes nothing fail, -1 makes nothing pass — and either way the run reports a
 * result nobody asked for.
 */
class SnapshotBaselineThresholdTest {

  @Test
  fun `the request value wins, then the env var, then the default`() {
    assertEquals(5.0, TrailblazeHostYamlRunner.resolveBaselineThreshold(5.0, "9"))
    assertEquals(9.0, TrailblazeHostYamlRunner.resolveBaselineThreshold(null, "9"))
    assertEquals(2.0, TrailblazeHostYamlRunner.resolveBaselineThreshold(null, null))
    assertEquals(2.0, TrailblazeHostYamlRunner.resolveBaselineThreshold(null, "  "))
  }

  @Test
  fun `an out-of-range threshold fails from either source`() {
    assertFailsWith<TrailblazeException> { TrailblazeHostYamlRunner.resolveBaselineThreshold(-1.0, null) }
    assertFailsWith<TrailblazeException> { TrailblazeHostYamlRunner.resolveBaselineThreshold(500.0, null) }
    assertFailsWith<TrailblazeException> { TrailblazeHostYamlRunner.resolveBaselineThreshold(null, "-1") }
    assertFailsWith<TrailblazeException> { TrailblazeHostYamlRunner.resolveBaselineThreshold(null, "500") }
  }

  // A decimal comma is the everyday typo here, and silently defaulting turns "compare at 2,5%"
  // into "compare at 2%" with nothing said.
  @Test
  fun `an unparseable env value fails instead of silently defaulting`() {
    assertFailsWith<TrailblazeException> { TrailblazeHostYamlRunner.resolveBaselineThreshold(null, "2,0") }
  }

  // NaN is inside no range: both bound comparisons are false, so a plain `0..100` check accepts it
  // and every later `diffPercent > threshold` is false too — the comparison passes having gated on
  // nothing. Infinity has the same effect one bound at a time.
  @Test
  fun `a non-finite threshold is rejected from either source`() {
    assertFailsWith<TrailblazeException> { TrailblazeHostYamlRunner.resolveBaselineThreshold(Double.NaN, null) }
    assertFailsWith<TrailblazeException> { TrailblazeHostYamlRunner.resolveBaselineThreshold(null, "NaN") }
    assertFailsWith<TrailblazeException> { TrailblazeHostYamlRunner.resolveBaselineThreshold(null, "Infinity") }
  }
}
