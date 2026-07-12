package xyz.block.trailblaze.playwright

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import com.microsoft.playwright.Page
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Regression coverage for [PlaywrightTrailblazeAgent]'s `nestedToolExecutor` wiring — the
 * Playwright counterpart of `MaestroNestedToolCompositionTest` (`:trailblaze-common`). Both agents
 * share the identical fix: `BaseTrailblazeAgent.nestedToolExecutorFor` dispatches a nested
 * `ctx.tools.X()` call directly against the closure-captured outer context instead of rebuilding
 * one via `runTrailblazeTools`. See `docs/devlog/2026-07-03-batched-tool-execution-scope.md`.
 *
 * Unlike the Android canary (a device-scoped clipboard cache), Playwright's `nestedToolExecutor`
 * has no analogous context-scoped resource to lose today — this test instead pins the underlying,
 * driver-agnostic contract directly: sequential nested calls composed by one scripted tool observe
 * the SAME [TrailblazeToolExecutionContext] instance, not a freshly rebuilt one.
 */
class PlaywrightNestedToolCompositionTest {

  /** Page manager whose members are never expected to be touched by this test's tool bodies. */
  private class NeverCalledPageManager : PlaywrightPageManager {
    override val currentPage: Page get() = error("currentPage should not be invoked in this test")
    override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()

    override fun requestDetails(details: Set<ViewHierarchyDetail>) {
      error("requestDetails should not be invoked in this test")
    }

    override fun getScreenState(): ScreenState = error("getScreenState should not be invoked in this test")

    override fun captureScreenStateForLogging(): ScreenState =
      error("captureScreenStateForLogging should not be invoked in this test")

    override fun waitForPageReady(domStabilityTimeoutMs: Double) {
      error("waitForPageReady should not be invoked in this test")
    }

    override fun resetSession() {
      error("resetSession should not be invoked in this test")
    }

    override fun close() = Unit
  }

  private fun buildAgent(): PlaywrightTrailblazeAgent = PlaywrightTrailblazeAgent(
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
  )

  /**
   * Closure-carrying tool. Doesn't need `@Serializable`/`@TrailblazeToolClass` — it's constructed
   * directly in-test and dispatched via `nestedToolExecutor`, never through the repo/JSON decode
   * path a real scripted-tool composition would use.
   */
  private class LambdaTool(
    val block: suspend (TrailblazeToolExecutionContext) -> TrailblazeToolResult,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = block(toolExecutionContext)
  }

  /**
   * Models a scripted tool composing two nested framework-tool calls in sequence. Records the
   * [TrailblazeToolExecutionContext] instance each nested call observed so the test can assert
   * both calls shared the SAME context — the property that was broken before the fix (each nested
   * call rebuilt its own context via `runTrailblazeTools`).
   */
  private class RecordContextTwiceComposingTool(
    private val observedContexts: MutableList<TrailblazeToolExecutionContext>,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      val nested = toolExecutionContext.nestedToolExecutor
        ?: return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "nestedToolExecutor not wired",
        )

      val firstResult = nested.invoke(
        LambdaTool { ctx ->
          observedContexts += ctx
          TrailblazeToolResult.Success()
        },
      )
      if (firstResult !is TrailblazeToolResult.Success) return firstResult

      return nested.invoke(
        LambdaTool { ctx ->
          observedContexts += ctx
          TrailblazeToolResult.Success()
        },
      )
    }
  }

  private val noOpComparator = object : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) =
      BooleanAssertionTrailblazeTool(reason = statement, result = true)
    override fun evaluateString(query: String) =
      StringEvaluationTrailblazeTool(reason = query, result = "")
    override fun extractNumberFromString(input: String): Double? = null
  }

  @Test
  fun `a scripted tool's two sequential nested framework-tool calls share one context`() = runBlocking {
    val agent = buildAgent()
    val observedContexts = mutableListOf<TrailblazeToolExecutionContext>()

    val result = agent.runTrailblazeTools(
      tools = listOf(RecordContextTwiceComposingTool(observedContexts)),
      elementComparator = noOpComparator,
    )

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(observedContexts).hasSize(2)
    // Before the fix, each `nestedToolExecutor.invoke(...)` call rebuilt a fresh
    // TrailblazeToolExecutionContext via `runTrailblazeTools`. After the fix, both nested calls
    // dispatch against the SAME closure-captured context.
    assertThat(observedContexts[1]).isSameInstanceAs(observedContexts[0])
  }
}
