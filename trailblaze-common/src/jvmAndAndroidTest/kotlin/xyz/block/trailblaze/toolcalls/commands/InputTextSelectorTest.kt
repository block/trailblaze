package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.TapOnElementCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.TapRouteOverride
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * `inputText` with a `selector` types into that field — one step in place of a
 * `tapOnElementBySelector` + `inputText` pair. Every driver taps the field first (natively or via the
 * Maestro lowering), so a field that reacts to a click still gets one. A driver that can focus the
 * field directly then types there; the rest type into the field the tap focused.
 */
class InputTextSelectorTest {

  private val cardNumber =
    TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(hintTextRegex = "Card number"))

  /** A driver-native focus-and-type call, as the agent received it. */
  private data class FocusAndType(val selector: TrailblazeNodeSelector, val text: String, val hideKeyboardAfter: Boolean)

  /**
   * Records every device action, in order, so a test can assert the tap came before the typing.
   *
   * [focusAndTypeResult] non-null means the driver can focus a field directly; [usesAccessibilityDriver]
   * means it resolves selector taps natively rather than through the Maestro lowering.
   */
  private class RecordingAgent(
    driverType: TrailblazeDriverType,
    override val usesAccessibilityDriver: Boolean,
    private val tapResult: TrailblazeToolResult = TrailblazeToolResult.Success(),
    private val focusAndTypeResult: TrailblazeToolResult? = null,
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = driverType.platform,
        ),
        trailblazeDriverType = driverType,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
  ) {
    val actions: MutableList<Any> = mutableListOf()

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      actions.addAll(commands)
      return TrailblazeToolResult.Success()
    }

    override suspend fun executeNodeSelectorTap(
      nodeSelector: TrailblazeNodeSelector,
      longPress: Boolean,
      tapRoute: TapRouteOverride?,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      if (!usesAccessibilityDriver) return null
      actions.add(nodeSelector)
      return tapResult
    }

    override suspend fun executeNodeSelectorInputText(
      nodeSelector: TrailblazeNodeSelector,
      text: String,
      hideKeyboardAfter: Boolean,
      clearFirst: Boolean,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      val result = focusAndTypeResult ?: return null
      actions.add(FocusAndType(nodeSelector, text, hideKeyboardAfter))
      return result
    }
  }

  private fun contextFor(agent: RecordingAgent) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
    sessionProvider = agent.sessionProvider,
    screenStateProvider = null,
    trailblazeLogger = agent.trailblazeLogger,
    memory = AgentMemory(),
    maestroTrailblazeAgent = agent,
  )

  @Test
  fun `a driver that can focus the field taps it, then focuses and types there`() = runBlocking {
    val failure = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "field never took focus")
    val agent = RecordingAgent(
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      usesAccessibilityDriver = true,
      focusAndTypeResult = failure,
    )

    val result = InputTextTrailblazeTool(text = "4111", selector = cardNumber).execute(contextFor(agent))

    // The driver's own verdict is the step's: a field that never took focus fails here rather than
    // falling back to typing into whatever field has focus.
    assertThat(result).isEqualTo(failure)
    assertThat(agent.actions).containsExactly(cardNumber, FocusAndType(cardNumber, "4111", hideKeyboardAfter = true))
  }

  @Test
  fun `a native-tap driver without focus-and-type taps the field, then types into it`() = runBlocking {
    val agent = RecordingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, usesAccessibilityDriver = true)

    val result = InputTextTrailblazeTool(text = "4111", selector = cardNumber).execute(contextFor(agent))

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(agent.actions.map { it::class }).containsExactly(
      TrailblazeNodeSelector::class,
      InputTextCommand::class,
      HideKeyboardCommand::class,
    )
    assertThat(agent.actions.first()).isEqualTo(cardNumber)
    assertThat((agent.actions[1] as InputTextCommand).text).isEqualTo("4111")
  }

  @Test
  fun `a failed tap types nothing and returns the tap's failure`() = runBlocking {
    val failure = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "no element matched")
    val agent = RecordingAgent(
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      usesAccessibilityDriver = true,
      tapResult = failure,
      focusAndTypeResult = TrailblazeToolResult.Success(),
    )

    val result = InputTextTrailblazeTool(text = "4111", selector = cardNumber).execute(contextFor(agent))

    assertThat(result).isEqualTo(failure)
    assertThat(agent.actions).containsExactly(cardNumber)
  }

  @Test
  fun `a Maestro driver lowers the selector to a tap ahead of the typing`() = runBlocking {
    val agent = RecordingAgent(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, usesAccessibilityDriver = false)
    val emailField = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Email"))

    val result = InputTextTrailblazeTool(text = "a@b.co", selector = emailField, hideKeyboardAfter = false)
      .execute(contextFor(agent))

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(agent.actions.map { it::class }).containsExactly(
      TapOnElementCommand::class,
      InputTextCommand::class,
    )
    assertThat((agent.actions.first() as TapOnElementCommand).selector.textRegex).isEqualTo("Email")
  }
}
