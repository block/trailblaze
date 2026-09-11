package xyz.block.trailblaze.waypoint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.waypoint.WaypointCondition
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointVariant
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins the four observable outcomes of [WaypointAssertion.poll] — Matched, NotMatched,
 * WaypointNotFound, NoScreenState — and the late-match polling case. These are the contract the
 * `assertWaypoint` tool renders into tool results.
 *
 * Time is advanced via the injected [now] lambda rather than a real clock so the test exercises the
 * timeout boundary precisely without `Thread.sleep`. `pollIntervalMs` is set tiny and the simulated
 * clock advances inside the [screenStateProvider] callback.
 */
class WaypointAssertionTest {

  @Test
  fun `matched waypoint returns Matched without exhausting timeout`() = runBlocking {
    val def = waypoint("test/more-tab-no-sheet", required = listOf("More", "Items"))
    val screen = fakeScreen(listOf("More", "Items"))
    val clock = 0L

    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 1_000L,
      pollIntervalMs = 50L,
      screenStateProvider = { screen },
      waypointResolver = { id -> if (id == def.id) def else null },
      now = { clock },
    )

    assertTrue(result is WaypointAssertion.Result.Matched, "expected Matched, got $result")
    assertEquals(def.id, result.definitionId)
    assertTrue(result.matchResult.matched)
  }

  @Test
  fun `mismatched waypoint after timeout returns NotMatched with missing-required diff`() = runBlocking {
    val def = waypoint(
      "test/more-tab-no-sheet",
      required = listOf("More", "Items"),
      forbidden = listOf("Switch mode"),
    )
    // Screen has "More" but not "Items", AND has the forbidden "Switch mode" — both arms fail.
    val screen = fakeScreen(listOf("More", "Switch mode"))
    var clock = 0L

    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 500L,
      pollIntervalMs = 100L,
      screenStateProvider = { screen },
      waypointResolver = { id -> if (id == def.id) def else null },
      // Advance the clock each evaluation so the loop terminates.
      now = { clock.also { clock += 150L } },
    )

    assertTrue(result is WaypointAssertion.Result.NotMatched, "expected NotMatched, got $result")
    assertEquals(def.id, result.definitionId)
    assertEquals(500L, result.timeoutMs)
    assertEquals(1, result.lastResult.missingRequired.size)
    assertEquals(1, result.lastResult.presentForbidden.size)
  }

  @Test
  fun `unknown waypoint id short-circuits to WaypointNotFound without polling`() = runBlocking {
    var providerCallCount = 0
    val result = WaypointAssertion.poll(
      waypointId = "test/does-not-exist",
      timeoutMs = 1_000L,
      screenStateProvider = {
        providerCallCount++
        fakeScreen(listOf("anything"))
      },
      waypointResolver = { null }, // every lookup misses
    )

    assertTrue(result is WaypointAssertion.Result.WaypointNotFound)
    assertEquals("test/does-not-exist", result.requestedId)
    assertEquals(0, providerCallCount, "screen state must not be queried when waypoint is unresolved")
  }

  @Test
  fun `persistent null screen state surfaces NoScreenState after timeout`() = runBlocking {
    val def = waypoint("test/some-screen", required = listOf("X"))
    var clock = 0L
    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 200L,
      pollIntervalMs = 50L,
      screenStateProvider = { null },
      waypointResolver = { id -> if (id == def.id) def else null },
      now = { clock.also { clock += 75L } },
    )

    assertTrue(result is WaypointAssertion.Result.NoScreenState)
    assertEquals(def.id, result.definitionId)
    assertEquals(200L, result.timeoutMs)
  }

  @Test
  fun `late match succeeds when screen settles before timeout`() = runBlocking {
    val def = waypoint("test/settled-screen", required = listOf("Items"))
    val initialScreen = fakeScreen(listOf("Loading"))
    val settledScreen = fakeScreen(listOf("Items"))
    var pollCount = 0
    var clock = 0L

    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 1_000L,
      pollIntervalMs = 50L,
      screenStateProvider = {
        pollCount++
        if (pollCount < 3) initialScreen else settledScreen
      },
      waypointResolver = { id -> if (id == def.id) def else null },
      now = { clock.also { clock += 60L } },
    )

    assertTrue(result is WaypointAssertion.Result.Matched, "expected Matched, got $result")
    assertEquals(3, pollCount, "poll should have run three times: 2 misses + 1 match")
  }

  // ---- TargetTemplateContext forwarding ----
  //
  // poll() forwards its `target` param to WaypointMatcher.match so waypoints whose selectors carry
  // `{{target.appId}}` expand correctly. These two tests pin the forwarding end-to-end: same
  // selector, same screen, only the target arg changes — Matched vs NotMatched is the proof that
  // the placeholder was substituted.

  @Test
  fun `templated waypoint matches when target supplies the appId`() = runBlocking {
    val def = templatedWaypoint(id = "test/templated", resourceIdSuffix = "foo")
    val screen = screenWithResourceIds(listOf("com.example.test:id/foo"))

    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 1_000L,
      pollIntervalMs = 50L,
      screenStateProvider = { screen },
      waypointResolver = { id -> if (id == def.id) def else null },
      now = { 0L },
      target = TargetTemplateContext(appId = "com.example.test"),
    )

    assertTrue(result is WaypointAssertion.Result.Matched, "expected Matched, got $result")
    assertEquals(def.id, result.definitionId)
  }

  @Test
  fun `templated waypoint short-circuits to NotMatched when target is null (fail-closed)`() = runBlocking {
    // Even though the screen has the resourceId that would match if the placeholder expanded, a
    // null target makes the matcher skip the whole definition with UNRESOLVED_TARGET_TEMPLATE —
    // which poll() surfaces as NotMatched (the waypoint wasn't satisfied within the timeout).
    // Critical for forbidden-only placeholders: without fail-closed, a forbidden selector that
    // doesn't expand would silently pass and let a templated waypoint be reported matched.
    val def = templatedWaypoint(id = "test/templated-null-target", resourceIdSuffix = "foo")
    val screen = screenWithResourceIds(listOf("com.example.test:id/foo"))
    var clock = 0L

    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 200L,
      pollIntervalMs = 100L,
      screenStateProvider = { screen },
      waypointResolver = { id -> if (id == def.id) def else null },
      now = { clock.also { clock += 150L } },
      // target = null (default)
    )

    assertTrue(
      result is WaypointAssertion.Result.NotMatched,
      "expected NotMatched (waypoint skipped via UNRESOLVED_TARGET_TEMPLATE), got $result",
    )
  }

  @Test
  fun `describeMismatch summarizes missing-required and present-forbidden entries`() {
    val def = waypoint(
      "test/screen",
      required = listOf("More", "Items"),
      forbidden = listOf("Switch mode"),
    )
    // Build the same shape poll() would produce, without re-running it.
    val matchResult = WaypointMatcher.match(
      definition = def,
      classifier = TrailblazeDeviceClassifier("android"),
      root = nodeTreeWithTexts(listOf("More", "Switch mode")),
    )
    val msg = WaypointAssertion.describeMismatch(
      WaypointAssertion.Result.NotMatched(def.id, matchResult, timeoutMs = 5_000L),
    )

    assertTrue(msg.contains("test/screen"), "msg should name the waypoint: $msg")
    assertTrue(msg.contains("5000ms"), "msg should include the timeout: $msg")
    assertTrue(msg.contains("Missing required"), "msg should call out missing-required: $msg")
    assertTrue(msg.contains("Present forbidden"), "msg should call out present-forbidden: $msg")
  }

  @Test
  fun `describeMismatch surfaces UNRESOLVED_TARGET_TEMPLATE skip with diagnostic hint`() {
    // Hand-build the skip-shaped match result so we don't depend on the matcher's wiring.
    val skippedResult = xyz.block.trailblaze.api.waypoint.WaypointMatchResult(
      definitionId = "test/templated-skip",
      matched = false,
      matchedRequired = emptyList(),
      missingRequired = emptyList(),
      presentForbidden = emptyList(),
      skipped = xyz.block.trailblaze.api.waypoint.WaypointMatchResult.SkipReason.UNRESOLVED_TARGET_TEMPLATE,
    )
    val msg = WaypointAssertion.describeMismatch(
      WaypointAssertion.Result.NotMatched(skippedResult.definitionId, skippedResult, timeoutMs = 200L),
    )

    // Without the skip-aware branch the message would be a bare "Waypoint X not matched after
    // 200ms." with no diagnostic — operators wouldn't know it's a missing target context issue
    // rather than a content miss. Pin the more useful message.
    assertTrue(msg.contains("UNRESOLVED_TARGET_TEMPLATE"), "msg should name the skip reason: $msg")
    assertTrue(msg.contains("{{target.appId}}"), "msg should reference the placeholder syntax: $msg")
    assertTrue(msg.contains("target context"), "msg should hint at the missing target wiring: $msg")
  }

  @Test
  fun `poll does not release on a capture that lost the nodes holding a forbidden element`() = runBlocking {
    // The screen still shows "Loading", but the first two captures never got that subtree from
    // the app, so nothing forbidden resolves in them. Releasing there is the bug: the step after
    // the waypoint would run against the loading screen. The third capture is complete and
    // "Loading" is really gone, which is the first moment the waypoint may match.
    val def = waypoint("test/loaded", required = listOf("Home"), forbidden = listOf("Loading"))
    val captures = listOf(
      partialScreen(listOf("Home")),
      partialScreen(listOf("Home")),
      completeScreen(listOf("Home")),
    )
    val seen = mutableListOf<Int>()
    var index = 0
    var clock = 0L

    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 1_000L,
      pollIntervalMs = 1L,
      screenStateProvider = { captures[index.coerceAtMost(captures.lastIndex)].also { seen += index; index++ } },
      waypointResolver = { id -> if (id == def.id) def else null },
      now = { clock.also { clock += 10L } },
    )

    assertTrue(result is WaypointAssertion.Result.Matched, "expected Matched, got $result")
    assertEquals(3, seen.size, "should have kept polling past both partial captures")
  }

  @Test
  fun `poll that only ever sees partial captures reports the capture problem, not a screen diff`() = runBlocking {
    val def = waypoint("test/loaded", required = listOf("Home"), forbidden = listOf("Loading"))
    val screen = partialScreen(listOf("Home"))
    var clock = 0L

    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 100L,
      pollIntervalMs = 10L,
      screenStateProvider = { screen },
      waypointResolver = { id -> if (id == def.id) def else null },
      now = { clock.also { clock += 60L } },
    )

    assertTrue(result is WaypointAssertion.Result.NotMatched, "expected NotMatched, got $result")
    assertEquals(
      xyz.block.trailblaze.api.waypoint.WaypointMatchResult.SkipReason.PARTIAL_CAPTURE,
      result.lastResult.skipped,
    )
    val msg = WaypointAssertion.describeMismatch(result)
    assertTrue(msg.contains("PARTIAL_CAPTURE"), "msg should name the skip reason: $msg")
    assertTrue(
      msg.contains("forbidden"),
      "msg should say which half of the waypoint contract could not be evaluated: $msg",
    )
    assertFalse(result.sawCompleteCapture, "no capture in this window was complete")
    // Here the strong claim IS earned: the poll saw nothing but partial captures.
    assertTrue(
      msg.contains("No capture in the whole 100ms window came back whole"),
      "msg should say the app never answered, and name the window it did not answer for: $msg",
    )
  }

  /**
   * `poll()` keeps only the LAST result, and one lossy capture is enough to produce a
   * PARTIAL_CAPTURE skip. So the skip reason alone cannot support "every capture in the window was
   * partial" — a reader told the app was blocked for the whole wait would go looking for a stall
   * that never happened. This pins the distinction: complete captures early, a partial one at the
   * moment the waypoint would have matched.
   */
  @Test
  fun `a partial capture after complete ones is reported as intermittent, not a whole-window stall`() = runBlocking {
    val def = waypoint("test/loaded", required = listOf("Home"), forbidden = listOf("Loading"))
    // The first two captures are whole and genuinely do not match — "Loading" is still there. The
    // third is the one that would have matched, and it lost nodes, so the matcher declines it.
    val captures = listOf(
      completeScreen(listOf("Home", "Loading")),
      completeScreen(listOf("Home", "Loading")),
      partialScreen(listOf("Home")),
    )
    var index = 0
    var clock = 0L

    val result = WaypointAssertion.poll(
      waypointId = def.id,
      timeoutMs = 100L,
      pollIntervalMs = 1L,
      screenStateProvider = { captures[index.coerceAtMost(captures.lastIndex)].also { index++ } },
      waypointResolver = { id -> if (id == def.id) def else null },
      now = { clock.also { clock += 40L } },
    )

    assertTrue(result is WaypointAssertion.Result.NotMatched, "expected NotMatched, got $result")
    assertEquals(
      xyz.block.trailblaze.api.waypoint.WaypointMatchResult.SkipReason.PARTIAL_CAPTURE,
      result.lastResult.skipped,
    )
    assertTrue(result.sawCompleteCapture, "the first two captures were complete")

    val msg = WaypointAssertion.describeMismatch(result)
    assertTrue(msg.contains("PARTIAL_CAPTURE"), "msg should still name the skip reason: $msg")
    assertTrue(
      msg.contains("Earlier captures in this window did come back whole"),
      "msg should say the block was intermittent: $msg",
    )
    // The claim the skip reason cannot support must not appear.
    assertFalse(
      msg.contains("No capture in the whole"),
      "msg must not claim the app never answered when it demonstrably did: $msg",
    )
  }

  // ---- fixtures ----

  private fun partialScreen(texts: List<String>): ScreenState = screenWithTexts(texts, droppedNodeFetches = 4)

  private fun completeScreen(texts: List<String>): ScreenState = screenWithTexts(texts, droppedNodeFetches = 0)

  private fun screenWithTexts(texts: List<String>, droppedNodeFetches: Int?): ScreenState =
    object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val droppedNodeFetches: Int? = droppedNodeFetches
      override val trailblazeNodeTree: TrailblazeNode = nodeTreeWithTexts(texts)
    }

  private fun waypoint(
    id: String,
    required: List<String> = emptyList(),
    forbidden: List<String> = emptyList(),
  ): WaypointDefinition = WaypointDefinition(
    id = id,
    byClassifier = mapOf(
      "android" to WaypointVariant(
        required = required.map { text ->
          WaypointCondition(
            selector = TrailblazeNodeSelector(
              androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = text),
            ),
          )
        },
        forbidden = forbidden.map { text ->
          WaypointCondition(
            selector = TrailblazeNodeSelector(
              androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = text),
            ),
          )
        },
      ),
    ),
  )

  /**
   * Builds a waypoint whose only required selector is a templated resourceIdRegex —
   * `^{{target.appId}}:id/<suffix>$`. The matcher expands the placeholder against the caller's
   * [TargetTemplateContext] (or leaves it literal if no context is supplied).
   */
  private fun templatedWaypoint(id: String, resourceIdSuffix: String): WaypointDefinition =
    WaypointDefinition(
      id = id,
      byClassifier = mapOf(
        "android" to WaypointVariant(
          required = listOf(
            WaypointCondition(
              selector = TrailblazeNodeSelector(
                androidAccessibility = DriverNodeMatch.AndroidAccessibility(
                  resourceIdRegex = "^{{target.appId}}:id/$resourceIdSuffix$",
                ),
              ),
            ),
          ),
        ),
      ),
    )

  private fun screenWithResourceIds(resourceIds: List<String>): ScreenState = object : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode = TrailblazeNode(
      nodeId = 1,
      children = resourceIds.mapIndexed { i, rid ->
        TrailblazeNode(
          nodeId = (i + 2).toLong(),
          driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = rid),
        )
      },
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
  }

  private fun nodeTreeWithTexts(texts: List<String>): TrailblazeNode = TrailblazeNode(
    nodeId = 1,
    children = texts.mapIndexed { i, text ->
      TrailblazeNode(
        nodeId = (i + 2).toLong(),
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = text),
      )
    },
    driverDetail = DriverNodeDetail.AndroidAccessibility(),
  )

  private fun fakeScreen(texts: List<String>): ScreenState = object : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode = nodeTreeWithTexts(texts)
  }
}
