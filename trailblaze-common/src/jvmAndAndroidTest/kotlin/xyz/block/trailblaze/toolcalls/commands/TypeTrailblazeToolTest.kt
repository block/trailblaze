package xyz.block.trailblaze.toolcalls.commands

import kotlinx.datetime.Clock
import maestro.orchestra.Command
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `type` is the LLM's only typing tool. The contract: the LLM sees the text and an optional ref,
 * never a selector, and the recording holds `inputText` — with the selector `tap` would have
 * recorded when a ref was given, and never the ref itself.
 */
class TypeTrailblazeToolTest {

  @Test
  fun `the LLM sees text and an optional ref, and inputText is hidden from it`() {
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools()
    assertEquals(TypeTrailblazeTool::class, resolver.resolveOrNull("type"))
    assertTrue(
      TypeTrailblazeTool::class in TrailblazeToolSetCatalog.entryToolClasses("core_interaction"),
    )

    val annotation = TypeTrailblazeTool::class.trailblazeToolClassAnnotation()
    assertTrue(annotation.surfaceToLlm)
    assertEquals(false, annotation.isRecordable, "the recording is the inputText it hands off to")

    val descriptor = assertNotNull(TypeTrailblazeTool::class.toKoogToolDescriptor())
    assertEquals(listOf("text"), descriptor.requiredParameters.map { it.name })
    assertEquals(
      listOf("ref", "reasoning", "hideKeyboardAfter", "clearFirst"),
      descriptor.optionalParameters.map { it.name },
    )
    assertNull(InputTextTrailblazeTool::class.toKoogToolDescriptor(), "the LLM types with `type`, not inputText")
  }

  @Test
  fun `records inputText with the selector tap would have recorded for the same ref`() {
    val context = contextWithTree(
      platform = TrailblazeDevicePlatform.ANDROID,
      tree = root(
        DriverNodeDetail.AndroidAccessibility(),
        field("e1", DriverNodeDetail.AndroidAccessibility(text = "Email", isEditable = true)),
      ),
    )
    val tapSelector = assertIs<TapOnByElementSelector>(
      TapTrailblazeTool(ref = "e1").toExecutableTrailblazeTools(context).single(),
    ).nodeSelector

    val recorded = TypeTrailblazeTool(
      ref = "e1",
      text = "a@b.co",
      reasoning = "type the email",
      hideKeyboardAfter = false,
      clearFirst = true,
    ).toExecutableTrailblazeTools(context)

    assertEquals(
      listOf(
        InputTextTrailblazeTool(
          text = "a@b.co",
          reasoning = "type the email",
          hideKeyboardAfter = false,
          selector = assertNotNull(tapSelector),
          clearFirst = true,
        ),
      ),
      recorded,
    )
  }

  @Test
  fun `a ref that tap records as a coordinate tap stays that tap, then plain inputText`() {
    // An iOS node with nothing to anchor a selector on: tap records a coordinate tap.
    val context = contextWithTree(
      platform = TrailblazeDevicePlatform.IOS,
      tree = root(DriverNodeDetail.IosMaestro(accessibilityText = "Form"), field("n1", DriverNodeDetail.IosMaestro())),
    )

    val recorded = TypeTrailblazeTool(ref = "n1", text = "hello", clearFirst = true)
      .toExecutableTrailblazeTools(context)

    assertEquals(2, recorded.size)
    assertIs<TapOnPointTrailblazeTool>(recorded[0])
    val type = assertIs<InputTextTrailblazeTool>(recorded[1])
    assertEquals("hello", type.text)
    assertNull(type.selector)
    assertTrue(type.clearFirst)
  }

  @Test
  fun `with no ref, records plain inputText into the focused field`() {
    val context = contextWithTree(
      platform = TrailblazeDevicePlatform.ANDROID,
      tree = root(DriverNodeDetail.AndroidAccessibility()),
    )

    assertEquals(
      listOf(InputTextTrailblazeTool(text = "hi", reasoning = "greet", hideKeyboardAfter = false, clearFirst = true)),
      TypeTrailblazeTool(text = "hi", reasoning = "greet", hideKeyboardAfter = false, clearFirst = true)
        .toExecutableTrailblazeTools(context),
    )
  }

  @Test
  fun `an unknown ref fails the way tap does`() {
    val context = contextWithTree(
      platform = TrailblazeDevicePlatform.ANDROID,
      tree = root(DriverNodeDetail.AndroidAccessibility()),
    )

    val error = assertFailsWith<TrailblazeToolExecutionException> {
      TypeTrailblazeTool(ref = "zz9", text = "x").toExecutableTrailblazeTools(context)
    }
    assertTrue("Element ref 'zz9' not found" in error.message.orEmpty(), error.message)
  }

  private var nextId = 1L

  private fun field(ref: String, detail: DriverNodeDetail) = TrailblazeNode(
    nodeId = nextId++,
    ref = ref,
    bounds = TrailblazeNode.Bounds(100, 200, 300, 260),
    driverDetail = detail,
  )

  private fun root(detail: DriverNodeDetail, vararg children: TrailblazeNode) = TrailblazeNode(
    nodeId = nextId++,
    bounds = TrailblazeNode.Bounds(0, 0, 1000, 1000),
    children = children.toList(),
    driverDetail = detail,
  )

  private fun contextWithTree(
    platform: TrailblazeDevicePlatform,
    tree: TrailblazeNode,
  ): TrailblazeToolExecutionContext {
    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId = "t", trailblazeDevicePlatform = platform),
      trailblazeDriverType = when (platform) {
        TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
        else -> TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
      },
      widthPixels = 1000,
      heightPixels = 1000,
    )
    val agent = object : MaestroTrailblazeAgent(
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = { deviceInfo },
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("t"), startTime = Clock.System.now())
      },
    ) {
      override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?) =
        TrailblazeToolResult.Success()
    }
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1000
      override val deviceHeight: Int = 1000
      override val viewHierarchy = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform = platform
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val trailblazeNodeTree: TrailblazeNode? = tree
    }
    return TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = deviceInfo,
      sessionProvider = agent.sessionProvider,
      trailblazeLogger = agent.trailblazeLogger,
      memory = AgentMemory(),
      maestroTrailblazeAgent = agent,
    )
  }
}
