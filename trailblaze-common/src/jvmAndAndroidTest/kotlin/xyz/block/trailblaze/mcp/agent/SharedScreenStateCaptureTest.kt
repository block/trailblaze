package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotSameInstanceAs
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
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
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
        yamlToolNames = emptySet(),
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
        yamlToolNames = emptySet(),
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
