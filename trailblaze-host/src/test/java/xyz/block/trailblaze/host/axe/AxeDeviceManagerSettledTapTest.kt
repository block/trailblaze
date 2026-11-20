package xyz.block.trailblaze.host.axe

import org.junit.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.host.ios.IosDriverAction

/**
 * A pushed screen's rows reach the accessibility tree before iOS accepts touches on them, so a
 * selector tap fired on the first capture that matched was silently dropped. These pin the rule
 * that replaced it: the tap waits until two captures in a row agree.
 */
class AxeDeviceManagerSettledTapTest {

  private val ok = AxeCli.Result(exitCode = 0, stdout = "", stderr = "")
  private val about = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = "^About$"))

  /** Serves the [screen] for each read (1-based), noting which read each tap followed. */
  private inner class Device(private val screen: (read: Int) -> String) {
    constructor(vararg screens: String) : this({ screens[minOf(it, screens.size) - 1] })

    var reads = 0
      private set
    val tapsAfterRead = mutableListOf<Int>()

    val manager = AxeDeviceManager(
      udid = "test-udid",
      deviceWidth = 402,
      deviceHeight = 874,
      describeUi = {
        reads++
        AxeCli.Result(exitCode = 0, stdout = screen(reads), stderr = "")
      },
      tapXy = { _, _ -> tapsAfterRead += reads; ok },
    )
  }

  @Test
  fun `a tap waits out a push that already shows its target`() {
    // Mid-push: the new page's About row is in the tree while the old page is still sliding out.
    val device = Device(
      screen(oldPageX = -100),
      screen(oldPageX = -121),
      screen(oldPageX = null),
    )

    device.manager.execute(IosDriverAction.TapOnElement(about, timeoutMs = 5_000))

    // Reads 3 and 4 are the first two that agree.
    assertEquals(listOf(4), device.tapsAfterRead)
  }

  @Test
  fun `a still screen is tapped on its second read`() {
    val device = Device(screen(oldPageX = null))

    device.manager.execute(IosDriverAction.TapOnElement(about, timeoutMs = 5_000))

    assertEquals(listOf(2), device.tapsAfterRead)
  }

  @Test
  fun `a screen that never stops changing is still tapped`() {
    val device = Device { screen(oldPageX = null, clock = "$it") }

    val result = device.manager.execute(IosDriverAction.TapOnElement(about, timeoutMs = 10_000))

    assertEquals(1, device.tapsAfterRead.size)
    assertEquals(201 to 389, result.resolvedX to result.resolvedY)
  }

  @Test
  fun `a short timeout taps the match rather than failing while waiting for the screen to settle`() {
    val device = Device { screen(oldPageX = null, clock = "$it") }

    device.manager.execute(IosDriverAction.TapOnElement(about, timeoutMs = 300))

    assertEquals(1, device.tapsAfterRead.size)
  }

  @Test
  fun `a match followed by failing captures is tapped when time runs out, not reported missing`() {
    val device = Device { if (it == 1) screen(oldPageX = -100) else "not json" }

    val result = device.manager.execute(IosDriverAction.TapOnElement(about, timeoutMs = 300))

    assertEquals(1, device.tapsAfterRead.size)
    assertEquals(201 to 389, result.resolvedX to result.resolvedY)
  }

  private fun screen(oldPageX: Int?, clock: String = "9:41") = """
    [
      {
        "role": "AXApplication",
        "type": "Application",
        "AXLabel": "Settings",
        "frame": {"x": 0, "y": 0, "width": 402, "height": 874},
        "children": [
          ${oldPageX?.let { """{"role": "AXButton", "type": "Button", "AXLabel": "General", "frame": {"x": $it, "y": 322, "width": 362, "height": 44}},""" } ?: ""}
          {"role": "AXStaticText", "type": "StaticText", "AXLabel": "$clock", "frame": {"x": 20, "y": 10, "width": 60, "height": 20}},
          {"role": "AXGroup", "type": "Group", "AXLabel": "About", "frame": {"x": 16, "y": 367, "width": 370, "height": 44}}
        ]
      }
    ]
  """.trimIndent()
}
