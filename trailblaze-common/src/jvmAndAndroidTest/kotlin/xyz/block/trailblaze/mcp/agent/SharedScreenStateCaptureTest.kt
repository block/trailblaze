package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
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
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Unit tests for [SharedScreenStateCapture] — one shared capture per agent turn. */
class SharedScreenStateCaptureTest {

  private class FakeScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode? = null
    override val annotationElements: List<AnnotationElement>? = null
  }

  @Test
  fun `captures once per request and reuses the snapshot within that request`() {
    var captures = 0
    val capture = SharedScreenStateCapture { captures++; FakeScreenState() }
    val provider = capture.asProvider()

    val first = provider()
    val second = provider()

    assertThat(captures).isEqualTo(1) // only one underlying capture for this request...
    assertThat(first).isSameInstanceAs(second) // ...and both callers see the same snapshot
  }

  @Test
  fun `clear forces a fresh capture for the next consumer`() {
    var captures = 0
    val capture = SharedScreenStateCapture { captures++; FakeScreenState() }
    val provider = capture.asProvider()

    val firstCapture = provider()
    capture.clear()
    val secondCapture = provider()

    assertThat(captures).isEqualTo(2)
    assertThat(firstCapture).isNotSameInstanceAs(secondCapture)
  }

  @Test
  fun `post-action capture is reused by the next request`() {
    var captures = 0
    val capture = SharedScreenStateCapture { captures++; FakeScreenState() }
    val provider = capture.asProvider()

    val requestScreen = provider()
    val toolPreActionScreen = provider()
    val toolPostActionScreen = capture.captureFresh()
    val nextRequestScreen = provider()

    assertThat(captures).isEqualTo(2)
    assertThat(toolPreActionScreen).isSameInstanceAs(requestScreen)
    assertThat(nextRequestScreen).isSameInstanceAs(toolPostActionScreen)
    assertThat(toolPostActionScreen).isNotSameInstanceAs(requestScreen)
  }

  @Test
  fun `failed fresh capture invalidates the previous snapshot`() {
    var captures = 0
    val capture = SharedScreenStateCapture {
      captures++
      if (captures == 2) error("capture failed")
      FakeScreenState()
    }
    val provider = capture.asProvider()

    val requestScreen = provider()
    assertFailsWith<IllegalStateException> { capture.captureFresh() }
    val recoveredScreen = provider()

    assertThat(captures).isEqualTo(3)
    assertThat(recoveredScreen).isNotSameInstanceAs(requestScreen)
  }

  @OptIn(InternalAgentToolsApi::class)
  @Test
  fun `dynamic tool execution invalidates the snapshot`() {
    var captures = 0
    val capture = SharedScreenStateCapture { captures++; FakeScreenState() }
    val provider = capture.asProvider()
    val requestScreen = provider()
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "dynamic-only",
        toolClasses = emptySet(),
      ),
    )
    repo.addDynamicTools(listOf(DynamicCaptureTestRegistration))
    val registry = repo.asToolRegistryWithDynamicToolHook(
      toolDispatcher = { error("class-backed dispatcher must not run") },
      trailblazeToolContextProvider = { error("dynamic test tool does not need a context") },
      afterDynamicToolExecution = capture::clear,
    )

    runBlocking {
      registry.getTool("dynamicCaptureTest").executeUnsafe(DynamicCaptureTestTool())
    }
    val nextRequestScreen = provider()

    assertThat(captures).isEqualTo(2)
    assertThat(nextRequestScreen).isNotSameInstanceAs(requestScreen)
  }

  /**
   * A scripted tool's result reaches the LLM through the decorator, which is how the strategy-graph
   * runner attaches the post-action screen. The screen it captures there is the one the next
   * request reuses, so decorating costs no extra capture.
   */
  @OptIn(InternalAgentToolsApi::class)
  @Test
  fun `dynamic tool result is decorated with a fresh screen the next request reuses`() {
    var captures = 0
    val capture = SharedScreenStateCapture { captures++; FakeScreenState() }
    val provider = capture.asProvider()
    provider()
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "dynamic-only",
        toolClasses = emptySet(),
      ),
    )
    repo.addDynamicTools(listOf(DynamicCaptureTestRegistration))
    var postActionScreen: ScreenState? = null
    val registry = repo.asToolRegistryWithDynamicToolHook(
      toolDispatcher = { error("class-backed dispatcher must not run") },
      trailblazeToolContextProvider = { error("dynamic test tool does not need a context") },
      afterDynamicToolExecution = capture::clear,
      decorateDynamicToolResult = { result ->
        postActionScreen = capture.captureFresh()
        "$result\n\nCurrent screen:\n<screen>"
      },
    )

    val result = runBlocking {
      registry.getTool("dynamicCaptureTest").executeUnsafe(DynamicCaptureTestTool())
    }
    val nextRequestScreen = provider()

    assertThat(result).isEqualTo("executed\n\nCurrent screen:\n<screen>")
    assertThat(nextRequestScreen).isSameInstanceAs(postActionScreen)
    assertThat(captures).isEqualTo(2)
  }

  /**
   * The scripted tool has already run when the decorator captures, so a failed capture must hand
   * back the tool's own result, not fail a completed action the agent might then retry. The next
   * request re-captures.
   */
  @Test
  fun `a failed post-action capture returns the bare tool result`() {
    var captures = 0
    val capture = SharedScreenStateCapture {
      captures++
      if (captures == 2) error("hierarchy timed out")
      FakeScreenState()
    }
    val provider = capture.asProvider()
    val requestScreen = provider()

    val result = appendPostActionScreen("executed", capture)
    val nextRequestScreen = provider()

    assertThat(result).isEqualTo("executed")
    assertThat(nextRequestScreen).isNotSameInstanceAs(requestScreen)
    assertThat(captures).isEqualTo(3)
  }

  /**
   * Every dispatched tool's result is composed here, including host-local scripted tools. The
   * action has already happened, so a capture that throws must still yield the tool's result
   * (with the loop nudge, if any) instead of failing the call.
   */
  @Test
  fun `a dispatched tool result survives a failed post-action capture`() {
    val capture = SharedScreenStateCapture { error("hierarchy timed out") }

    val plain = composeToolDispatchResult("Executed signIn: Success", loopNudge = null, capture = capture)
    val nudged = composeToolDispatchResult("Executed signIn: Success", loopNudge = "You repeated this.", capture = capture)

    assertThat(plain).isEqualTo("Executed signIn: Success")
    assertThat(nudged).isEqualTo("You repeated this.\n\nExecuted signIn: Success")
  }

  @Test
  fun `a dispatched tool result carries the nudge then the fresh screen`() {
    val capture = SharedScreenStateCapture {
      object : ScreenState by FakeScreenState() {
        override val viewHierarchyTextRepresentation: String = "<screen>"
      }
    }

    val result = composeToolDispatchResult("Executed tap: Success", loopNudge = "Stuck?", capture = capture)

    assertThat(result).isEqualTo("Stuck?\n\nExecuted tap: Success\n\nCurrent screen:\n<screen>")
  }

  @OptIn(InternalAgentToolsApi::class)
  @Test
  fun `dynamic tool added by surface refresh invalidates the snapshot`() {
    var captures = 0
    val capture = SharedScreenStateCapture { captures++; FakeScreenState() }
    val provider = capture.asProvider()
    val requestScreen = provider()
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "dynamic-only",
        toolClasses = emptySet(),
      ),
    )
    val liveRegistry = ToolRegistry {}
    repo.addDynamicTools(listOf(DynamicCaptureTestRegistration))

    refreshKoogToolSurface(
      toolRepo = repo,
      liveRegistry = liveRegistry,
      toolDispatcher = { error("class-backed dispatcher must not run") },
      trailblazeToolContextProvider = { error("dynamic test tool does not need a context") },
      afterDynamicToolExecution = capture::clear,
    )
    runBlocking {
      liveRegistry.getTool("dynamicCaptureTest").executeUnsafe(DynamicCaptureTestTool())
    }
    val nextRequestScreen = provider()

    assertThat(captures).isEqualTo(2)
    assertThat(nextRequestScreen).isNotSameInstanceAs(requestScreen)
  }

  /**
   * A scripted tool the model calls goes through the agent's dispatch like a class-backed one, since
   * that dispatch is what logs it for recordings and reports. The dispatcher attaches the screen
   * itself, so neither dynamic-tool hook runs.
   */
  @OptIn(InternalAgentToolsApi::class)
  @Test
  fun `host-local dynamic tool is dispatched through the agent`() {
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "dynamic-only",
        toolClasses = emptySet(),
      ),
    )
    repo.addDynamicTools(listOf(HostLocalTestRegistration))
    val dispatched = mutableListOf<TrailblazeTool>()
    val registry = repo.asToolRegistryWithDynamicToolHook(
      toolDispatcher = { tool -> dispatched += tool; "dispatched" },
      trailblazeToolContextProvider = { error("a dispatched tool does not need this context") },
      afterDynamicToolExecution = { error("the dispatcher handles the screen") },
      decorateDynamicToolResult = { error("the dispatcher handles the screen") },
    )

    val tool = HostLocalTestTool()
    val result = runBlocking { registry.getTool("hostLocalTest").executeUnsafe(tool) }

    assertThat(result).isEqualTo("dispatched")
    assertThat(dispatched).containsExactly(tool)
  }

  @Test
  fun `a host-local tool starts with no screen so its context captures on first read`() {
    var captures = 0
    val provider = SharedScreenStateCapture { captures++; FakeScreenState() }.asProvider()

    assertThat(dispatchScreenState(HostLocalTestTool(), provider)).isNull()
    assertThat(captures).isEqualTo(0)
    assertThat(dispatchScreenState(DynamicCaptureTestTool(), provider)).isNotNull()
    assertThat(captures).isEqualTo(1)
  }

  @Serializable
  private class HostLocalTestTool : HostLocalExecutableTrailblazeTool {
    override val advertisedToolName: String get() = "hostLocalTest"

    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
      error("only the agent's dispatch runs this tool")
  }

  private object HostLocalTestRegistration : DynamicTrailblazeToolRegistration {
    override val name = ToolName("hostLocalTest")
    override val trailblazeDescriptor = TrailblazeToolDescriptor(name = name.toolName)

    override fun buildKoogTool(
      trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
    ): TrailblazeKoogTool<out TrailblazeTool> = TrailblazeKoogTool(
      argsSerializer = HostLocalTestTool.serializer(),
      descriptor = ToolDescriptor(name = name.toolName, description = "test"),
      executeTool = { error("the registry must route this call to the dispatcher") },
    )

    override fun decodeToolCall(argumentsJson: String): TrailblazeTool = HostLocalTestTool()
  }

  @Serializable
  private class DynamicCaptureTestTool : TrailblazeTool

  private object DynamicCaptureTestRegistration : DynamicTrailblazeToolRegistration {
    override val name = ToolName("dynamicCaptureTest")
    override val trailblazeDescriptor = TrailblazeToolDescriptor(name = name.toolName)

    override fun buildKoogTool(
      trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
    ): TrailblazeKoogTool<out TrailblazeTool> = TrailblazeKoogTool(
      argsSerializer = DynamicCaptureTestTool.serializer(),
      descriptor = ToolDescriptor(name = name.toolName, description = "test"),
      executeTool = { "executed" },
    )

    override fun decodeToolCall(argumentsJson: String): TrailblazeTool = DynamicCaptureTestTool()
  }
}
