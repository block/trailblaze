package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.AssertConditionCommand
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
    // The text arg is dispatched through the lenient transform (case-insensitive, literal
    // reading honored) — assert the dispatched pattern's behavior, not its string shape.
    val dispatched = assertNotNull(match.textRegex)
    assertTrue(Regex(dispatched).matches("Loading"))
    assertTrue(Regex(dispatched).matches("LOADING"))
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
    // Interpolation ran ("${amount}" -> "$5.00") before the lenient transform: the dispatched
    // pattern matches the interpolated value via its literal reading.
    val dispatched = assertNotNull(match.textRegex)
    assertTrue(Regex(dispatched).matches("\$5.00"))
    assertTrue(!Regex(dispatched).matches("\$4.00"))
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
      nodeSelector = nodeSelector,
    )

    tool.execute(context)

    val captured = agent.capturedAssertVisible
    assertNotNull(captured, "Agent.executeNodeSelectorAssertVisible should have been called")
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(captured.driverMatch)
    assertEquals("Submit", match.textRegex)
  }

  @Test
  fun `AssertVisible in FORCE_LEGACY mode ignores nodeSelector and falls back to Maestro`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent, nodeSelectorMode = NodeSelectorMode.FORCE_LEGACY)
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
    )

    val tool = AssertVisibleBySelectorTrailblazeTool(nodeSelector = nodeSelector)

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
      nodeSelector = nodeSelector,
    )

    tool.execute(context)

    assertNotNull(agent.capturedAssertVisible, "Agent assertion should have been tried")
    assertTrue(agent.maestroCommandsExecuted, "Should have fallen back to Maestro after null")
  }

  // endregion

  // region AssertNotVisibleBySelectorTrailblazeTool

  @Test
  fun `AssertNotVisibleBySelector dispatches nodeSelector (with spatial scoping) to agent`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent)
    // Mirrors a spatially-scoped "X not visible BELOW Y" assertion — the structural `below:`
    // scope is exactly what the text-only AssertNotVisibleWithText tool can't express, so
    // verify it survives dispatch to the agent.
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Email Marketing"),
      below = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(textRegex = "Reach more customers"),
      ),
    )

    val tool = AssertNotVisibleBySelectorTrailblazeTool(nodeSelector = nodeSelector)
    tool.execute(context)

    val captured = agent.capturedAssertNotVisible
    assertNotNull(captured, "Agent.executeNodeSelectorAssertNotVisible should have been called")
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(captured.driverMatch)
    assertEquals("Email Marketing", match.textRegex)
    assertEquals(
      "Reach more customers",
      captured.below?.androidAccessibility?.textRegex,
      "the below: spatial scope must be preserved through dispatch",
    )
  }

  @Test
  fun `AssertNotVisibleBySelector in FORCE_LEGACY mode ignores nodeSelector and falls back to Maestro`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent, nodeSelectorMode = NodeSelectorMode.FORCE_LEGACY)
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Email Marketing"),
    )

    val tool = AssertNotVisibleBySelectorTrailblazeTool(nodeSelector = nodeSelector)
    tool.execute(context)

    assertNull(agent.capturedAssertNotVisible, "Should not call agent assertion path")
    assertTrue(agent.maestroCommandsExecuted, "Should have fallen back to Maestro path")
  }

  @Test
  fun `AssertNotVisibleBySelector falls back to Maestro when agent returns null`() = runBlocking {
    val agent = CapturingAgent(assertNotVisibleResult = null)
    val context = createContext(agent)
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Email Marketing"),
    )

    val tool = AssertNotVisibleBySelectorTrailblazeTool(nodeSelector = nodeSelector)
    tool.execute(context)

    assertNotNull(agent.capturedAssertNotVisible, "Agent assertion should have been tried")
    assertTrue(agent.maestroCommandsExecuted, "Should have fallen back to Maestro after null")
  }

  @Test
  fun `AssertNotVisibleBySelector Maestro path asserts notVisible`() = runBlocking {
    val agent = CapturingAgent()
    val context = createContext(agent)
    val nodeSelector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Email Marketing"),
    )
    val tool = AssertNotVisibleBySelectorTrailblazeTool(nodeSelector = nodeSelector)

    val command = assertIs<AssertConditionCommand>(tool.toMaestroCommands(context.memory).single())
    assertNotNull(command.condition.notVisible, "Maestro path must assert notVisible")
    assertNull(command.condition.visible, "Maestro path must NOT assert visible")
  }

  // endregion

  // region allDriverMatches (drives the AccessibilityTrailblazeAgent not-visible driver guard)

  @Test
  fun `allDriverMatches collects the top-level driver match`() {
    val sel = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "X"))
    val matches = sel.allDriverMatches()
    assertEquals(1, matches.size)
    assertIs<DriverNodeMatch.AndroidAccessibility>(matches.single())
    assertTrue(matches.none { it !is DriverNodeMatch.AndroidAccessibility }, "all accessibility → guard must NOT fire")
  }

  @Test
  fun `allDriverMatches flags a non-accessibility top-level match`() {
    val sel = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidMaestro(textRegex = "X"))
    assertTrue(
      sel.allDriverMatches().any { it !is DriverNodeMatch.AndroidAccessibility },
      "androidMaestro top-level → guard must fire",
    )
  }

  @Test
  fun `allDriverMatches traverses nested hierarchy branches (the top-level-only blind spot)`() {
    // Top-level is accessibility, but a non-accessibility branch is nested under containsChild.
    // A top-level-only check would miss this; the full traversal must catch it.
    val sel = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Parent"),
      containsChild = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidMaestro(textRegex = "Child"),
      ),
    )
    val matches = sel.allDriverMatches()
    assertEquals(2, matches.size, "should collect both the top-level and the nested driver match")
    assertTrue(
      matches.any { it !is DriverNodeMatch.AndroidAccessibility },
      "nested androidMaestro under containsChild → guard must fire",
    )
  }

  @Test
  fun `allDriverMatches traverses spatial branches and stays empty when there is no driver match`() {
    // below: branch carries the only (accessibility) match; nothing non-accessibility → no fire.
    val spatial = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(textRegex = "Anchor"),
      below = TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidAccessibility(textRegex = "Target")),
    )
    assertEquals(2, spatial.allDriverMatches().size)
    assertTrue(spatial.allDriverMatches().none { it !is DriverNodeMatch.AndroidAccessibility })

    // A structural-only selector with no driver leaf anywhere → empty, so the guard never fires.
    val structuralOnly = TrailblazeNodeSelector(index = 0)
    assertTrue(structuralOnly.allDriverMatches().isEmpty())
  }

  // endregion

  // region Helpers

  private fun TrailblazeToolResult.isSuccess() =
    this is TrailblazeToolResult.Success

  private fun createContext(
    agent: CapturingAgent,
    nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.PREFER_NODE_SELECTOR,
  ): TrailblazeToolExecutionContext {
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = agent.memory,
      maestroTrailblazeAgent = agent,
      nodeSelectorMode = nodeSelectorMode,
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
      timeoutMs: Long?,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      capturedAssertVisible = nodeSelector
      return assertVisibleResult
    }

    override suspend fun executeNodeSelectorAssertNotVisible(
      nodeSelector: TrailblazeNodeSelector,
      timeoutMs: Long?,
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
