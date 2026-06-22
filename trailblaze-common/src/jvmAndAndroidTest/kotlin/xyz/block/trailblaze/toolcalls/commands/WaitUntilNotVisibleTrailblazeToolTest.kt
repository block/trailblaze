package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
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
 * Unit tests for [WaitUntilNotVisibleTrailblazeTool] — the non-throwing "wait until NOT visible →
 * boolean" framework primitive. Pins the contract the Square Android launch steps depend on:
 *  - accessibility driver routes through the native `executeNodeSelectorAssertNotVisible` wait,
 *  - non-accessibility driver falls back to a Maestro `AssertConditionCommand(notVisible=…)`,
 *  - the verdict comes back as a structured boolean and NEVER as a thrown/`Error` result on the
 *    normal "still visible" path, so callers can branch and keep their own error messages,
 *  - a genuine infra *exception* still propagates (it is NOT silently collapsed to `false`).
 */
class WaitUntilNotVisibleTrailblazeToolTest {

  private fun selector() = TrailblazeNodeSelector.withMatch(
    DriverNodeMatch.AndroidAccessibility(textRegex = "Loading"),
  )

  private fun tool() = WaitUntilNotVisibleTrailblazeTool(nodeSelector = selector(), timeoutMs = 5_000L)

  private fun structuredBoolean(result: TrailblazeToolResult): Boolean {
    val success = assertIs<TrailblazeToolResult.Success>(result)
    return success.structuredContent!!.jsonPrimitive.boolean
  }

  @Test
  fun `accessibility driver - not visible returns structured true`() = runBlocking {
    val agent = FakeAgent(usesAccessibility = true, assertNotVisibleResult = TrailblazeToolResult.Success())
    val result = tool().execute(createContext(agent))

    assertEquals(JsonPrimitive(true), assertIs<TrailblazeToolResult.Success>(result).structuredContent)
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(agent.capturedAssertNotVisible?.driverMatch)
    assertEquals("Loading", match.textRegex, "should route the selector through the native wait")
    assertEquals(false, agent.maestroExecuted, "accessibility path must not fall back to Maestro")
  }

  @Test
  fun `accessibility driver - still visible returns structured false WITHOUT throwing`() = runBlocking {
    val agent = FakeAgent(
      usesAccessibility = true,
      // The AssertNotVisible action returns Error when the element is still on screen at timeout.
      assertNotVisibleResult = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "still visible"),
    )
    val result = tool().execute(createContext(agent))

    // Non-throwing: the tool reports the verdict as a boolean, never surfaces the Error.
    assertEquals(false, structuredBoolean(result))
  }

  @Test
  fun `non-accessibility driver falls back to a Maestro notVisible assertion`() = runBlocking {
    val agent = FakeAgent(usesAccessibility = false, maestroResult = TrailblazeToolResult.Success())
    val result = tool().execute(createContext(agent))

    assertEquals(true, structuredBoolean(result))
    assertTrue(agent.maestroExecuted, "non-accessibility path must use the Maestro fallback")
    val command = assertIs<AssertConditionCommand>(agent.capturedMaestroCommands.single())
    assertEquals("Loading", command.condition.notVisible?.textRegex)
    assertEquals("5000", command.timeout, "timeoutMs should ride through to the Maestro assertion")
  }

  @Test
  fun `non-accessibility driver - Maestro assertion fails returns structured false`() = runBlocking {
    val agent = FakeAgent(
      usesAccessibility = false,
      maestroResult = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "still visible"),
    )
    assertEquals(false, structuredBoolean(tool().execute(createContext(agent))))
  }

  @Test
  fun `accessibility driver returning null defensively falls back to Maestro`() = runBlocking {
    // A future accessibility agent that advertises usesAccessibilityDriver but doesn't resolve the
    // selector returns null; the tool must fall back rather than NPE.
    val agent = FakeAgent(usesAccessibility = true, assertNotVisibleResult = null, maestroResult = TrailblazeToolResult.Success())
    assertEquals(true, structuredBoolean(tool().execute(createContext(agent))))
    assertTrue(agent.maestroExecuted, "null native result should fall back to Maestro")
  }

  @Test
  fun `a genuine infra exception propagates instead of being collapsed to false`() {
    // Distinguishes a thrown infra failure (device disconnected, etc.) from the normal
    // "still visible" Error verdict: the exception escapes execute() rather than being masked.
    val agent = FakeAgent(usesAccessibility = true, assertNotVisibleThrows = true)
    assertFailsWith<IllegalStateException> {
      runBlocking { tool().execute(createContext(agent)) }
    }
  }

  private fun createContext(agent: FakeAgent) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = agent.trailblazeDeviceInfoProvider(),
    sessionProvider = agent.sessionProvider,
    trailblazeLogger = agent.trailblazeLogger,
    memory = agent.memory,
    maestroTrailblazeAgent = agent,
    nodeSelectorMode = NodeSelectorMode.PREFER_NODE_SELECTOR,
  )

  /**
   * A [MaestroTrailblazeAgent] whose driver flavor + assertion outcomes are configurable, so each
   * test can pin one branch of [WaitUntilNotVisibleTrailblazeTool].
   */
  private class FakeAgent(
    usesAccessibility: Boolean,
    private val assertNotVisibleResult: TrailblazeToolResult? = TrailblazeToolResult.Success(),
    private val assertNotVisibleThrows: Boolean = false,
    private val maestroResult: TrailblazeToolResult = TrailblazeToolResult.Success(),
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-instance",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType =
          if (usesAccessibility) {
            TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
          } else {
            TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
          },
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
  ) {
    override val usesAccessibilityDriver: Boolean = usesAccessibility

    var capturedAssertNotVisible: TrailblazeNodeSelector? = null
      private set
    var maestroExecuted: Boolean = false
      private set
    var capturedMaestroCommands: List<Command> = emptyList()
      private set

    override suspend fun executeNodeSelectorAssertNotVisible(
      nodeSelector: TrailblazeNodeSelector,
      timeoutMs: Long?,
      traceId: TraceId?,
    ): TrailblazeToolResult? {
      capturedAssertNotVisible = nodeSelector
      if (assertNotVisibleThrows) error("simulated infra failure (device disconnected)")
      return assertNotVisibleResult
    }

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      maestroExecuted = true
      capturedMaestroCommands = commands
      return maestroResult
    }
  }
}
