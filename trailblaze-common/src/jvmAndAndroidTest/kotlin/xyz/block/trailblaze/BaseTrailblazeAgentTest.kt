package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
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
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
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
}
