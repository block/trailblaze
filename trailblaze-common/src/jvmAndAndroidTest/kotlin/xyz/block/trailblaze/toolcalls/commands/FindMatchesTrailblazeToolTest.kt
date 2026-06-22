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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FindMatchesTrailblazeToolTest {

  @BeforeTest
  fun shrinkPollInterval() {
    // Keep the polling tests off real wall-clock sleeps — the production 300ms interval would add
    // ~hundreds of ms per poll. The wait-budget (timeoutMs) is still honored via the real clock.
    FindMatchesTrailblazeTool.pollIntervalMs = 1L
  }

  @AfterTest
  fun cleanup() {
    repeat(SnapshotCache.frameDepth()) { SnapshotCache.popFrame() }
    FindMatchesTrailblazeTool.pollIntervalMs = FindMatchesTrailblazeTool.DEFAULT_POLL_INTERVAL_MS
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

  // -- timeoutMs polling (wait-until-visible) --

  /**
   * Context whose `screenStateProvider` returns a FRESH state each call, wrapping whatever
   * [treeProvider] yields that call — lets a poll test model a screen that changes between
   * captures. The `screenState` field is a throwaway; the polling path reads only the provider.
   */
  private fun pollingCtx(treeProvider: () -> TrailblazeNode?): TrailblazeToolExecutionContext =
    TrailblazeToolExecutionContext(
      screenState = FakeScreenState(androidNode(nodeId = 99)),
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
      screenStateProvider = { FakeScreenState(treeProvider()) },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )

  @Test
  fun `timeoutMs polls the live hierarchy until the selector appears, then returns the match`() = runBlocking {
    val withSubmit = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Submit")))
    val withoutSubmit = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Loading")))
    val captureCount = intArrayOf(0)
    // First capture has no "Submit" (still loading); subsequent captures do — models a screen
    // that renders the target after the call begins.
    val context = pollingCtx { if (captureCount[0]++ == 0) withoutSubmit else withSubmit }

    val result = FindMatchesTrailblazeTool(selector = submitSelector(), timeoutMs = 5_000).execute(context)

    val matches = decodeMatches(result)
    assertEquals(1, matches.size, "should return the match once it appears")
    assertTrue(captureCount[0] >= 2, "should have re-captured while waiting; captured ${captureCount[0]}x")
  }

  @Test
  fun `timeoutMs returns empty success (not an error) when the selector never appears`() = runBlocking {
    // A real tree is present (resolution IS possible), the selector just never matches.
    val withoutSubmit = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Loading")))
    val context = pollingCtx { withoutSubmit }

    val result = FindMatchesTrailblazeTool(selector = submitSelector(), timeoutMs = 60).execute(context)

    // "Absent after the timeout" is a normal empty result for the caller's `length === 0` gate —
    // NOT a verification failure. This is the whole point of the probe vs a throwing assert.
    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(emptyList(), decodeMatches(result))
  }

  @Test
  fun `timeoutMs tolerates a transient null tree then returns the match once it resolves`() = runBlocking {
    // Pins the kdoc contract: a tree that is null on SOME polls but present on others (mid-
    // transition) is just "no match yet", NOT the persistent NoTreeEverSeen driver-mismatch. The
    // provider yields null first (capture mid-transition), then a tree with no match, then the
    // matching tree — the match must still be returned (Success), never the missing-tree error.
    val withSubmit = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Submit")))
    val withoutSubmit = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Loading")))
    val sequence: List<TrailblazeNode?> = listOf(null, withoutSubmit, withSubmit)
    val captureCount = intArrayOf(0)
    val context = pollingCtx {
      val i = captureCount[0]++
      sequence.getOrElse(i) { withSubmit }
    }

    val result = FindMatchesTrailblazeTool(selector = submitSelector(), timeoutMs = 5_000).execute(context)

    val matches = decodeMatches(result)
    assertEquals(1, matches.size, "transient null trees must not abort the poll; the match should still be found")
    assertTrue(captureCount[0] >= 3, "should have polled past the null + no-match captures; captured ${captureCount[0]}x")
  }

  @Test
  fun `timeoutMs returns the missing-tree error when the driver never produces a node tree`() = runBlocking {
    // A driver whose ScreenState never yields a trailblazeNodeTree (e.g. Maestro-only) can't
    // resolve a node selector at all. The polling path must surface the SAME error the
    // point-in-time path returns — not a misleading empty success that sends a scripted caller
    // down its "absent element" branch.
    val context = pollingCtx { null }

    val result = FindMatchesTrailblazeTool(selector = submitSelector(), timeoutMs = 60).execute(context)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("does not produce a TrailblazeNode tree"),
      "expected the missing-tree error, got: ${error.errorMessage}",
    )
  }

  @Test
  fun `timeoutMs polling emits exactly one trace span for the whole poll, not one per capture`() = runBlocking {
    // Regression guard for the per-poll span flood: the polling path must wrap the ENTIRE wait in a
    // single TrailblazeTracer span (emitted once by execute), NOT re-enter a traced resolve on every
    // re-capture. The selector never matches, so the poll re-captures many times within the budget —
    // yet only one `findMatches` span may be recorded.
    val withoutSubmit = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Loading")))
    val captureCount = intArrayOf(0)
    val context = pollingCtx {
      captureCount[0]++
      withoutSubmit
    }

    TrailblazeTracer.clear()
    FindMatchesTrailblazeTool(selector = submitSelector(), timeoutMs = 40).execute(context)

    assertTrue(captureCount[0] >= 2, "test must exercise multiple polls; captured ${captureCount[0]}x")
    val findMatchesEvents =
      Json.parseToJsonElement(TrailblazeTracer.exportJson()).jsonArray.filter {
        it.jsonObject["name"]?.jsonPrimitive?.content == "findMatches"
      }
    assertEquals(
      1,
      findMatchesEvents.size,
      "polling path must emit exactly one findMatches span across ${captureCount[0]} captures",
    )
  }

  @Test
  fun `non-positive timeoutMs performs a single immediate capture without polling`() = runBlocking {
    // kdoc contract for timeoutMs <= 0: one immediate live capture, no wait — the point-in-time
    // result routed through the polling path rather than an error.
    val withSubmit = androidNode(nodeId = 1, children = listOf(androidNode(nodeId = 2, text = "Submit")))

    val zeroCaptures = intArrayOf(0)
    val zeroResult =
      FindMatchesTrailblazeTool(selector = submitSelector(), timeoutMs = 0).execute(
        pollingCtx {
          zeroCaptures[0]++
          withSubmit
        },
      )
    assertEquals(1, decodeMatches(zeroResult).size, "timeoutMs=0 should still return the current match")
    assertEquals(1, zeroCaptures[0], "timeoutMs=0 must capture exactly once (no polling)")

    val negCaptures = intArrayOf(0)
    val negResult =
      FindMatchesTrailblazeTool(selector = submitSelector(), timeoutMs = -5).execute(
        pollingCtx {
          negCaptures[0]++
          withSubmit
        },
      )
    assertEquals(1, decodeMatches(negResult).size, "negative timeoutMs should still return the current match")
    assertEquals(1, negCaptures[0], "negative timeoutMs must capture exactly once (no polling)")
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
