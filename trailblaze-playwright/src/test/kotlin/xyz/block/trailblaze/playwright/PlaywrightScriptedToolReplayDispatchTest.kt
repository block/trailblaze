package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Page
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral coverage for how the web agent dispatches `OtherTrailblazeTool` — the shape
 * every scripted (TS) tool takes when a recorded trail is deserialized, since scripted
 * tools have no Kotlin class and land on the YAML decoder's unknown-name placeholder.
 *
 * This is the replay path for recordings that embed scripted-tool calls (e.g. the
 * Wikipedia example's `wikipedia_web_*` tools): whether they replay mechanically hinges
 * on the placeholder resolving through the session's [TrailblazeToolRepo] before driver
 * dispatch. `HostOnDeviceRpcTrailblazeAgentTest` pins the same contract for the RPC agent.
 */
class PlaywrightScriptedToolReplayDispatchTest {

  /** Page manager whose members are never expected to be touched by these tool bodies. */
  private class NeverCalledPageManager : PlaywrightPageManager {
    override val currentPage: Page get() = error("currentPage should not be invoked in this test")
    override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()
    override fun requestDetails(details: Set<ViewHierarchyDetail>) = error("unused")
    override fun getScreenState(): ScreenState = error("unused")
    override fun captureScreenStateForLogging(): ScreenState = error("unused")
    override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
    override fun resetSession() = Unit
    override fun close() = Unit
  }

  /**
   * Minimal [DynamicTrailblazeToolRegistration]: `decodeToolCall` returns [decodedTool]
   * verbatim, so the test can plant a [HostLocalExecutableTrailblazeTool] under a name —
   * the same registration shape `LazyYamlScriptedToolRegistration` gives real TS tools.
   */
  private class FakeScriptedToolRegistration(
    registeredName: String,
    private val decodedTool: TrailblazeTool,
  ) : DynamicTrailblazeToolRegistration {
    override val name: ToolName = ToolName(registeredName)
    override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
      name = registeredName,
      description = "fake scripted tool",
      requiredParameters = emptyList(),
      optionalParameters = emptyList(),
    )
    override fun buildKoogTool(
      trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
    ): TrailblazeKoogTool<out TrailblazeTool> =
      error("buildKoogTool not used — replay dispatches via decodeToolCall")

    override fun decodeToolCall(argumentsJson: String): TrailblazeTool = decodedTool
  }

  private class FakeHostLocalScriptedTool(
    private val executed: AtomicBoolean,
  ) : HostLocalExecutableTrailblazeTool {
    override val advertisedToolName: String = "fake_scripted_tool"
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      executed.set(true)
      return TrailblazeToolResult.Success()
    }
  }

  private fun emptyRepo(): TrailblazeToolRepo = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "test",
      toolClasses = emptySet(),
      yamlToolNames = emptySet(),
    ),
    toolSetCatalog = null,
  )

  private fun buildAgent(toolRepo: TrailblazeToolRepo?): PlaywrightTrailblazeAgent =
    PlaywrightTrailblazeAgent(
      browserManager = NeverCalledPageManager(),
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "fixture-browser",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
          ),
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          widthPixels = 1280,
          heightPixels = 800,
        )
      },
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
      },
      trailblazeToolRepo = toolRepo,
    )

  private val noOpComparator = object : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) =
      BooleanAssertionTrailblazeTool(reason = statement, result = true)

    override fun evaluateString(query: String) =
      StringEvaluationTrailblazeTool(reason = query, result = "")

    override fun extractNumberFromString(input: String): Double? = null
  }

  @Test
  fun `a registered scripted tool's placeholder resolves and executes through the batch path`() {
    // The daemon web path registers target.tools: scripted tools into the session repo
    // before replay — this pins that a recording's OtherTrailblazeTool placeholder for
    // such a tool dispatches (via BaseTrailblazeAgent's resolveDynamicTool + host-local
    // branch) instead of dying in the web agent's type-discriminating `when`.
    val executed = AtomicBoolean(false)
    val repo = emptyRepo().apply {
      addDynamicTools(
        listOf(FakeScriptedToolRegistration("fake_scripted_tool", FakeHostLocalScriptedTool(executed))),
      )
    }
    val agent = buildAgent(repo)

    val result = runBlocking {
      agent.runTrailblazeTools(
        tools = listOf(OtherTrailblazeTool(toolName = "fake_scripted_tool", raw = JsonObject(emptyMap()))),
        elementComparator = noOpComparator,
      )
    }

    assertTrue(executed.get(), "The registered scripted tool must actually execute on replay.")
    assertTrue(
      result.result is TrailblazeToolResult.Success,
      "Batch dispatch of a registered scripted tool must succeed: ${result.result}",
    )
  }

  @Test
  fun `an unregistered scripted tool's placeholder fails loudly, never silently succeeds`() {
    // The failure MESSAGE is currently the anonymous "Unhandled Trailblaze tool
    // OtherTrailblazeTool" throw from PlaywrightTrailblazeAgent's else branch — the
    // planned dispatch fix will name the tool instead (mirroring MaestroTrailblazeAgent).
    // This test deliberately pins only the durable property: unresolvable placeholders
    // fail the run loudly rather than no-op'ing, so a recording with a missing tool can
    // never "pass" by skipping steps.
    val agent = buildAgent(emptyRepo())

    val outcome = runCatching {
      runBlocking {
        agent.runTrailblazeTools(
          tools = listOf(OtherTrailblazeTool(toolName = "unregistered_tool", raw = JsonObject(emptyMap()))),
          elementComparator = noOpComparator,
        )
      }
    }

    val silentlySucceeded = outcome.isSuccess && outcome.getOrThrow().result is TrailblazeToolResult.Success
    assertFalse(silentlySucceeded, "An unresolvable scripted-tool placeholder must fail the run.")
  }
}
