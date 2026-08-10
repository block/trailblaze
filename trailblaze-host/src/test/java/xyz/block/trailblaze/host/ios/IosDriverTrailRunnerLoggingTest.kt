package xyz.block.trailblaze.host.ios

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/**
 * Pins the per-action screenshot logging contract of [IosDriverTrailRunner.runActions]:
 * with a logger + session provider wired, every executed action — successful or failed —
 * emits a [TrailblazeLog.AgentDriverLog] with a persisted `screenshotFile`, so IOS_AXE
 * sessions render step frames in the run report like IOS_HOST does. Without them, the
 * runner degrades to plain inline execution and never touches the device's screen state.
 */
class IosDriverTrailRunnerLoggingTest {

  // Lazy like AxeScreenState: the screenshot is only materialized on first read, and blows up
  // if that first read happens after any later action executed. This pins the runner's central
  // guarantee — lazies are forced on the calling thread BEFORE the action dispatches; without
  // the forcing, the async log job would evaluate them post-action and this fake fails the run.
  private class FakeScreenState(
    private val isStale: () -> Boolean = { false },
  ) : ScreenState {
    override val screenshotBytes: ByteArray by lazy {
      check(!isStale()) {
        "screenshotBytes first evaluated after a later action executed — pre-action forcing is broken"
      }
      byteArrayOf(1, 2, 3, 4, 5)
    }
    override val deviceWidth: Int = 390
    override val deviceHeight: Int = 844
    // Mirrors AxeScreenState, which throws (rather than returning null) when describe-ui
    // produced no usable tree — the logger must degrade to a hierarchy-less log.
    override val viewHierarchy: ViewHierarchyTreeNode get() = error("no hierarchy in this fake")
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  private class FakeIosDeviceManager(
    private val screenStateProvider: (() -> ScreenState)? = null,
    private val executionResult: IosDeviceManager.ExecutionResult = IosDeviceManager.ExecutionResult(),
    private val failWith: Exception? = null,
  ) : IosDeviceManager {
    val executedActions = mutableListOf<IosDriverAction>()
    var screenStateCaptures = 0

    override fun getScreenState(): ScreenState {
      screenStateCaptures++
      screenStateProvider?.let { return it() }
      val executedAtCapture = executedActions.size
      return FakeScreenState(isStale = { executedActions.size != executedAtCapture })
    }

    override fun execute(action: IosDriverAction): IosDeviceManager.ExecutionResult {
      executedActions.add(action)
      failWith?.let { throw it }
      return executionResult
    }
  }

  private val emittedLogs = CopyOnWriteArrayList<TrailblazeLog>()
  private val persistedScreenshotFiles = CopyOnWriteArrayList<String>()

  private val capturingLogger = TrailblazeLogger(
    logEmitter = { log -> emittedLogs.add(log) },
    screenStateLogger = { screenStateLog ->
      persistedScreenshotFiles.add(screenStateLog.fileName)
      screenStateLog.fileName
    },
  )

  private val sessionProvider = {
    TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now())
  }

  private fun axeSelector(label: String): TrailblazeNodeSelector =
    TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosAxe(labelRegex = label))

  private fun driverLogs(): List<TrailblazeLog.AgentDriverLog> {
    IosDriverTrailRunner.flushLogs()
    return emittedLogs.filterIsInstance<TrailblazeLog.AgentDriverLog>()
  }

  @Test
  fun `each successful action emits an AgentDriverLog with a persisted screenshot`() {
    val deviceManager = FakeIosDeviceManager()

    val result = IosDriverTrailRunner.runActions(
      actions = listOf(IosDriverAction.Tap(x = 10, y = 20), IosDriverAction.InputText("hello")),
      traceId = null,
      deviceManager = deviceManager,
      trailblazeLogger = capturingLogger,
      sessionProvider = sessionProvider,
    )

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    val logs = driverLogs()
    assertThat(logs).hasSize(2)
    logs.forEach { log ->
      assertThat(log.screenshotFile).isNotNull()
      assertThat(log.viewHierarchy).isNull()
    }
    assertThat(logs[0].action).isEqualTo(AgentDriverAction.TapPoint(x = 10, y = 20))
    assertThat(logs[1].action).isEqualTo(AgentDriverAction.EnterText(text = "hello"))
    assertThat(persistedScreenshotFiles).hasSize(2)
  }

  @Test
  fun `element tap logs the resolved tap coordinates from the execution result`() {
    val deviceManager = FakeIosDeviceManager(
      executionResult = IosDeviceManager.ExecutionResult(resolvedX = 111, resolvedY = 222),
    )

    IosDriverTrailRunner.runActions(
      actions = listOf(
        IosDriverAction.TapOnElement(nodeSelector = axeSelector("Sign in")),
      ),
      traceId = null,
      deviceManager = deviceManager,
      trailblazeLogger = capturingLogger,
      sessionProvider = sessionProvider,
    )

    val action = driverLogs().single().action
    assertThat(action).isEqualTo(AgentDriverAction.TapPoint(x = 111, y = 222))
  }

  @Test
  fun `a failed action still logs the pre-action screen and short-circuits the batch`() {
    val deviceManager = FakeIosDeviceManager(failWith = RuntimeException("element not found"))

    val result = IosDriverTrailRunner.runActions(
      actions = listOf(
        IosDriverAction.AssertVisible(nodeSelector = axeSelector("Welcome")),
        IosDriverAction.Tap(x = 1, y = 2),
      ),
      traceId = null,
      deviceManager = deviceManager,
      trailblazeLogger = capturingLogger,
      sessionProvider = sessionProvider,
    )

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    // Second action never ran.
    assertThat(deviceManager.executedActions).hasSize(1)
    // The error path flushes inline, so the log is visible without an explicit flush.
    val log = emittedLogs.filterIsInstance<TrailblazeLog.AgentDriverLog>().single()
    assertThat(log.screenshotFile).isNotNull()
    val assertAction = log.action as AgentDriverAction.AssertCondition
    assertThat(assertAction.isVisible).isTrue()
    assertThat(assertAction.succeeded).isFalse()
  }

  @Test
  fun `a skipped optional action emits no driver log`() {
    // AxeDeviceManager models an optional skip (wait exhausted, no throw) as a normal return
    // with no resolved coordinates. Logging it would render a green passed assert card — or a
    // (0,0) tap marker — for something the driver never observed.
    val deviceManager = FakeIosDeviceManager()

    val result = IosDriverTrailRunner.runActions(
      actions = listOf(
        IosDriverAction.AssertNotVisible(nodeSelector = axeSelector("Spinner"), optional = true),
        IosDriverAction.TapOnElement(nodeSelector = axeSelector("Maybe"), optional = true),
        IosDriverAction.Tap(x = 1, y = 2),
      ),
      traceId = null,
      deviceManager = deviceManager,
      trailblazeLogger = capturingLogger,
      sessionProvider = sessionProvider,
    )

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(deviceManager.executedActions).hasSize(3)
    val logs = driverLogs()
    assertThat(logs).hasSize(1)
    assertThat(logs.single().action).isEqualTo(AgentDriverAction.TapPoint(x = 1, y = 2))
  }

  @Test
  fun `without a logger the runner never captures screen state`() {
    val deviceManager = FakeIosDeviceManager()

    val result = IosDriverTrailRunner.runActions(
      actions = listOf(IosDriverAction.Tap(x = 10, y = 20)),
      traceId = null,
      deviceManager = deviceManager,
    )

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(deviceManager.executedActions).hasSize(1)
    assertThat(deviceManager.screenStateCaptures).isEqualTo(0)
  }

  @Test
  fun `a screen capture failure is non-fatal and the action still executes`() {
    val deviceManager = FakeIosDeviceManager(
      screenStateProvider = { error("axe screenshot failed") },
    )

    val result = IosDriverTrailRunner.runActions(
      actions = listOf(IosDriverAction.Tap(x = 10, y = 20)),
      traceId = null,
      deviceManager = deviceManager,
      trailblazeLogger = capturingLogger,
      sessionProvider = sessionProvider,
    )

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(deviceManager.executedActions).hasSize(1)
    assertThat(driverLogs()).isEmpty()
  }
}
