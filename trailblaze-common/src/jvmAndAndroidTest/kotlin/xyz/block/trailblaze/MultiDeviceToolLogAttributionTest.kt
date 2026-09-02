package xyz.block.trailblaze

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * A multi-device session's tool logs must say WHICH named device ran each action, and must keep
 * saying it correctly after `switchDevice` hands the session over.
 *
 * Before this, a reader had only the screenshot's dimensions to go on — no help at all when both
 * bound devices share a resolution, which the X2 pair very nearly does.
 */
class MultiDeviceToolLogAttributionTest {

  @Test
  fun theActiveBindingNameLandsOnTheToolLog() {
    val agentContext = CapturingAgentContext()
    val bindings = bindings("seller", "buyer")

    agentContext.logAWait(bindings)

    assertEquals("seller", agentContext.loggedDeviceNames().single())
  }

  @Test
  fun attributionFollowsASwitchRatherThanNamingTheStartDevice() {
    val agentContext = CapturingAgentContext()
    val bindings = bindings("seller", "buyer")

    agentContext.logAWait(bindings)
    bindings.switchTo("buyer")
    agentContext.logAWait(bindings)

    assertEquals(
      listOf("seller", "buyer"),
      agentContext.loggedDeviceNames(),
      "the bindings instance is shared and mutated by switchDevice — reading it once at " +
        "construction would attribute the whole second half of the run to the wrong screen",
    )
  }

  @Test
  fun aSingleDeviceSessionAttributesNothing() {
    val agentContext = CapturingAgentContext()

    agentContext.logAWait(deviceBindings = null)

    assertNull(
      agentContext.loggedToolLogs().single().deviceName,
      "null means not-applicable — a reader that saw a name here would report a " +
        "single-device run as multi-device",
    )
  }

  @Test
  fun anAgentsOwnBindingsAttributeAContextLessDispatch() {
    // Compose RPC and the on-device RPC catch-all log without an execution context. They still
    // reach the shared bindings through the agent, so the attribution survives that path.
    val agentContext = CapturingAgentContext(activeDeviceName = "buyer")

    agentContext.logToolExecution(
      tool = StubActionTool(),
      timeBeforeExecution = Clock.System.now(),
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      result = TrailblazeToolResult.Success(),
    )

    assertEquals("buyer", agentContext.loggedDeviceNames().single())
  }

  private fun CapturingAgentContext.logAWait(deviceBindings: SessionDeviceBindings?) {
    logToolExecution(
      tool = StubActionTool(),
      timeBeforeExecution = Clock.System.now(),
      context = executionContext(deviceBindings),
      result = TrailblazeToolResult.Success(),
    )
  }

  private fun CapturingAgentContext.loggedToolLogs(): List<TrailblazeLog.TrailblazeToolLog> =
    emitted.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()

  private fun CapturingAgentContext.loggedDeviceNames(): List<String?> =
    loggedToolLogs().map { it.deviceName }

  private class CapturingAgentContext(
    override val activeDeviceName: String? = null,
  ) : TrailblazeAgentContext {
    val emitted = mutableListOf<TrailblazeLog>()
    override val trailblazeLogger = TrailblazeLogger(
      logEmitter = LogEmitter { log -> emitted.add(log) },
      screenStateLogger = ScreenStateLogger { "" },
    )
    override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = { deviceInfo("seller") }
    override val sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    }
    override val memory = AgentMemory()
  }

  private fun executionContext(deviceBindings: SessionDeviceBindings?) =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
      trailblazeDeviceInfo = deviceInfo("seller"),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      deviceBindings = deviceBindings,
    )

  private fun bindings(vararg names: String) = SessionDeviceBindings(
    devices = names.associateWith {
      val info = deviceInfo(it)
      SessionDeviceBindings.BoundDevice(
        trailblazeDeviceId = info.trailblazeDeviceId,
        trailblazeDeviceInfo = info,
        description = null,
        targetId = null,
      )
    },
  )

  @Serializable
  @TrailblazeToolClass("stub_action")
  private class StubActionTool : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  private companion object {
    fun deviceInfo(instanceId: String) = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = instanceId,
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 1920,
    )
  }
}
