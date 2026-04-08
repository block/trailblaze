package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Tests that assertion tools correctly dispatch to the agent's nodeSelector-based
 * assertion methods, and fall back to Maestro commands when the agent returns null.
 */
class AssertToolNodeSelectorTest {

  // region AssertNotVisibleWithTextTrailblazeTool

  @Test
  fun `AssertNotVisible dispatches nodeSelector with text and id to agent`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent)

    val tool = AssertNotVisibleWithTextTrailblazeTool(
      text = "Loading",
      id = "com\\.example:id/spinner",
      enabled = true,
      selected = false,
      index = 0,
    )

    tool.execute(context)

    val captured = agent.capturedAssertNotVisible
    assertNotNull(captured, "Agent.executeNodeSelectorAssertNotVisible should have been called")

    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(captured.driverMatch)
    assertEquals("Loading", match.textRegex)
    assertEquals("com\\.example:id/spinner", match.resourceIdRegex)
    assertEquals(true, match.isEnabled)
    assertEquals(false, match.isSelected)
    assertNull(captured.index, "index=0 should map to null (default, no filtering)")
  }

  @Test
  fun `AssertNotVisible passes non-zero index to nodeSelector`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent)

    val tool = AssertNotVisibleWithTextTrailblazeTool(
      text = "Item",
      index = 3,
    )

    tool.execute(context)

    val captured = agent.capturedAssertNotVisible
    assertNotNull(captured)
    assertEquals(3, captured.index)
  }

  @Test
  fun `AssertNotVisible interpolates memory variables in text`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent)
    context.memory.remember("amount", "$5.00")

    val tool = AssertNotVisibleWithTextTrailblazeTool(
      text = "\${amount}",
    )

    tool.execute(context)

    val captured = agent.capturedAssertNotVisible
    assertNotNull(captured)
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(captured.driverMatch)
    assertEquals("\$5.00", match.textRegex)
  }

  @Test
  fun `AssertNotVisible falls back to Maestro when agent returns null`() = runBlocking {
    val agent = CapturingAgent(assertNotVisibleResult = null)
    val context = createContext(agent)

    val tool = AssertNotVisibleWithTextTrailblazeTool(text = "Error")

    val result = tool.execute(context)

    // The Maestro fallback path runs executeMaestroCommands which returns Success() in our fake
    assertTrue(result.isSuccess())
    assertTrue(agent.maestroCommandsExecuted, "Should have fallen back to Maestro path")
  }

  // endregion

  // region AssertVisibleBySelectorTrailblazeTool

  @Test
  fun `AssertVisible with nodeSelector dispatches to agent`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent)
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
    )

    val tool = AssertVisibleBySelectorTrailblazeTool(
      selector = xyz.block.trailblaze.api.TrailblazeElementSelector(textRegex = "Submit"),
      nodeSelector = nodeSelector,
    )

    tool.execute(context)

    val captured = agent.capturedAssertVisible
    assertNotNull(captured, "Agent.executeNodeSelectorAssertVisible should have been called")
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(captured.driverMatch)
    assertEquals("Submit", match.textRegex)
  }

  @Test
  fun `AssertVisible without nodeSelector falls back to Maestro`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent)

    val tool = AssertVisibleBySelectorTrailblazeTool(
      selector = xyz.block.trailblaze.api.TrailblazeElementSelector(textRegex = "Submit"),
      nodeSelector = null,
    )

    tool.execute(context)

    assertNull(agent.capturedAssertVisible, "Should not call agent assertion path")
    assertTrue(agent.maestroCommandsExecuted, "Should have fallen back to Maestro path")
  }

  @Test
  fun `AssertVisible falls back to Maestro when agent returns null`() = runBlocking {
    val agent = CapturingAgent(assertVisibleResult = null)
    val context = createContext(agent)
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
    )

    val tool = AssertVisibleBySelectorTrailblazeTool(
      selector = xyz.block.trailblaze.api.TrailblazeElementSelector(textRegex = "Submit"),
      nodeSelector = nodeSelector,
    )

    tool.execute(context)

    assertNotNull(agent.capturedAssertVisible, "Agent assertion should have been tried")
    assertTrue(agent.maestroCommandsExecuted, "Should have fallen back to Maestro after null")
  }

  // endregion

  // region Helpers

  private fun TrailblazeToolResult.isSuccess() =
    this is TrailblazeToolResult.Success

  private fun createContext(agent: CapturingAgent): TrailblazeToolExecutionContext {
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
      maestroTrailblazeAgent = agent,
      nodeSelectorMode = NodeSelectorMode.PREFER_NODE_SELECTOR,
    )
  }

  /**
   * A [MaestroTrailblazeAgent] that captures nodeSelector assertion calls for verification
   * and tracks whether the Maestro fallback path was hit.
   */
  private class CapturingAgent(
    private val assertVisibleResult: TrailblazeToolResult? = TrailblazeToolResult.Success(),
    private val assertNotVisibleResult: TrailblazeToolResult? = TrailblazeToolResult.Success(),
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-instance",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
  ) {
    var capturedAssertVisible: TrailblazeNodeSelector? = null
      private set

    var capturedAssertNotVisible: TrailblazeNodeSelector? = null
      private set

    var maestroCommandsExecuted: Boolean = false
      private set

    override suspend fun executeNodeSelectorAssertVisible(
      nodeSelector: TrailblazeNodeSelector,
      timeoutMs: Long,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      capturedAssertVisible = nodeSelector
      return assertVisibleResult
    }

    override suspend fun executeNodeSelectorAssertNotVisible(
      nodeSelector: TrailblazeNodeSelector,
      timeoutMs: Long,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      capturedAssertNotVisible = nodeSelector
      return assertNotVisibleResult
    }

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      maestroCommandsExecuted = true
      return TrailblazeToolResult.Success()
    }
  }

  // endregion
}
