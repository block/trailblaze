package xyz.block.trailblaze.ui.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for [FormattingUtils.formatCompactDuration] — the coarse human format behind the
 * "» Nm later" collapsed-gap badge in the exported timeline (#173).
 */
class FormattingUtilsTest {

  @Test fun `sub-minute durations render as whole seconds`() {
    assertEquals("0s", FormattingUtils.formatCompactDuration(0L))
    assertEquals("5s", FormattingUtils.formatCompactDuration(5_000L))
    assertEquals("40s", FormattingUtils.formatCompactDuration(40_900L)) // rounds down
  }

  @Test fun `minute-scale durations drop seconds`() {
    assertEquals("1m", FormattingUtils.formatCompactDuration(60_000L))
    assertEquals("37m", FormattingUtils.formatCompactDuration(37 * 60_000L + 30_000L))
  }

  @Test fun `hour-scale durations show hours and minutes`() {
    assertEquals("1h", FormattingUtils.formatCompactDuration(60 * 60_000L)) // exact hour drops minutes
    assertEquals("2h 5m", FormattingUtils.formatCompactDuration(2 * 60 * 60_000L + 5 * 60_000L))
  }

  @Test fun `negative durations are treated by magnitude`() {
    assertEquals("37m", FormattingUtils.formatCompactDuration(-37 * 60_000L))
  }
}
