package xyz.block.trailblaze.android.test

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidTestTimingTest {
  @Test
  fun `total attributes all measured phases`() {
    val timing =
      AndroidTestTiming(
        toolName = "tap",
        orchestrationMs = 1.25,
        nativeExecutionMs = 4.5,
        loggingMs = 0.75,
      )

    assertEquals(6.5, timing.totalMs)
  }
}
