package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A repeated tap must reach the multi-tap gesture (so a double-tap is one gesture, not two taps a
 * dispatch apart), a plain tap must stay a single tap, and a percent tap at the far edge must land
 * on the last valid pixel.
 */
class AccessibilityDeviceManagerTapTest {

  private val taps = mutableListOf<Pair<Int, Int>>()
  private val multiTaps = mutableListOf<Triple<Int, Int, Int>>()
  private var dispatched = true

  private val manager = AccessibilityDeviceManager(
    awaitSettle = {},
    forgetStableTree = {},
    dispatchTap = { x, y -> taps += x to y; dispatched },
    dispatchMultiTap = { x, y, count -> multiTaps += Triple(x, y, count); dispatched },
    screenDimensions = { 1080 to 2400 },
  )

  @Test
  fun `a double tap is dispatched as one multi-tap gesture`() {
    val result = manager.execute(AccessibilityAction.Tap(x = 100, y = 65, repeat = 2))

    assertEquals(listOf(Triple(100, 65, 2)), multiTaps)
    assertEquals(emptyList(), taps)
    assertEquals(100 to 65, result.resolvedX to result.resolvedY)
  }

  @Test
  fun `a plain tap stays a single tap`() {
    manager.execute(AccessibilityAction.Tap(x = 10, y = 20))

    assertEquals(listOf(10 to 20), taps)
    assertEquals(emptyList(), multiTaps)
  }

  @Test
  fun `a double tap by percent resolves against the screen and is one multi-tap`() {
    manager.execute(AccessibilityAction.TapRelative(percentX = 50.0, percentY = 26.0, repeat = 2))

    assertEquals(listOf(Triple(540, 624, 2)), multiTaps)
    assertEquals(emptyList(), taps)
  }

  @Test
  fun `a decimal percent tap resolves to the pixel the fraction lands on`() {
    manager.execute(AccessibilityAction.TapRelative(percentX = 50.5, percentY = 26.25))

    // 1080 * 50.5% = 545.4, 2400 * 26.25% = 630.
    assertEquals(listOf(545 to 630), taps)
  }

  @Test
  fun `a percent tap at 100 percent lands on the last pixel, not past the screen`() {
    manager.execute(AccessibilityAction.TapRelative(percentX = 100.0, percentY = 100.0))

    assertEquals(listOf(1079 to 2399), taps)
  }

  @Test
  fun `a multi-tap the system cancelled fails instead of reporting success`() {
    dispatched = false

    assertFailsWith<IllegalStateException> {
      manager.execute(AccessibilityAction.Tap(x = 1, y = 2, repeat = 2))
    }
  }
}
