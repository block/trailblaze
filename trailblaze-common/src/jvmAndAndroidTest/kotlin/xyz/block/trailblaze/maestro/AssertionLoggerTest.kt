package xyz.block.trailblaze.maestro

import kotlinx.datetime.Clock
import maestro.DeviceInfo
import maestro.Maestro
import maestro.TreeNode
import maestro.device.Platform
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.api.CaptureCoverage
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The report's `accessibility_truncation` signal rolls [CaptureCoverage] up from driver logs, so a
 * capture whose log drops it is invisible to that signal — including the assert's own capture, which
 * is exactly the one a spurious assertion failure needs explained.
 */
class AssertionLoggerTest {

  @Test
  fun `assertion log carries the capture coverage of the screen state it asserted against`() {
    val coverage = CaptureCoverage(
      contentNodes = 12,
      zeroBoundsContentNodes = 9,
      horizontalCoverage = 0.2,
      verticalCoverage = 0.95,
      looksTruncated = true,
      reason = "content jammed against the right edge",
    )

    assertEquals(coverage, logAssertionAgainst(FakeScreenState(captureCoverage = coverage)).captureCoverage)
  }

  @Test
  fun `assertion log carries no capture coverage when the driver produced none`() {
    assertNull(logAssertionAgainst(FakeScreenState(captureCoverage = null)).captureCoverage)
  }

  private fun logAssertionAgainst(screenState: ScreenState): TrailblazeLog.AgentDriverLog {
    val captured = mutableListOf<TrailblazeLog>()
    AssertionLogger(
      maestro = viewHierarchyOnlyMaestro(),
      screenStateProvider = { screenState },
      trailblazeLogger = TrailblazeLogger(
        logEmitter = LogEmitter(captured::add),
        screenStateLogger = ScreenStateLogger { it.fileName },
      ),
      sessionProvider = {
        TrailblazeSession(sessionId = SessionId("test_session"), startTime = Clock.System.now())
      },
    ).logSuccessfulAssertionCommand(
      MaestroCommand(
        AssertConditionCommand(condition = Condition(visible = ElementSelector(textRegex = "Welcome"))),
      ),
    )
    return captured.filterIsInstance<TrailblazeLog.AgentDriverLog>().single()
  }

  private fun viewHierarchyOnlyMaestro(): Maestro = Maestro(
    driver = ViewHierarchyOnlyDriver(
      rootTreeNode = TreeNode(),
      deviceInfo = DeviceInfo(
        platform = Platform.ANDROID,
        widthPixels = 1080,
        heightPixels = 2400,
        widthGrid = 1080,
        heightGrid = 2400,
      ),
    ),
  )

  private class FakeScreenState(
    override val captureCoverage: CaptureCoverage?,
  ) : ScreenState {
    // PNG magic number: TrailblazeLogger.logScreenState rejects short/absent bytes, which would
    // leave the log without a screenshot file.
    override val screenshotBytes: ByteArray = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 2400
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "FrameLayout",
    )
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }
}
