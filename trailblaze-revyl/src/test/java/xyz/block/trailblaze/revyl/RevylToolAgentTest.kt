package xyz.block.trailblaze.revyl

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.tools.RevylExecutableTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.Status
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.test.assertContains

/**
 * Unit tests for [RevylToolAgent] dispatch logic, error handling,
 * and platform-specific context construction.
 *
 * Uses a stub [RevylExecutableTool] to avoid needing a real Revyl CLI
 * binary or cloud device. The [RevylCliClient] is constructed with
 * `/bin/echo` as the binary override so its init-time availability
 * check passes without the real CLI installed.
 */
class RevylToolAgentTest {

  private val dummyClient = RevylCliClient("/bin/echo")

  private val noOpComparator = object : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) =
      BooleanAssertionTrailblazeTool(reason = statement, result = true)
    override fun evaluateString(query: String) =
      StringEvaluationTrailblazeTool(reason = query, result = "")
    override fun extractNumberFromString(input: String): Double? = null
  }

  // ── Stub tool that records execution and returns a configurable result ──

  @Serializable
  @TrailblazeToolClass("stub_revyl_tool")
  private class StubRevylTool(
    @Transient private val result: TrailblazeToolResult = TrailblazeToolResult.Success(),
    @Transient private val onExecute: ((TrailblazeToolExecutionContext) -> Unit)? = null,
  ) : RevylExecutableTool {
    @Transient var executed = false

    override suspend fun executeWithRevyl(
      client: RevylCliClient,
      context: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      executed = true
      onExecute?.invoke(context)
      return result
    }
  }

  @TrailblazeToolClass("stub_throwing_tool")
  private class ThrowingRevylTool(
    private val exception: Exception = RuntimeException("boom"),
  ) : RevylExecutableTool {
    override suspend fun executeWithRevyl(
      client: RevylCliClient,
      context: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      throw exception
    }
  }

  /** A tool type that RevylToolAgent does not recognize. */
  @Serializable
  @TrailblazeToolClass("unknown_tool")
  private class UnsupportedTool : TrailblazeTool

  // ── Helpers ──

  private fun runTools(
    agent: RevylToolAgent,
    vararg tools: TrailblazeTool,
  ) = agent.runTrailblazeTools(
    tools = tools.toList(),
    traceId = null,
    screenState = null,
    elementComparator = noOpComparator,
    screenStateProvider = null,
  )

  // ── Tests ──

  @Test
  fun `dispatches RevylExecutableTool and returns Success`() {
    val agent = RevylToolAgent(dummyClient, "android")
    val tool = StubRevylTool()

    val result = runTools(agent, tool)

    assertThat(tool.executed).isTrue()
    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.executedTools).hasSize(1)
  }

  @Test
  fun `returns Success for ObjectiveStatusTrailblazeTool`() {
    val agent = RevylToolAgent(dummyClient, "android")
    val tool = ObjectiveStatusTrailblazeTool(
      explanation = "test complete",
      status = Status.COMPLETED,
    )

    val result = runTools(agent, tool)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.executedTools).hasSize(1)
  }

  @Test
  fun `returns UnknownTrailblazeTool for unsupported tool type`() {
    val agent = RevylToolAgent(dummyClient, "android")
    val tool = UnsupportedTool()

    val result = runTools(agent, tool)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error.UnknownTrailblazeTool::class)
  }

  @Test
  fun `stops execution on first tool failure`() {
    val agent = RevylToolAgent(dummyClient, "android")
    val failingTool = StubRevylTool(
      result = TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "simulated failure",
        command = StubRevylTool(),
        stackTrace = "",
      ),
    )
    val secondTool = StubRevylTool()

    val result = runTools(agent, failingTool, secondTool)

    assertThat(failingTool.executed).isTrue()
    assertThat(secondTool.executed).isEqualTo(false)
    assertThat(result.executedTools).hasSize(1)
    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  @Test
  fun `catches exception and returns ExceptionThrown`() {
    val agent = RevylToolAgent(dummyClient, "android")
    val tool = ThrowingRevylTool(RuntimeException("something broke"))

    val result = runTools(agent, tool)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    val error = result.result as TrailblazeToolResult.Error.ExceptionThrown
    assertContains(error.errorMessage, "something broke")
  }

  @Test
  fun `builds context with IOS platform when platform is ios`() {
    val agent = RevylToolAgent(dummyClient, "ios")
    var capturedContext: TrailblazeToolExecutionContext? = null
    val tool = StubRevylTool(onExecute = { capturedContext = it })

    runTools(agent, tool)

    val info = capturedContext!!.trailblazeDeviceInfo
    assertThat(info.trailblazeDriverType).isEqualTo(TrailblazeDriverType.IOS_HOST)
    assertThat(info.trailblazeDeviceId.trailblazeDevicePlatform)
      .isEqualTo(TrailblazeDevicePlatform.IOS)
  }

  @Test
  fun `builds context with ANDROID platform when platform is android`() {
    val agent = RevylToolAgent(dummyClient, "android")
    var capturedContext: TrailblazeToolExecutionContext? = null
    val tool = StubRevylTool(onExecute = { capturedContext = it })

    runTools(agent, tool)

    val info = capturedContext!!.trailblazeDeviceInfo
    assertThat(info.trailblazeDriverType).isEqualTo(TrailblazeDriverType.ANDROID_HOST)
    assertThat(info.trailblazeDeviceId.trailblazeDevicePlatform)
      .isEqualTo(TrailblazeDevicePlatform.ANDROID)
  }

  @Test
  fun `executes multiple tools sequentially on success`() {
    val agent = RevylToolAgent(dummyClient, "android")
    val tool1 = StubRevylTool()
    val tool2 = StubRevylTool()
    val tool3 = StubRevylTool()

    val result = runTools(agent, tool1, tool2, tool3)

    assertThat(tool1.executed).isTrue()
    assertThat(tool2.executed).isTrue()
    assertThat(tool3.executed).isTrue()
    assertThat(result.executedTools).hasSize(3)
    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(result.inputTools).hasSize(3)
  }
}
