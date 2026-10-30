package xyz.block.trailblaze.maestro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.Clock
import maestro.DeviceInfo
import maestro.Driver
import maestro.Point
import maestro.TapRepeat
import maestro.TreeNode
import maestro.device.Platform
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver

/**
 * A double-tap is two native taps a platform-defined interval apart; a screen capture between them
 * can stretch that gap until the OS reads two single taps.
 */
class LoggingDriverRepeatedTapTest {
  @Test
  fun `a batch with a double-tap taps both times without capturing the screen between them`() {
    val fixture = Fixture()
    val batch = fixture.driver.forMaestroBatch(listOf(tapCommand(repeat = TapRepeat(repeat = 2, delay = 50))))

    batch.tap(POINT)
    batch.tap(POINT)

    assertEquals(0, fixture.screenCaptures)
    assertEquals(listOf(POINT, POINT), fixture.delegateTaps)
    assertEquals(
      listOf<AgentDriverAction>(
        AgentDriverAction.TapPoint(POINT.x, POINT.y),
        AgentDriverAction.TapPoint(POINT.x, POINT.y),
      ),
      fixture.loggedActions(),
    )
    // Tap points are grid coordinates, so the log must carry the grid size, not the 3x pixel size.
    assertEquals(listOf(390 to 844, 390 to 844), fixture.loggedDimensions())
  }

  @Test
  fun `a batch with a single tap still captures the screen for the log`() {
    val fixture = Fixture()
    val batch = fixture.driver.forMaestroBatch(listOf(tapCommand(repeat = null)))

    assertFailsWith<IllegalStateException> { batch.tap(POINT) }

    assertEquals(1, fixture.screenCaptures)
  }

  private fun tapCommand(repeat: TapRepeat?) =
    MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "${POINT.x},${POINT.y}", repeat = repeat))

  private class Fixture {
    var screenCaptures = 0
    val delegateTaps = mutableListOf<Point>()
    private val logs = mutableListOf<TrailblazeLog>()
    private val delegate = object : Driver by ViewHierarchyOnlyDriver(
      rootTreeNode = TreeNode(),
      deviceInfo = DeviceInfo(
        platform = Platform.IOS,
        widthPixels = 1170,
        heightPixels = 2532,
        widthGrid = 390,
        heightGrid = 844,
      ),
    ) {
      override fun tap(point: Point) {
        delegateTaps += point
      }
    }
    val driver = LoggingDriver(
      delegate = delegate,
      screenStateProvider = {
        screenCaptures++
        error("screen captured")
      },
      trailblazeLogger = TrailblazeLogger(logEmitter = { logs += it }, screenStateLogger = { "" }),
      sessionProvider = TrailblazeSessionProvider { TrailblazeSession(SessionId("fixture"), Clock.System.now()) },
    )

    fun loggedActions() = logs.filterIsInstance<TrailblazeLog.AgentDriverLog>().map { it.action }

    fun loggedDimensions() =
      logs.filterIsInstance<TrailblazeLog.AgentDriverLog>().map { it.deviceWidth to it.deviceHeight }
  }

  private companion object {
    val POINT = Point(100, 65)
  }
}
