package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolExecutionContextThreadLocal
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.DumpMemoryTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.test.Test

class BaseTrailblazeAgentTest {

  // ── Stub tool ──

  @Serializable
  @TrailblazeToolClass("stub_executable")
  private class StubTool(
    val result: TrailblazeToolResult = TrailblazeToolResult.Success(),
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = result
  }

  // ── Stub delegating tool ──

  @TrailblazeToolClass("stub_delegating")
  private class StubDelegatingTool(
    val expandedTools: List<StubTool>,
  ) : DelegatingTrailblazeTool {
    override fun toExecutableTrailblazeTools(
      executionContext: TrailblazeToolExecutionContext,
    ): List<ExecutableTrailblazeTool> = expandedTools
  }

  // ── Stub host-local tool ──
  //
  // Dynamically-built host-local tool stand-in (what subprocess MCP tools look like at
  // runtime). Exposes a count of how many times `execute` fired so tests can prove it's
  // dispatched via the base class short-circuit rather than the agent's `executeTool`.

  private class StubHostLocalTool(
    override val advertisedToolName: String = "stub_host_local",
    val result: TrailblazeToolResult = TrailblazeToolResult.Success(),
  ) : HostLocalExecutableTrailblazeTool {
    var executeCount: Int = 0
      private set

    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      executeCount++
      return result
    }
  }

  // ── Test agent ──

  private open class TestAgent : BaseTrailblazeAgent() {
    override val trailblazeLogger = TrailblazeLogger.createNoOp()
    override val trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
    override val sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    }

    override fun buildExecutionContext(
      traceId: TraceId,
      screenState: ScreenState?,
      screenStateProvider: (() -> ScreenState)?,
    ) = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = traceId,
      trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
      sessionProvider = sessionProvider,
      trailblazeLogger = trailblazeLogger,
      memory = memory,
    )

    override fun executeTool(
      tool: TrailblazeTool,
      context: TrailblazeToolExecutionContext,
      toolsExecuted: MutableList<TrailblazeTool>,
    ): TrailblazeToolResult {
      return when (tool) {
        is DelegatingTrailblazeTool -> {
          executeDelegatingTool(tool, context, toolsExecuted) { mappedTool ->
            when (mappedTool) {
              is StubTool -> mappedTool.result
              else -> TrailblazeToolResult.Error.ExceptionThrown(
                errorMessage = "Unsupported tool",
                command = mappedTool,
                stackTrace = "",
              )
            }
          }
        }
        is StubTool -> {
          toolsExecuted.add(tool)
          tool.result
        }
        else -> {
          toolsExecuted.add(tool)
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "Unsupported tool",
            command = tool,
            stackTrace = "",
          )
        }
      }
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

  private fun run(agent: TestAgent, vararg tools: TrailblazeTool) =
    agent.runTrailblazeTools(
      tools = tools.toList(),
      elementComparator = noOpComparator,
    )

  // ── Tests ──

  @Test
  fun `executes tools sequentially and returns success`() {
    val agent = TestAgent()
    val t1 = StubTool()
    val t2 = StubTool()

    val result = run(agent, t1, t2)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.executedTools).hasSize(2)
    assertThat(result.inputTools).containsExactly(t1, t2)
  }

  @Test
  fun `stops on first failure and returns error`() {
    val agent = TestAgent()
    val failing = StubTool(
      result = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "boom",
        command = StubTool(),
        stackTrace = "",
      ),
    )
    val second = StubTool()

    val result = run(agent, failing, second)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(result.executedTools).hasSize(1)
    assertThat(result.inputTools).containsExactly(failing, second)
  }

  @Test
  fun `dispatches MemoryTrailblazeTool through base class`() {
    val agent = TestAgent()
    val memoryTool = DumpMemoryTrailblazeTool

    val result = run(agent, memoryTool)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.executedTools).containsExactly(memoryTool)
  }

  @Test
  fun `MemoryTrailblazeTool is added to executedTools and interleaves with regular tools`() {
    val agent = TestAgent()
    val t1 = StubTool()
    val memTool = DumpMemoryTrailblazeTool
    val t2 = StubTool()

    val result = run(agent, t1, memTool, t2)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.executedTools).hasSize(3)
    assertThat(result.executedTools[1]).isEqualTo(memTool)
  }

  @Test
  fun `inputTools is always the original tools list`() {
    val agent = TestAgent()
    val tools = listOf(StubTool(), StubTool(), StubTool())

    val result = agent.runTrailblazeTools(
      tools = tools,
      elementComparator = noOpComparator,
    )

    assertThat(result.inputTools).isEqualTo(tools)
  }

  // ── Delegating tool tests ──

  @Test
  fun `delegating tool expands into sub-tools in executedTools`() {
    val agent = TestAgent()
    val sub1 = StubTool()
    val sub2 = StubTool()
    val delegating = StubDelegatingTool(expandedTools = listOf(sub1, sub2))

    val result = run(agent, delegating)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.executedTools).containsExactly(sub1, sub2)
  }

  @Test
  fun `delegating tool stops on sub-tool failure`() {
    val agent = TestAgent()
    val failingSub = StubTool(
      result = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "sub-tool failed",
        command = StubTool(),
        stackTrace = "",
      ),
    )
    val secondSub = StubTool()
    val delegating = StubDelegatingTool(expandedTools = listOf(failingSub, secondSub))

    val result = run(agent, delegating)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(result.executedTools).containsExactly(failingSub)
  }

  @Test
  fun `delegating tool interleaves with regular tools in executedTools`() {
    val agent = TestAgent()
    val t1 = StubTool()
    val sub1 = StubTool()
    val sub2 = StubTool()
    val delegating = StubDelegatingTool(expandedTools = listOf(sub1, sub2))
    val t2 = StubTool()

    val result = run(agent, t1, delegating, t2)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.executedTools).containsExactly(t1, sub1, sub2, t2)
  }

  // ── Host-local dispatch tests ──

  @Test
  fun `HostLocalExecutableTrailblazeTool runs via execute - not executeTool`() {
    // Agent whose executeTool fails any tool that reaches it. A HostLocal tool should
    // short-circuit in the base class and never hit executeTool, so the run succeeds.
    val agent = object : TestAgent() {
      override fun executeTool(
        tool: TrailblazeTool,
        context: TrailblazeToolExecutionContext,
        toolsExecuted: MutableList<TrailblazeTool>,
      ): TrailblazeToolResult = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "executeTool should not be called for host-local tools",
        command = tool,
        stackTrace = "",
      )
    }
    val hostLocal = StubHostLocalTool()

    val result = run(agent, hostLocal)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(hostLocal.executeCount).isEqualTo(1)
    assertThat(result.executedTools).containsExactly(hostLocal)
  }

  @Test
  fun `HostLocalExecutableTrailblazeTool error stops execution like any other failure`() {
    val agent = TestAgent()
    val failing = StubHostLocalTool(
      result = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "host-local boom",
        command = StubTool(),
        stackTrace = "",
      ),
    )
    val neverRuns = StubTool()

    val result = run(agent, failing, neverRuns)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(result.executedTools).containsExactly(failing)
    assertThat(failing.executeCount).isEqualTo(1)
  }

  @Test
  fun `HostLocal tools interleave with regular and memory tools`() {
    val agent = TestAgent()
    val t1 = StubTool()
    val hostLocal = StubHostLocalTool()
    val memTool = DumpMemoryTrailblazeTool
    val t2 = StubTool()

    val result = run(agent, t1, hostLocal, memTool, t2)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.executedTools).containsExactly(t1, hostLocal, memTool, t2)
    assertThat(hostLocal.executeCount).isEqualTo(1)
  }

  // ── ToolExecutionContextThreadLocal install/clear (PR #2756 lead-dev review #5) ──

  @Test
  fun `runTrailblazeTools clears the ToolExecutionContextThreadLocal slot after a successful batch`() {
    // Pins the `try { install } finally { clear }` wrapper added in #2749. A leaked slot
    // would let a later batch on the same thread observe a stale context.
    ToolExecutionContextThreadLocal.clear() // defensive: start clean
    val agent = TestAgent()
    val result = run(agent, StubTool(), StubTool())

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(ToolExecutionContextThreadLocal.get()).isNull()
  }

  @Test
  fun `runTrailblazeTools clears the ToolExecutionContextThreadLocal slot when a tool returns failure`() {
    // The early-return path on first failure must still hit the `finally` and clear the
    // slot. Without `try/finally` the outer return would skip the clear.
    ToolExecutionContextThreadLocal.clear()
    val agent = TestAgent()
    val failing = StubTool(
      result = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "boom",
        command = StubTool(),
        stackTrace = "",
      ),
    )
    val neverRuns = StubTool()

    val result = run(agent, failing, neverRuns)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(ToolExecutionContextThreadLocal.get()).isNull()
  }

  @Test
  fun `back-to-back batches on the same thread see their own freshly-installed context`() {
    // Closes the leak loop: prove the slot is freed between batches AND that the second
    // batch's install isn't observing the first batch's context. We capture the slot
    // value during each batch via a sentinel host-local tool that records what
    // ToolExecutionContextThreadLocal.get() returns at execute time.
    ToolExecutionContextThreadLocal.clear()

    val captured = mutableListOf<TrailblazeToolExecutionContext?>()

    class SnapshotTool : HostLocalExecutableTrailblazeTool {
      override val advertisedToolName: String = "snapshot_tool"
      override suspend fun execute(
        toolExecutionContext: TrailblazeToolExecutionContext,
      ): TrailblazeToolResult {
        captured += ToolExecutionContextThreadLocal.get()
        return TrailblazeToolResult.Success()
      }
    }

    val agent = TestAgent()
    run(agent, SnapshotTool())
    run(agent, SnapshotTool())

    // Both batches saw a non-null context (their own install) at execute time.
    assertThat(captured).hasSize(2)
    assertThat(captured[0]).isNotNull()
    assertThat(captured[1]).isNotNull()
    // The two captured contexts MUST be different instances — if they were equal,
    // the second batch was reading a stale slot from the first batch's leaked install.
    assertThat(captured[0] === captured[1]).isEqualTo(false)
    // After both batches, the slot is cleared again.
    assertThat(ToolExecutionContextThreadLocal.get()).isNull()
  }

  // ── Host-local tool logging (every dispatch emits a TrailblazeToolLog — #2924) ──

  private fun capturingAgent(captured: MutableList<TrailblazeLog>): TestAgent =
    object : TestAgent() {
      override val trailblazeLogger: TrailblazeLogger = TrailblazeLogger(
        logEmitter = LogEmitter { log -> captured.add(log) },
        screenStateLogger = ScreenStateLogger { "" },
      )
    }

  // Host-local tool that overrides what gets recorded by setting `recordedToolOverride`. The
  // recording shape (with the produced output captured for replay) is what ships in the log
  // payload, not the original input-shape instance.
  @Serializable
  @TrailblazeToolClass("override_emitting_stub")
  private class OverrideEmittingStubTool(
    val outputValue: String? = null,
  ) : HostLocalExecutableTrailblazeTool {
    override val advertisedToolName: String = "override_emitting_stub"
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      toolExecutionContext.recordedToolOverride =
        OverrideEmittingStubTool(outputValue = "produced-value")
      return TrailblazeToolResult.Success()
    }
  }

  @Test
  fun `host-local tool's recordedToolOverride is applied to the emitted TrailblazeToolLog`() {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = capturingAgent(captured)

    val result = run(agent, OverrideEmittingStubTool())

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].toolName).isEqualTo("override_emitting_stub")
    assertThat(toolLogs[0].trailblazeTool.toString().contains("produced-value")).isEqualTo(true)
  }

  @Test
  fun `plain HostLocalExecutableTrailblazeTool emits a TrailblazeToolLog naming the advertised tool`() {
    // Regression: a marker-interface gate (the prior shape) left plain HostLocal tools
    // (the shape `SubprocessTrailblazeTool` and scripted MCP tools take) invisible to
    // session logs, recordings, and reports. Every host-local dispatch
    // must produce a log entry — otherwise recording auto-save silently swaps the missing
    // step for an unrelated fallback.
    val captured = mutableListOf<TrailblazeLog>()
    val agent = capturingAgent(captured)

    val result = run(agent, StubHostLocalTool(advertisedToolName = "stub_plain_host_local"))

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].toolName).isEqualTo("stub_plain_host_local")
    assertThat(toolLogs[0].successful).isEqualTo(true)
  }

  // Stand-in for SubprocessTrailblazeTool — same interface shape (HostLocal + RawArgument, no
  // class-level @TrailblazeToolClass, args as a JsonObject). The base agent must log this
  // dispatch even though the concrete class isn't `@Serializable`.
  private class DynamicSubprocessLikeTool(
    override val advertisedToolName: String,
    override val rawToolArguments: JsonObject,
    val result: TrailblazeToolResult = TrailblazeToolResult.Success(),
  ) : HostLocalExecutableTrailblazeTool, xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool {
    override val instanceToolName: String get() = advertisedToolName

    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = result
  }

  @Test
  fun `subprocess-shaped dynamic tool emits a TrailblazeToolLog with toolName args and durationMs`() {
    // End-to-end shape check: a spawn-style dynamic tool dispatched through the agent must
    // produce a numbered TrailblazeToolLog entry with non-empty args, durationMs, and a
    // successful flag — the same surface session-log readers and recording reconstruction
    // rely on. Mirrors what `SubprocessTrailblazeTool` looks like at runtime without needing
    // a real spawned MCP server in unit tests.
    val captured = mutableListOf<TrailblazeLog>()
    val agent = capturingAgent(captured)
    val args = kotlinx.serialization.json.buildJsonObject {
      put("cardId", kotlinx.serialization.json.JsonPrimitive("card_abc123"))
    }
    val tool = DynamicSubprocessLikeTool(
      advertisedToolName = "subprocess_removeCard",
      rawToolArguments = args,
    )

    val result = run(agent, tool)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    val log = toolLogs[0]
    assertThat(log.toolName).isEqualTo("subprocess_removeCard")
    assertThat(log.successful).isEqualTo(true)
    assertThat(log.durationMs >= 0L).isEqualTo(true)
    // Raw args are preserved on the persisted payload so recording reconstruction has
    // everything it needs to rebuild the original tool call.
    assertThat(log.trailblazeTool.raw).isEqualTo(args)
    // Host-local dispatches set `dispatchedHostSide` so session viewers / reports can badge
    // them as such — the discoverability fix from the #2935 follow-up review.
    assertThat(log.dispatchedHostSide).isEqualTo(true)
  }

  @Test
  fun `host-local dispatch that THROWS still emits a TrailblazeToolLog with successful=false`() {
    // Regression for the PR #2935 review's High—Correctness finding. The contract is
    // "every dispatch logs" — that has to hold even when the tool's `execute` throws
    // rather than returning a `TrailblazeToolResult.Error`. Custom HostLocal authors and
    // future regressions in `SubprocessTrailblazeTool` could both let an exception escape;
    // the captured log must still be there.
    val captured = mutableListOf<TrailblazeLog>()
    val agent = capturingAgent(captured)

    class ThrowingHostLocal : HostLocalExecutableTrailblazeTool {
      override val advertisedToolName: String = "throwing_subprocess_tool"
      override suspend fun execute(
        toolExecutionContext: TrailblazeToolExecutionContext,
      ): TrailblazeToolResult = throw RuntimeException("subprocess transport died")
    }

    val result = run(agent, ThrowingHostLocal())

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].toolName).isEqualTo("throwing_subprocess_tool")
    assertThat(toolLogs[0].successful).isEqualTo(false)
    assertThat(toolLogs[0].exceptionMessage).isEqualTo("subprocess transport died")
  }

  @Test
  fun `failed host-local dispatch still emits a TrailblazeToolLog with successful=false`() {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = capturingAgent(captured)
    val failing = DynamicSubprocessLikeTool(
      advertisedToolName = "subprocess_brokenTool",
      rawToolArguments = kotlinx.serialization.json.buildJsonObject {},
      result = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "subprocess crashed",
        command = StubTool(),
        stackTrace = "",
      ),
    )

    run(agent, failing)

    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].successful).isEqualTo(false)
    assertThat(toolLogs[0].exceptionMessage).isEqualTo("subprocess crashed")
  }
}
