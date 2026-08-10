package xyz.block.trailblaze.toolcalls.commands.memory

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.SnapshotCache
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Every case here runs the tool with a context that carries no LLM client and no
 * [xyz.block.trailblaze.utils.ElementComparator] — the capture succeeding under those conditions is
 * the zero-LLM property, enforced by construction rather than by counting calls.
 */
class RememberBySelectorTrailblazeToolTest {

  @AfterTest
  fun cleanup() {
    repeat(SnapshotCache.frameDepth()) { SnapshotCache.popFrame() }
  }

  // -- Fixtures --

  private fun androidNode(
    text: String? = null,
    resourceId: String? = null,
    nodeId: Long = 0,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nodeId,
    children = children,
    bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    driverDetail = DriverNodeDetail.AndroidAccessibility(text = text, resourceId = resourceId),
  )

  private fun selector(textRegex: String) = TrailblazeNodeSelector.withMatch(
    DriverNodeMatch.AndroidAccessibility(textRegex = textRegex),
  )

  private fun iosAxeNode(
    label: String? = null,
    value: String? = null,
    nodeId: Long = 0,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nodeId,
    children = children,
    bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    driverDetail = DriverNodeDetail.IosAxe(label = label, value = value),
  )

  private class FakeScreenState(
    val root: TrailblazeNode?,
    platform: TrailblazeDevicePlatform,
  ) : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = platform
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode? = root
    override val annotationElements: List<AnnotationElement>? = null
  }

  private fun ctx(
    tree: TrailblazeNode?,
    memory: AgentMemory = AgentMemory(),
    platform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  ): TrailblazeToolExecutionContext {
    val state = FakeScreenState(tree, platform)
    return TrailblazeToolExecutionContext(
      screenState = state,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = platform,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now())
      },
      screenStateProvider = { state },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = memory,
    )
  }

  private fun errorMessage(result: TrailblazeToolResult): String {
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    return result.errorMessage
  }

  // -- rememberTextBySelector --

  /**
   * Negative control: the screen carries two option rows with DIFFERENT labels, and only the
   * selected one may land in memory. An implementation that grabs the root, the first node, or any
   * node fails here — unlike a "nothing threw" assertion, which such an implementation would pass.
   */
  @Test
  fun `captures the text of the selected element, not a sibling`() = runBlocking {
    val memory = AgentMemory()
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(text = "Print at the end of the sale", nodeId = 2),
        androidNode(text = "Print when the order is ready", nodeId = 3),
      ),
    )

    val result = RememberTextBySelectorTrailblazeTool(
      nodeSelector = selector("Print when the order is ready"),
      variable = "currentOption",
    ).execute(ctx(tree, memory))

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals("Print when the order is ready", memory.variables["currentOption"])
  }

  /**
   * A Contacts-style row labels the field type and carries the datum in AXValue, so the AXe text
   * priority (label > value > title) answers "home" for a row the trail selected by its number.
   * Capturing the field the selector matched on is what keeps rememberNumberBySelector able to
   * parse a number out of a value that plainly contained one.
   */
  @Test
  fun `captures the AXe field the selector matched on, not the higher-priority label`() = runBlocking {
    val memory = AgentMemory()
    val tree = iosAxeNode(
      nodeId = 1,
      children = listOf(iosAxeNode(label = "home", value = "(555) 478-7672", nodeId = 2)),
    )

    val result = RememberTextBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.IosAxe(valueRegex = "(555) 478-7672"),
      ),
      variable = "phone",
    ).execute(ctx(tree, memory, TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals("(555) 478-7672", memory.variables["phone"])
  }

  /**
   * The same rule on another driver: Android resolves text before contentDescription, so a node
   * selected by its contentDescription must not be remembered as its text.
   */
  @Test
  fun `captures the Android field the selector matched on, not the higher-priority text`() = runBlocking {
    val memory = AgentMemory()
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          children = emptyList(),
          bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
          driverDetail = DriverNodeDetail.AndroidAccessibility(
            text = "home",
            contentDescription = "(555) 478-7672",
          ),
        ),
      ),
    )

    val result = RememberTextBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = "(555) 478-7672"),
      ),
      variable = "phone",
    ).execute(ctx(tree, memory))

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals("(555) 478-7672", memory.variables["phone"])
  }

  @Test
  fun `no match is an error and leaves memory untouched`() = runBlocking {
    val memory = AgentMemory()
    val tree = androidNode(nodeId = 1, children = listOf(androidNode(text = "Save", nodeId = 2)))

    val result = RememberTextBySelectorTrailblazeTool(
      nodeSelector = selector("Absent"),
      variable = "currentOption",
    ).execute(ctx(tree, memory))

    assertTrue(errorMessage(result).contains("no element matched"))
    assertNull(memory.variables["currentOption"])
  }

  /**
   * Ambiguity must fail loudly: picking a winner would make the captured value depend on resolver
   * traversal order, which is the non-determinism this tool exists to remove.
   */
  @Test
  fun `multiple matches is an error and leaves memory untouched`() = runBlocking {
    val memory = AgentMemory()
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(text = "Item", nodeId = 2),
        androidNode(text = "Item", nodeId = 3),
      ),
    )

    val result = RememberTextBySelectorTrailblazeTool(
      nodeSelector = selector("Item"),
      variable = "currentOption",
    ).execute(ctx(tree, memory))

    assertTrue(errorMessage(result).contains("matched 2 elements"))
    assertNull(memory.variables["currentOption"])
  }

  @Test
  fun `an element with no text is an error and leaves memory untouched`() = runBlocking {
    val memory = AgentMemory()
    val tree = androidNode(
      nodeId = 1,
      children = listOf(androidNode(resourceId = "app:id/option_row", nodeId = 2)),
    )

    val result = RememberTextBySelectorTrailblazeTool(
      nodeSelector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "app:id/option_row"),
      ),
      variable = "currentOption",
    ).execute(ctx(tree, memory))

    assertTrue(errorMessage(result).contains("no text to capture"))
    assertNull(memory.variables["currentOption"])
  }

  @Test
  fun `a missing nodeSelector is an error`() = runBlocking {
    val result = RememberTextBySelectorTrailblazeTool(variable = "currentOption")
      .execute(ctx(androidNode(nodeId = 1)))

    assertTrue(errorMessage(result).contains("requires `nodeSelector` to be non-null"))
  }

  @Test
  fun `a driver with no node tree is an error naming the platform`() = runBlocking {
    val result = RememberTextBySelectorTrailblazeTool(
      nodeSelector = selector("Save"),
      variable = "currentOption",
    ).execute(ctx(tree = null, platform = TrailblazeDevicePlatform.IOS))

    assertTrue(errorMessage(result).contains("platform=IOS"))
  }

  /**
   * A `--secret` key is a session-lifetime redaction promise, and the result message reaches logs
   * and the LLM-facing result surface — so the captured value must not ride back out in it.
   */
  @Test
  fun `a sensitive variable is redacted in the result message`() = runBlocking {
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "0000")
    val tree = androidNode(nodeId = 1, children = listOf(androidNode(text = "4821", nodeId = 2)))

    val result = RememberTextBySelectorTrailblazeTool(
      nodeSelector = selector("4821"),
      variable = "pin",
    ).execute(ctx(tree, memory))

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals("4821", memory.variables["pin"])
    assertTrue(result.message?.contains("[REDACTED]") == true)
    assertTrue(result.message?.contains("4821") == false)
  }

  // -- rememberNumberBySelector --

  @Test
  fun `captures the number out of the selected element's text`() = runBlocking {
    val memory = AgentMemory()
    val tree = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(text = "Subtotal $12.00", nodeId = 2),
        androidNode(text = "Total $42.50", nodeId = 3),
      ),
    )

    val result = RememberNumberBySelectorTrailblazeTool(
      nodeSelector = selector("Total .*"),
      variable = "total",
    ).execute(ctx(tree, memory))

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals("42.50", memory.variables["total"])
  }

  @Test
  fun `text with no number is an error and leaves memory untouched`() = runBlocking {
    val memory = AgentMemory()
    val tree = androidNode(nodeId = 1, children = listOf(androidNode(text = "Sold out", nodeId = 2)))

    val result = RememberNumberBySelectorTrailblazeTool(
      nodeSelector = selector("Sold out"),
      variable = "total",
    ).execute(ctx(tree, memory))

    assertTrue(errorMessage(result).contains("no number found"))
    assertNull(memory.variables["total"])
  }
}
