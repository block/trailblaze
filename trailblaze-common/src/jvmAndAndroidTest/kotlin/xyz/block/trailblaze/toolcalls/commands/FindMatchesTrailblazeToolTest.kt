package xyz.block.trailblaze.toolcalls.commands

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.MatchDescriptor
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FindMatchesTrailblazeToolTest {

  @AfterTest
  fun cleanup() {
    repeat(SnapshotCache.frameDepth()) { SnapshotCache.popFrame() }
  }

  // -- Fixtures --

  private fun androidNode(
    text: String? = null,
    contentDescription: String? = null,
    resourceId: String? = null,
    bounds: TrailblazeNode.Bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    nodeId: Long = 0,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nodeId,
    children = children,
    bounds = bounds,
    driverDetail = DriverNodeDetail.AndroidAccessibility(
      text = text,
      contentDescription = contentDescription,
      resourceId = resourceId,
    ),
  )

  private fun submitSelector() = TrailblazeNodeSelector.withMatch(
    DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
  )

  private fun itemSelector() = TrailblazeNodeSelector.withMatch(
    DriverNodeMatch.AndroidAccessibility(textRegex = "Item"),
  )

  private class FakeScreenState(
    val root: TrailblazeNode?,
    platform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
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

  private fun ctx(tree: TrailblazeNode, providerCount: IntArray = intArrayOf(0)): TrailblazeToolExecutionContext {
    val state = FakeScreenState(tree)
    return TrailblazeToolExecutionContext(
      screenState = state,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now())
      },
      screenStateProvider = {
        providerCount[0]++
        state
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }

  private fun decodeMatches(result: TrailblazeToolResult): List<MatchDescriptor> {
    assertIs<TrailblazeToolResult.Success>(result)
    val payload = result.structuredContent
    assertNotNull(payload, "structuredContent must be populated")
    assertIs<JsonArray>(payload)
    return Json.decodeFromJsonElement(
      kotlinx.serialization.builtins.ListSerializer(MatchDescriptor.serializer()),
      payload,
    )
  }

  // -- Resolution cases --

  @Test
  fun `empty match returns empty descriptor list`() = runBlocking {
    val root = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(nodeId = 2, text = "A"),
        androidNode(nodeId = 3, text = "B"),
      ),
    )
    val context = ctx(root)

    val result = FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)

    val matches = decodeMatches(result)
    assertEquals(emptyList(), matches)
    assertEquals(
      "findMatches: 0 matches",
      (result as TrailblazeToolResult.Success).message,
    )
  }

  @Test
  fun `unique match returns single descriptor with bounds and identity`() = runBlocking {
    val target = androidNode(
      nodeId = 7,
      text = "Submit",
      contentDescription = "submit_button",
      resourceId = "com.example:id/submit",
      bounds = TrailblazeNode.Bounds(10, 20, 110, 80),
    )
    val root = androidNode(
      nodeId = 1,
      children = listOf(
        androidNode(nodeId = 2, text = "Cancel"),
        target,
      ),
    )
    val context = ctx(root)

    val result = FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)

    val matches = decodeMatches(result)
    assertEquals(1, matches.size)
    val match = matches.single()
    assertEquals(listOf(1), match.indexPath)
    assertEquals(TrailblazeNode.Bounds(10, 20, 110, 80), match.bounds)
    assertEquals("Submit", match.matchedText)
    assertEquals("submit_button", match.accessibilityId)
    assertEquals("com.example:id/submit", match.resourceId)
    assertEquals(
      "findMatches: 1 match",
      (result as TrailblazeToolResult.Success).message,
    )
  }

  @Test
  fun `multiple matches returns deterministic top-to-bottom order`() = runBlocking {
    // Two siblings with the same text — resolver sorts top-to-bottom.
    val first = androidNode(
      nodeId = 5,
      text = "Item",
      bounds = TrailblazeNode.Bounds(0, 10, 100, 60),
    )
    val second = androidNode(
      nodeId = 6,
      text = "Item",
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val root = androidNode(nodeId = 1, children = listOf(first, second))
    val context = ctx(root)

    val result = FindMatchesTrailblazeTool(selector = itemSelector()).execute(context)

    val matches = decodeMatches(result)
    assertEquals(2, matches.size)
    assertEquals(10, matches[0].bounds?.top)
    assertEquals(100, matches[1].bounds?.top)
  }

  // -- Snapshot caching --

  @Test
  fun `findMatches reuses cached snapshot within a frame`() = runBlocking {
    val target = androidNode(nodeId = 2, text = "Submit")
    val root = androidNode(nodeId = 1, children = listOf(target))
    val providerCount = intArrayOf(0)
    val context = ctx(root, providerCount)

    SnapshotCache.withFrame {
      runBlocking {
        FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)
        FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)
        FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)
      }
    }

    assertEquals(1, providerCount[0], "screenStateProvider should be invoked exactly once across three queries in the same frame")
  }

  @Test
  fun `findMatches re-captures after SnapshotCache invalidation`() = runBlocking {
    val target = androidNode(nodeId = 2, text = "Submit")
    val root = androidNode(nodeId = 1, children = listOf(target))
    val providerCount = intArrayOf(0)
    val context = ctx(root, providerCount)

    SnapshotCache.withFrame {
      runBlocking {
        FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)
        assertEquals(1, providerCount[0])

        SnapshotCache.invalidateCurrent()
        FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)
      }
    }

    assertEquals(2, providerCount[0])
  }

  @Test
  fun `findMatches captures every call when there is no active frame`() = runBlocking {
    val target = androidNode(nodeId = 2, text = "Submit")
    val root = androidNode(nodeId = 1, children = listOf(target))
    val providerCount = intArrayOf(0)
    val context = ctx(root, providerCount)

    FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)
    FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)

    assertEquals(2, providerCount[0])
  }

  // -- Error paths --

  @Test
  fun `findMatches returns error when no screenStateProvider is on the context`() = runBlocking {
    val target = androidNode(nodeId = 2, text = "Submit")
    val root = androidNode(nodeId = 1, children = listOf(target))
    val baseContext = ctx(root)
    val contextWithoutProvider = TrailblazeToolExecutionContext(
      screenState = baseContext.screenState,
      traceId = null,
      trailblazeDeviceInfo = baseContext.trailblazeDeviceInfo,
      sessionProvider = baseContext.sessionProvider,
      screenStateProvider = null,
      trailblazeLogger = baseContext.trailblazeLogger,
      memory = baseContext.memory,
    )

    val result = FindMatchesTrailblazeTool(selector = submitSelector()).execute(contextWithoutProvider)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(error.errorMessage.contains("screenStateProvider"))
  }

  @Test
  fun `findMatches returns error when trailblazeNodeTree is null`() = runBlocking {
    // Maestro-only drivers populate `viewHierarchy` but leave `trailblazeNodeTree` null.
    // findMatches can't resolve a TrailblazeNodeSelector against that — surface a
    // structured error with the platform name so authors know what to fix.
    val noTreeState = FakeScreenState(root = null)
    val context = TrailblazeToolExecutionContext(
      screenState = noTreeState,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now())
      },
      screenStateProvider = { noTreeState },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )

    val result = FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("does not produce a TrailblazeNode tree"),
      "expected error message to name the missing-tree shape, got: ${error.errorMessage}",
    )
    assertTrue(
      error.errorMessage.contains("ANDROID"),
      "expected error message to include the platform name, got: ${error.errorMessage}",
    )
  }

  @Test
  fun `findMatches emits a trace event with the selector description`() = runBlocking {
    // Pin the TrailblazeTracer wrap added in pass-2. If a future change accidentally
    // removes the wrap or renames the `name`/`cat`, this test fails — the wrap is the
    // only path to session-level performance debugging for findMatches.
    val target = androidNode(nodeId = 2, text = "Submit")
    val root = androidNode(nodeId = 1, children = listOf(target))
    val context = ctx(root)

    TrailblazeTracer.clear()
    FindMatchesTrailblazeTool(selector = submitSelector()).execute(context)

    val events = Json.parseToJsonElement(TrailblazeTracer.exportJson()).jsonArray
    val findMatchesEvent = events.firstOrNull {
      it.jsonObject["name"]?.jsonPrimitive?.content == "findMatches"
    }
    assertNotNull(findMatchesEvent, "expected a `findMatches` trace event in the recorder")
    val obj = findMatchesEvent.jsonObject
    assertEquals(
      "FindMatchesTrailblazeTool",
      obj["cat"]?.jsonPrimitive?.content,
      "trace event's cat field should name the source tool",
    )
    val selectorArg = obj["args"]?.jsonObject?.get("selector")?.jsonPrimitive?.content
    assertNotNull(selectorArg, "trace event should carry the rendered selector description in args")
    assertTrue(selectorArg.contains("Submit"), "selector arg should include the textRegex; got: $selectorArg")
  }

  @Test
  fun `findMatches with selector having no driver match arm matches every node`() = runBlocking {
    // A selector with every driver-match arm null is degenerate — the resolver's
    // driverMatch getter returns null, and the resolver treats every node as a
    // candidate that fails no predicate. Pin the resulting shape (Success with
    // every node included, no structured error) so a future resolver refactor
    // doesn't silently change the contract this PR exposes to scripted-tool authors.
    val target = androidNode(nodeId = 2, text = "Submit")
    val root = androidNode(nodeId = 1, children = listOf(target))
    val context = ctx(root)

    val emptyArmSelector = TrailblazeNodeSelector() // every field null

    val result = FindMatchesTrailblazeTool(selector = emptyArmSelector).execute(context)

    assertIs<TrailblazeToolResult.Success>(result)
    val matches = decodeMatches(result)
    // No predicate is set, so the resolver returns every node — both root and the
    // single child are included.
    assertEquals(2, matches.size, "empty-arm selector should match every node in the tree")
  }
}
