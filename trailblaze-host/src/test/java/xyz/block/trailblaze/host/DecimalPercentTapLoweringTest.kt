package xyz.block.trailblaze.host

import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Driver
import maestro.Point
import maestro.TreeNode
import maestro.device.Platform
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TapOnPointV2Command
import org.junit.Test
import xyz.block.trailblaze.maestro.OrchestraRunner
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Maestro driver can only parse integer percents, so decimal ones become absolute points. */
class DecimalPercentTapLoweringTest {

  private val grid = 1080 to 2400

  private fun tap(point: String) = MaestroCommand(TapOnPointV2Command(point = point))

  @Test
  fun `a decimal percent tap becomes the point Maestro would have computed`() {
    val lowered = DecimalPercentTapLowering.lower(
      MaestroCommand(TapOnPointV2Command(point = "50.5%,26%", longPress = true)),
    ) { grid }

    // 1080 * 50.5% = 545.4, 2400 * 26% = 624.
    assertEquals(TapOnPointV2Command(point = "545,624", longPress = true), lowered.tapOnPointV2Command)
  }

  @Test
  fun `whole-number percent, absolute and non-finite taps pass through without reading the screen`() {
    listOf(tap("50%,26%"), tap("100,65"), tap("NaN%,50%"), tap("Infinity%,50%")).forEach { command ->
      assertSame(
        command,
        DecimalPercentTapLowering.lower(command) { error("screen size read for ${command.tapOnPointV2Command}") },
      )
    }
  }

  @Test
  fun `the host executor runs a decimal tap through Maestro at the current screen size`() {
    val device = FakeDevice()
    val executor = HostOrchestraExecutor(device.driver, NO_CALLBACKS)

    assertTrue(runBlocking { executor.execute(listOf(tap("50.5%,26%"))) })
    // Rotated between commands: the next tap must use the landscape size, not the portrait one.
    device.grid = 844 to 390
    assertTrue(runBlocking { executor.execute(listOf(tap("50.5%,26%"))) })

    // 390 * 50.5% = 196.9, 844 * 26% = 219.4; then 844 * 50.5% = 426.2, 390 * 26% = 101.4.
    assertEquals(listOf(Point(196, 219), Point(426, 101)), device.taps)
  }

  @Test
  fun `the host executor still hands whole-number percents to Maestro and rejects NaN`() {
    val device = FakeDevice()
    val executor = HostOrchestraExecutor(device.driver, NO_CALLBACKS)

    assertTrue(runBlocking { executor.execute(listOf(tap("50%,26%"))) })
    assertFalse(runBlocking { executor.execute(listOf(tap("NaN%,50%"))) })

    // Maestro's own percent math: 390 * 50 / 100 = 195, 844 * 26 / 100 = 219.
    assertEquals(listOf(Point(195, 219)), device.taps)
  }

  private class FakeDevice {
    var grid = 390 to 844
    val taps = mutableListOf<Point>()
    val driver: Driver = object : Driver by ViewHierarchyOnlyDriver(TreeNode(), deviceInfo(390, 844)) {
      override fun deviceInfo(): DeviceInfo = deviceInfo(grid.first, grid.second)

      override fun tap(point: Point) {
        taps += point
      }
    }
  }

  private companion object {
    val NO_CALLBACKS = OrchestraRunner.OrchestraCallbacks(
      onCommandComplete = { _, _ -> },
      onCommandFailed = { _, _, _ -> },
    )

    fun deviceInfo(width: Int, height: Int) = DeviceInfo(
      platform = Platform.IOS,
      widthPixels = width * 3,
      heightPixels = height * 3,
      widthGrid = width,
      heightGrid = height,
    )
  }
}
