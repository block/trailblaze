package xyz.block.trailblaze.host.axe

import org.junit.Test
import xyz.block.trailblaze.host.ios.IosDriverAction
import kotlin.test.assertEquals

/** A repeated tap goes out as one multi-tap session; a single tap keeps the plain tap path. */
class AxeDeviceManagerRepeatTapTest {

  private val ok = AxeCli.Result(exitCode = 0, stdout = "", stderr = "")
  private val singleTaps = mutableListOf<Pair<Int, Int>>()
  private val multiTaps = mutableListOf<Triple<Int, Int, Int>>()

  private val manager = AxeDeviceManager(
    udid = "test-udid",
    deviceWidth = 400,
    deviceHeight = 800,
    tapXy = { x, y -> singleTaps += x to y; ok },
    multiTapXy = { x, y, count -> multiTaps += Triple(x, y, count); ok },
  )

  @Test
  fun `a double tap is dispatched as one two-tap session`() {
    val result = manager.execute(IosDriverAction.Tap(100, 65, repeat = 2))

    assertEquals(listOf(Triple(100, 65, 2)), multiTaps)
    assertEquals(emptyList(), singleTaps)
    assertEquals(100 to 65, result.resolvedX to result.resolvedY)
  }

  @Test
  fun `a double tap by percent resolves the percent against the screen`() {
    manager.execute(IosDriverAction.TapRelative(50.0, 25.0, repeat = 2))

    assertEquals(1, multiTaps.size)
    assertEquals(2, multiTaps.single().third)
    assertEquals(emptyList(), singleTaps)
  }

  @Test
  fun `a plain tap stays a single tap`() {
    manager.execute(IosDriverAction.Tap(10, 20))

    assertEquals(listOf(10 to 20), singleTaps)
    assertEquals(emptyList(), multiTaps)
  }
}
