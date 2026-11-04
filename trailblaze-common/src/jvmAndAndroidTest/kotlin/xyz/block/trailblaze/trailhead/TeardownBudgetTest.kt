package xyz.block.trailblaze.trailhead

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TeardownBudgetTest {
  @Test
  fun `an unset timeout retains the 45 second cleanup budget`() {
    assertEquals(45.seconds, TeardownTrailblazeTool.resolveBudget(null))
  }

  @Test
  fun `a lane can choose a positive timeout up to ten minutes`() {
    assertEquals(1.milliseconds, TeardownTrailblazeTool.resolveBudget("1"))
    assertEquals(180.seconds, TeardownTrailblazeTool.resolveBudget(" 180000 "))
    assertEquals(600.seconds, TeardownTrailblazeTool.resolveBudget("600000"))
  }

  @Test
  fun `invalid or out of range timeouts retain the default budget`() {
    for (raw in listOf("", " ", "invalid", "1.5", "0", "-1", "600001", Long.MAX_VALUE.toString(), "9999999999999999999999")) {
      assertEquals(45.seconds, TeardownTrailblazeTool.resolveBudget(raw), "timeout: $raw")
    }
  }
}
