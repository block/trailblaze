package xyz.block.trailblaze.host.axe

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for pure helpers on [AxeDeviceManager]. The resolver-poll loops
 * (`executeTapOnElement` / `executeAssertVisible` / `executeAssertNotVisible`) depend on
 * `AxeCli` subprocess calls and need a stubbable seam to unit-test — tracked separately.
 */
class AxeDeviceManagerTest {

  // Typical portrait iPhone 16 Pro sim: 402x874 in points.
  private val width = 402
  private val height = 874

  @Test
  fun `swipe UP starts from center and ends 10% from the top`() {
    val coords = AxeDeviceManager.computeDirectionalSwipeCoords(AxeAction.Direction.UP, width, height)
    val (startX, startY, endX, endY) = coords
    assertEquals(width / 2, startX, "startX is center")
    assertEquals(height / 2, startY, "startY is center")
    assertEquals(width / 2, endX, "endX stays on center column")
    assertEquals((height * 0.1).toInt(), endY, "endY is 10% from top for an UP swipe")
  }

  @Test
  fun `swipe DOWN starts from center and ends 10% from the bottom`() {
    val coords = AxeDeviceManager.computeDirectionalSwipeCoords(AxeAction.Direction.DOWN, width, height)
    val (startX, startY, endX, endY) = coords
    assertEquals(width / 2, startX)
    assertEquals(height / 2, startY)
    assertEquals(width / 2, endX)
    assertEquals((height * 0.9).toInt(), endY, "endY is 90% down for a DOWN swipe")
  }

  @Test
  fun `swipe LEFT starts from 90% right and ends 10% from left`() {
    val coords = AxeDeviceManager.computeDirectionalSwipeCoords(AxeAction.Direction.LEFT, width, height)
    val (startX, startY, endX, endY) = coords
    assertEquals((width * 0.9).toInt(), startX, "startX is 90% across for a LEFT swipe")
    assertEquals(height / 2, startY)
    assertEquals((width * 0.1).toInt(), endX, "endX is 10% across")
    assertEquals(height / 2, endY)
  }

  @Test
  fun `swipe RIGHT starts from 10% left and ends 90% across`() {
    val coords = AxeDeviceManager.computeDirectionalSwipeCoords(AxeAction.Direction.RIGHT, width, height)
    val (startX, startY, endX, endY) = coords
    assertEquals((width * 0.1).toInt(), startX)
    assertEquals(height / 2, startY)
    assertEquals((width * 0.9).toInt(), endX)
    assertEquals(height / 2, endY)
  }

  @Test
  fun `swipe math handles small simulator dimensions without going negative`() {
    // Regression guard: at 100x100 both 10% and 90% endpoints must stay in bounds.
    val all = AxeAction.Direction.entries.map { dir ->
      dir to AxeDeviceManager.computeDirectionalSwipeCoords(dir, 100, 100)
    }
    for ((dir, coords) in all) {
      for (v in coords) {
        assert(v in 0..100) { "$dir produced out-of-bounds coord: $v" }
      }
    }
  }

  // IntArray destructuring — mirrors the private extensions in AxeDeviceManager so the
  // test reads naturally without reaching into internals.
  private operator fun IntArray.component1() = this[0]
  private operator fun IntArray.component2() = this[1]
  private operator fun IntArray.component3() = this[2]
  private operator fun IntArray.component4() = this[3]
}
