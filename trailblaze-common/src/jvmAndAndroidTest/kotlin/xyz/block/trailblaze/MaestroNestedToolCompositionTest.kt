package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.test.Test
import maestro.orchestra.Command
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Regression coverage for nested scripted-tool composition — the follow-up to PR #4506's
 * [xyz.block.trailblaze.toolcalls.ToolBatchScope] fix (see
 * `docs/devlog/2026-07-03-batched-tool-execution-scope.md`, "Deliberately out of
 * scope" section).
 *
 * #4506 fixed recorded replay building a fresh [TrailblazeToolExecutionContext] per tool, which
 * dropped cross-tool device state cached ON the context (the canary:
 * [AndroidDeviceCommandExecutor]'s in-process clipboard cache, since Android 10+ blocks
 * cross-process clipboard reads). The identical bug exists in nested scripted-tool composition —
 * a scripted tool calling `ctx.tools.setClipboard(...)` then `ctx.tools.pasteClipboard(...)`
 * inside one execute() — because `MaestroTrailblazeAgent.buildExecutionContext`'s
 * `nestedToolExecutor` closure re-entered `runTrailblazeTools(...)` fresh instead of dispatching
 * against the closure-captured outer context.
 *
 * This models the `mobile_setClipboard` -> `mobile_pasteClipboard` canary without touching real
 * ADB: a fake device-state cache keyed by the *context's* [AndroidDeviceCommandExecutor]
 * instance, mirroring that executor's real `lastSetClipboard` field. `AndroidDeviceCommandExecutor`
 * is a plain (non-data) class, so default reference identity is exactly what a `Map` key needs to
 * catch "nested calls silently got a different executor instance."
 */
class MaestroNestedToolCompositionTest {

  /** Minimal concrete [MaestroTrailblazeAgent] — mirrors `MaestroAgentContextChainTest.FixtureAgent`. */
  private class FixtureAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
  ) {
    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  /**
   * Closure-carrying tool. Doesn't need `@Serializable`/`@TrailblazeToolClass` — it's constructed
   * directly in-test and dispatched via `nestedToolExecutor`, never through the repo/JSON decode
   * path a real scripted-tool composition would use (that path is already covered by
   * `SessionScopedHostBindingTest`; this test targets the agent's context-identity contract).
   */
  private class LambdaTool(
    val block: suspend (TrailblazeToolExecutionContext) -> TrailblazeToolResult,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = block(toolExecutionContext)
  }

  /**
   * Models a scripted tool composing two nested framework-tool calls in sequence — exactly what
   * `SessionScopedHostBinding.executeResolved` does for `ctx.tools.setClipboard(...)` followed by
   * `ctx.tools.pasteClipboard(...)` inside one bundle's execute(). The cache is keyed by the
   * *context's* `androidDeviceCommandExecutor` instance, so this fails the same way the real
   * clipboard round trip does if the two nested calls land on different contexts/executors.
   */
  private class SetThenPasteComposingTool(
    private val clipboardByExecutor: MutableMap<AndroidDeviceCommandExecutor, String>,
    private val text: String,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      val nested = toolExecutionContext.nestedToolExecutor
        ?: return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "nestedToolExecutor not wired",
        )

      val setResult = nested.invoke(
        LambdaTool { ctx ->
          val executor = ctx.androidDeviceCommandExecutor
            ?: return@LambdaTool TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "no executor")
          clipboardByExecutor[executor] = text
          TrailblazeToolResult.Success()
        },
      )
      if (setResult !is TrailblazeToolResult.Success) return setResult

      return nested.invoke(
        LambdaTool { ctx ->
          val executor = ctx.androidDeviceCommandExecutor
            ?: return@LambdaTool TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "no executor")
          val cached = clipboardByExecutor[executor]
          if (cached == null) {
            TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "mobile_pasteClipboard: device clipboard is empty.",
            )
          } else {
            TrailblazeToolResult.Success(message = "pasted:$cached")
          }
        },
      )
    }
  }

  /**
   * Models a scripted tool whose first nested call fails, composing a second nested call that
   * records whether it ran. Pins that a failing nested call short-circuits the composing tool's
   * `execute()` — the second `ctx.tools.X()` call must never fire — the same early-return
   * contract `SessionScopedHostBinding.executeResolved` relies on for a `DelegatingTrailblazeTool`'s
   * expanded sub-tools.
   */
  private class FailThenRecordComposingTool(
    private val secondCallInvoked: MutableList<Boolean>,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      val nested = toolExecutionContext.nestedToolExecutor
        ?: return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "nestedToolExecutor not wired",
        )

      val firstResult = nested.invoke(
        LambdaTool {
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "first nested call failed")
        },
      )
      if (firstResult !is TrailblazeToolResult.Success) return firstResult

      return nested.invoke(
        LambdaTool {
          secondCallInvoked += true
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
    val agent = FixtureAgent()
    val cache = mutableMapOf<AndroidDeviceCommandExecutor, String>()

    val result = agent.runTrailblazeTools(
      tools = listOf(SetThenPasteComposingTool(cache, text = "HELLO")),
      elementComparator = noOpComparator,
    )

    // Before the fix, each `nestedToolExecutor.invoke(...)` call rebuilt a fresh
    // TrailblazeToolExecutionContext (and therefore a fresh AndroidDeviceCommandExecutor) via
    // `runTrailblazeTools`, so the "paste" half read an empty cache — exactly the "device
    // clipboard is empty" symptom from the real mobile_setClipboard -> mobile_pasteClipboard
    // round trip. After the fix, both nested calls dispatch against the SAME closure-captured
    // context, so the write is visible to the read.
    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result.result as TrailblazeToolResult.Success).message).isEqualTo("pasted:HELLO")
  }

  @Test
  fun `a failing nested call short-circuits and the next nested call never runs`() = runBlocking {
    val agent = FixtureAgent()
    val secondCallInvoked = mutableListOf<Boolean>()

    val result = agent.runTrailblazeTools(
      tools = listOf(FailThenRecordComposingTool(secondCallInvoked)),
      elementComparator = noOpComparator,
    )

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(secondCallInvoked).isEqualTo(emptyList())
  }
}
