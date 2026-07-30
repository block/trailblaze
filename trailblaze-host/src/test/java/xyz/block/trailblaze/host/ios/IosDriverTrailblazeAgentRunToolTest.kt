package xyz.block.trailblaze.host.ios

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins [IosDriverTrailblazeAgent.runTool]'s handling of [OtherTrailblazeTool] placeholders —
 * the unknown-at-decode-time shape callers without their own repo (e.g. the daemon's
 * `BridgeUiActionExecutor` no-repo fallback) forward for the device agent to resolve.
 *
 * Regression coverage for the iOS CLI smoke `verify` failure where a repo-resolvable
 * assertion tool reached the AXe one-shot dispatch as a placeholder and failed with
 * "Tool OtherTrailblazeTool is not a known TrailblazeTool shape" instead of resolving.
 */
class IosDriverTrailblazeAgentRunToolTest {

  @BeforeTest
  fun clearRecordedExecutions() {
    RecordingEchoTool.executedValues.clear()
  }

  private class FakeIosDeviceManager : IosDeviceManager {
    val executedActions = mutableListOf<IosDriverAction>()

    override fun getScreenState(): ScreenState = error("not needed by these tests")

    override fun execute(action: IosDriverAction): IosDeviceManager.ExecutionResult {
      executedActions.add(action)
      return IosDeviceManager.ExecutionResult()
    }
  }

  private fun buildAgent(
    deviceManager: FakeIosDeviceManager,
    toolRepo: TrailblazeToolRepo?,
  ): IosDriverTrailblazeAgent = IosDriverTrailblazeAgent(
    deviceManager = deviceManager,
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = { testDeviceInfo },
    sessionProvider = { TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now()) },
    trailblazeToolRepo = toolRepo,
  )

  private fun buildContext(agent: IosDriverTrailblazeAgent): TrailblazeToolExecutionContext =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = testDeviceInfo,
      sessionProvider = { TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now()) },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      maestroTrailblazeAgent = agent,
    )

  @Test
  fun `resolves an OtherTrailblazeTool placeholder through the session repo before dispatch`() {
    val deviceManager = FakeIosDeviceManager()
    val agent = buildAgent(
      deviceManager = deviceManager,
      toolRepo = TrailblazeToolRepo(
        TrailblazeToolSet.DynamicTrailblazeToolSet(
          name = "Test Tools",
          toolClasses = setOf(RecordingEchoTool::class),
        ),
      ),
    )

    val result = runBlocking {
      agent.runTool(
        tool = OtherTrailblazeTool(
          toolName = "recordingEcho",
          raw = buildJsonObject { put("value", "hello") },
        ),
        context = buildContext(agent),
      )
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(RecordingEchoTool.executedValues).contains("hello")
  }

  @Test
  fun `resolved verify assertion executes through the AXe driver pipeline`() {
    // The exact smoke-failure shape: a built-in verification tool (MapsToMaestroCommands)
    // arriving as a placeholder must resolve via the driver's built-in surface — the repo
    // the MCP bridge now wires — and land on the device manager as an AXe action.
    val deviceManager = FakeIosDeviceManager()
    val agent = buildAgent(
      deviceManager = deviceManager,
      toolRepo = TrailblazeToolRepo.withDynamicToolSets(
        customToolClasses = TrailblazeToolSet.NonLlmTrailblazeTools,
        driverType = TrailblazeDriverType.IOS_AXE,
      ),
    )

    val result = runBlocking {
      agent.runTool(
        tool = OtherTrailblazeTool(
          toolName = "assertVisibleWithText",
          raw = buildJsonObject { put("text", "Welcome") },
        ),
        context = buildContext(agent),
      )
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(deviceManager.executedActions).hasSize(1)
    assertThat(deviceManager.executedActions.single()).isInstanceOf(IosDriverAction.AssertVisible::class)
  }

  @Test
  fun `unresolvable OtherTrailblazeTool fails with an unknown-tool error naming the tool`() {
    val deviceManager = FakeIosDeviceManager()
    val agent = buildAgent(
      deviceManager = deviceManager,
      toolRepo = TrailblazeToolRepo(
        TrailblazeToolSet.DynamicTrailblazeToolSet(
          name = "Test Tools",
          toolClasses = setOf(RecordingEchoTool::class),
        ),
      ),
    )

    val exception = assertFailsWith<TrailblazeException> {
      runBlocking {
        agent.runTool(
          tool = OtherTrailblazeTool(toolName = "noSuchTool", raw = buildJsonObject {}),
          context = buildContext(agent),
        )
      }
    }

    assertThat(exception.message.orEmpty()).contains("Unknown tool 'noSuchTool'")
    // The pre-fix failure mode: the placeholder shape leaking into the error instead of
    // the tool name the caller actually asked for.
    assertThat(exception.message.orEmpty()).doesNotContain("not a known TrailblazeTool shape")
  }

  @Test
  fun `without a repo an OtherTrailblazeTool still fails with the unknown-tool error`() {
    val deviceManager = FakeIosDeviceManager()
    val agent = buildAgent(deviceManager = deviceManager, toolRepo = null)

    val exception = assertFailsWith<TrailblazeException> {
      runBlocking {
        agent.runTool(
          tool = OtherTrailblazeTool(toolName = "anyTool", raw = buildJsonObject {}),
          context = buildContext(agent),
        )
      }
    }

    assertThat(exception.message.orEmpty()).contains("Unknown tool 'anyTool'")
  }

  private companion object {
    val testDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-ios-sim",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
      ),
      trailblazeDriverType = TrailblazeDriverType.IOS_AXE,
      widthPixels = 390,
      heightPixels = 844,
    )
  }
}

/**
 * Top-level (not nested in the test class) so its `@Serializable` decode through the repo works —
 * same pattern as `FailingEchoTool` in `ToolDispatchMemoryBoundaryTest`.
 */
@Serializable
@TrailblazeToolClass("recordingEcho")
private data class RecordingEchoTool(val value: String) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    executedValues.add(value)
    return TrailblazeToolResult.Success()
  }

  companion object {
    val executedValues = mutableListOf<String>()
  }
}
