package xyz.block.trailblaze.toolcalls.commands

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The load-bearing claim of `findSelectorMatches` is a COST claim — N selectors, one capture —
 * so most assertions here count provider invocations rather than inspecting matches. A version of
 * this tool that quietly captured per selector would still return correct answers and would be
 * worthless, so a test that only checked the answers could not fail on the defect the tool exists
 * to fix.
 */
class FindSelectorMatchesTrailblazeToolTest {

  @BeforeTest
  fun shrinkPollInterval() {
    // One cadence for both query tools, owned by the engine they share — which is also what keeps
    // these tests off real 300ms sleeps.
    SelectorQueryEngine.pollIntervalMs = 1L
  }

  @AfterTest
  fun cleanup() {
    repeat(SnapshotCache.frameDepth()) { SnapshotCache.popFrame() }
    SelectorQueryEngine.pollIntervalMs = SelectorQueryEngine.DEFAULT_POLL_INTERVAL_MS
  }

  // -- Fixtures --

  private fun androidNode(
    text: String? = null,
    nodeId: Long = 0,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nodeId,
    children = children,
    bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    driverDetail = DriverNodeDetail.AndroidAccessibility(text = text),
  )

  private fun selectorFor(textRegex: String) = TrailblazeNodeSelector.withMatch(
    DriverNodeMatch.AndroidAccessibility(textRegex = textRegex),
  )

  /** A root whose children carry exactly [texts], one node each. */
  private fun treeOf(vararg texts: String): TrailblazeNode = androidNode(
    nodeId = 1,
    children = texts.mapIndexed { i, t -> androidNode(nodeId = (i + 2).toLong(), text = t) },
  )

  private class FakeScreenState(
    val root: TrailblazeNode?,
    platform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    /** Null is "unknown", which every driver but Android accessibility reports. */
    override val droppedNodeFetches: Int? = null,
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

  /**
   * Context whose provider is called fresh every capture, counting invocations in [captureCount].
   * That counter is the subject of most of this suite.
   */
  private fun ctx(
    captureCount: IntArray = intArrayOf(0),
    provider: (Int) -> FakeScreenState,
  ): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = provider(0),
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
      val state = provider(captureCount[0])
      captureCount[0]++
      state
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  /** Fixed-screen context — the common case where the screen doesn't change between captures. */
  private fun staticCtx(state: FakeScreenState, captureCount: IntArray = intArrayOf(0)) =
    ctx(captureCount) { state }

  private fun decode(result: TrailblazeToolResult): List<List<MatchDescriptor>> {
    assertIs<TrailblazeToolResult.Success>(result)
    val payload = result.structuredContent
    assertNotNull(payload, "structuredContent must be populated")
    assertIs<JsonArray>(payload)
    return Json.decodeFromJsonElement(
      ListSerializer(ListSerializer(MatchDescriptor.serializer())),
      payload,
    )
  }

  // -- The cost claim --

  @Test
  fun `resolves every selector against a single capture`() = runBlocking {
    val captures = intArrayOf(0)
    val context = staticCtx(FakeScreenState(treeOf("Submit", "Cancel", "Item")), captures)

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(
          selectors = listOf(selectorFor("Submit"), selectorFor("Cancel"), selectorFor("Item")),
        ).execute(context)
      }
    }

    val matches = decode(result)
    assertEquals(3, matches.size)
    assertTrue(matches.all { it.size == 1 }, "each selector matches its own node")
    // The whole point. Three `findMatches` calls from a scripted tool would have captured three
    // times, because each scripting callback enters its own SnapshotCache frame.
    assertEquals(1, captures[0], "three selectors must cost exactly one capture")
  }

  @Test
  fun `a single poll costs one capture regardless of selector count`() = runBlocking {
    val captures = intArrayOf(0)
    // timeoutMs = 0 is one immediate live capture, no polling — so the count is exact and this
    // test can't be flaky on timing.
    val context = staticCtx(FakeScreenState(treeOf("Home")), captures)

    // Ten, not two: ten is the batch size this tool is meant to make ordinary, and the count has
    // to be independent of it. Resolving is pure computation against the captured tree —
    // measured at ~3ms for ten selectors over a 2,000-node tree, three orders of magnitude under
    // the capture it replaces — so the only number that can grow here is captures.
    val selectors = listOf(selectorFor("Home")) +
      (1..9).map { selectorFor("NoSuchScreen$it") }

    val result = FindSelectorMatchesTrailblazeTool(
      selectors = selectors,
      timeoutMs = 0,
    ).execute(context)

    assertEquals(10, decode(result).size)
    assertEquals(1, captures[0], "ten selectors on the polling path must still be one capture")
  }

  @Test
  fun `a wait drops the frame's pre-wait snapshot so a later query sees the new screen`() = runBlocking {
    val captures = intArrayOf(0)
    // Capture 0 is the screen before the wait; everything after it is the screen waited for.
    val context = ctx(captures) { n ->
      FakeScreenState(if (n == 0) treeOf("Splash") else treeOf("Home"))
    }

    val afterWait = SnapshotCache.withFrame {
      runBlocking {
        // Populates the frame with the pre-wait screen, as any query earlier in the batch would.
        FindSelectorMatchesTrailblazeTool(selectors = listOf(selectorFor("Splash")))
          .execute(context)
        FindSelectorMatchesTrailblazeTool(
          selectors = listOf(selectorFor("Home")),
          timeoutMs = 1_000,
        ).execute(context)
        // Same frame, point-in-time — the call that read the PRE-wait tree before the engine
        // started invalidating on the way out of a wait. Neither query tool can invalidate for
        // itself: both are read-only, which is exactly what tells the dispatcher not to.
        FindSelectorMatchesTrailblazeTool(selectors = listOf(selectorFor("Home")))
          .execute(context)
      }
    }

    assertEquals(
      1,
      decode(afterWait).single().size,
      "a point-in-time query after a wait must see the screen the wait ended on",
    )
    assertEquals(3, captures[0], "the third query must re-capture rather than reuse the stale frame")
  }

  // -- The alignment contract --

  @Test
  fun `results are index-aligned and unmatched selectors get empty lists`() = runBlocking {
    val context = staticCtx(FakeScreenState(treeOf("Submit")))

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(
          selectors = listOf(selectorFor("Nope"), selectorFor("Submit"), selectorFor("AlsoNope")),
        ).execute(context)
      }
    }

    val matches = decode(result)
    // Position, not presence, is how a caller reads its answer — an unmatched selector must
    // occupy its slot rather than being dropped, or every later index shifts.
    assertEquals(3, matches.size)
    assertEquals(emptyList(), matches[0])
    assertEquals(1, matches[1].size)
    assertEquals(emptyList(), matches[2])
  }

  @Test
  fun `duplicate selectors each get their own result`() = runBlocking {
    // Index alignment (rather than a map keyed by selector) is what makes this well-defined; a
    // keyed result would silently collapse the two.
    val context = staticCtx(FakeScreenState(treeOf("Submit")))

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(
          selectors = listOf(selectorFor("Submit"), selectorFor("Submit")),
        ).execute(context)
      }
    }

    val matches = decode(result)
    assertEquals(2, matches.size)
    assertEquals(matches[0], matches[1])
  }

  @Test
  fun `answers each selector exactly as findMatches would`() = runBlocking {
    // Pins the shared-resolver contract: a caller batching to save captures must not be changing
    // its predicates at the same time. If this fails, SelectorMatchResolution has drifted from
    // one of its two callers.
    val screen = FakeScreenState(treeOf("Submit", "Item", "Item"))
    val selectors = listOf(selectorFor("Submit"), selectorFor("Item"), selectorFor("Missing"))

    val batched = decode(
      SnapshotCache.withFrame {
        runBlocking { FindSelectorMatchesTrailblazeTool(selectors = selectors).execute(staticCtx(screen)) }
      },
    )

    selectors.forEachIndexed { index, selector ->
      val single = SnapshotCache.withFrame {
        runBlocking { FindMatchesTrailblazeTool(selector = selector).execute(staticCtx(screen)) }
      }
      assertIs<TrailblazeToolResult.Success>(single)
      val payload = single.structuredContent
      assertNotNull(payload)
      assertIs<JsonArray>(payload)
      val expected = Json.decodeFromJsonElement(ListSerializer(MatchDescriptor.serializer()), payload)
      assertEquals(expected, batched[index], "selector $index must match findMatches' answer")
    }
  }

  @Test
  fun `an empty selector list is rejected`() = runBlocking<Unit> {
    val result = FindSelectorMatchesTrailblazeTool(selectors = emptyList())
      .execute(staticCtx(FakeScreenState(treeOf("Submit"))))

    // A capture with nothing to ask of it is a caller bug, not a trivially-empty success — the
    // latter would let a data-driven caller silently stop checking anything.
    assertIs<TrailblazeToolResult.Error>(result)
  }

  // -- The race primitive --

  @Test
  fun `polling returns as soon as ANY selector matches, answering the rest from that frame`() = runBlocking {
    val captures = intArrayOf(0)
    // Neither screen is up on the first poll; "Home" renders on the second.
    val context = ctx(captures) { n ->
      if (n == 0) FakeScreenState(treeOf("Loading")) else FakeScreenState(treeOf("Home"))
    }

    val result = FindSelectorMatchesTrailblazeTool(
      selectors = listOf(selectorFor("Wizard"), selectorFor("Home")),
      timeoutMs = 5_000,
    ).execute(context)

    val matches = decode(result)
    assertEquals(emptyList(), matches[0], "the wizard never rendered")
    assertEquals(1, matches[1].size, "home did, and that ends the wait")
    // The far end of the budget is never reached — a caller racing two screens pays only until
    // one of them shows up.
    assertEquals(2, captures[0], "should stop capturing the poll one of them matched")
  }

  @Test
  fun `polling returns all-empty when no selector ever matches`() = runBlocking {
    val result = FindSelectorMatchesTrailblazeTool(
      selectors = listOf(selectorFor("Wizard"), selectorFor("Home")),
      timeoutMs = 30,
    ).execute(staticCtx(FakeScreenState(treeOf("Loading"))))

    // "None of these appeared" is a normal answer for a conditional flow, not an error.
    val matches = decode(result)
    assertEquals(2, matches.size)
    assertTrue(matches.all { it.isEmpty() })
  }

  // -- Captures that lost nodes --

  @Test
  fun `re-captures rather than calling a selector absent from a capture that lost nodes`() = runBlocking {
    val captures = intArrayOf(0)
    val context = ctx(captures) { n ->
      if (n == 0) {
        // Holey tree, and "Submit" isn't in it — exactly what a caller would misread as absence.
        FakeScreenState(treeOf("Loading"), droppedNodeFetches = 2)
      } else {
        FakeScreenState(treeOf("Submit", "Home"), droppedNodeFetches = 0)
      }
    }

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(
          selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
        ).execute(context)
      }
    }

    val matches = decode(result)
    assertEquals(1, matches[0].size, "it was on screen; the first capture just lost it")
    assertEquals(1, matches[1].size)
    assertEquals(2, captures[0], "should have re-captured after the capture that lost nodes")
  }

  @Test
  fun `trusts a capture that lost nodes when every selector matched in it`() = runBlocking {
    val captures = intArrayOf(0)
    val context = staticCtx(FakeScreenState(treeOf("Submit", "Home"), droppedNodeFetches = 7), captures)

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(
          selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
        ).execute(context)
      }
    }

    assertTrue(decode(result).all { it.size == 1 })
    // Presence in a tree with holes is still presence, so there is nothing to confirm and no
    // reason to pay for a second capture.
    assertEquals(1, captures[0], "all-matched needs no re-capture even from a partial tree")
  }

  @Test
  fun `refuses to report absence when every capture lost nodes`() = runBlocking {
    val captures = intArrayOf(0)
    val context = staticCtx(FakeScreenState(treeOf("Loading"), droppedNodeFetches = 2), captures)

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(
          selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
        ).execute(context)
      }
    }

    // An empty Success here is the defect: the caller branches on "absent" having never seen a
    // tree that could prove it.
    assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(captures[0] > 1, "should have re-captured before giving up; captured ${captures[0]} time(s)")
  }

  @Test
  fun `one selector matching does not license absence for the others out of a holey capture`() = runBlocking {
    val captures = intArrayOf(0)
    // "Home" is present, "Submit" is not, and the tree has holes — so the Submit answer is
    // exactly as unknowable as it would be in a call that matched nothing.
    val context = staticCtx(FakeScreenState(treeOf("Home"), droppedNodeFetches = 3), captures)

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(
          selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
        ).execute(context)
      }
    }

    assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(captures[0] > 1, "should have re-captured for the unanswered selector")
  }

  @Test
  fun `a match out of a holey capture does not end the wait while another selector is unanswered`() = runBlocking {
    val captures = intArrayOf(0)
    val context = ctx(captures) { n ->
      if (n == 0) {
        // "Home" matched, "Submit" didn't, and the tree lost nodes — the frame answers one
        // selector truthfully and the other with an absence nobody measured.
        FakeScreenState(treeOf("Home"), droppedNodeFetches = 3)
      } else {
        FakeScreenState(treeOf("Home", "Submit"), droppedNodeFetches = 0)
      }
    }

    val result = FindSelectorMatchesTrailblazeTool(
      selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
      timeoutMs = 5_000,
    ).execute(context)

    // Returning the first frame is the defect: it is the polling path silently dropping the
    // strictness the point-in-time path enforces two tests up. The budget was already there, so
    // spending one more poll to get a frame worth trusting costs the caller nothing it hadn't
    // agreed to.
    val matches = decode(result)
    assertEquals(1, matches[0].size, "Submit was on screen; the first capture just lost it")
    assertEquals(1, matches[1].size)
    assertEquals(2, captures[0], "should have kept polling past the holey frame")
  }

  @Test
  fun `a wait that only ever sees holey captures with an unanswered selector fails`() = runBlocking<Unit> {
    // Same shape as above, except the device never recovers. Answering "Home matched, Submit
    // absent" here would be the caller's absent branch fired on no evidence.
    val result = FindSelectorMatchesTrailblazeTool(
      selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
      timeoutMs = 30,
    ).execute(staticCtx(FakeScreenState(treeOf("Home"), droppedNodeFetches = 3)))

    assertIs<TrailblazeToolResult.Error>(result)
  }

  @Test
  fun `an earlier complete poll does not license a holey mixed frame at timeout`() = runBlocking<Unit> {
    // Poll 0 is COMPLETE and matches nothing, which sets the sticky "a trustworthy look happened
    // in this window" flag. Every later poll is holey AND has picked up "Home", so the frame the
    // wait ends on is mixed and its empty slot for "Submit" was never measured. The sticky flag
    // settles an all-empty window; it must not settle this one, because the new match is itself
    // evidence the screen moved since the complete look. Guarding only the early return let this
    // frame out at timeout.
    val context = ctx(intArrayOf(0)) { n ->
      if (n == 0) {
        FakeScreenState(treeOf("Loading"), droppedNodeFetches = 0)
      } else {
        FakeScreenState(treeOf("Home"), droppedNodeFetches = 3)
      }
    }

    val result = FindSelectorMatchesTrailblazeTool(
      selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
      timeoutMs = 30,
    ).execute(context)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    // And the refusal must not claim every capture was partial, because one wasn't — that wording
    // would send a reader off debugging a wedged app when the app is merely answering unevenly.
    assertTrue(
      error.errorMessage.contains("answering intermittently"),
      "should name the intermittent condition it actually saw: ${error.errorMessage}",
    )
  }

  /**
   * `timeoutMs = 0` is the polling path with no budget: one live capture, no retry. So it reaches
   * the refusal never having had a chance at a complete capture, and the two other arms both
   * misadvise it — there is no `timeoutMs` to add that it didn't pass, and one look is no evidence
   * that the app stopped answering.
   */
  @Test
  fun `a zero timeoutMs is told to pass a real budget rather than to go investigate the app`() = runBlocking<Unit> {
    val captures = intArrayOf(0)
    val result = FindSelectorMatchesTrailblazeTool(
      selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
      timeoutMs = 0,
    ).execute(staticCtx(FakeScreenState(treeOf("Loading"), droppedNodeFetches = 2), captures))

    val msg = assertIs<TrailblazeToolResult.Error>(result).errorMessage
    assertEquals(1, captures[0], "a zero budget buys exactly one capture")
    assertTrue(msg.contains("never retries"), "the message should say one capture was all it took: $msg")
    assertFalse(
      msg.contains("investigate why the app stopped"),
      "one capture is no evidence the app stopped answering: $msg",
    )
    assertFalse(msg.contains("Pass a `timeoutMs`"), "it passed one; the value is the problem: $msg")
  }

  @Test
  fun `an earlier complete poll still settles absence when the final poll is merely holey`() = runBlocking {
    // Why the fix above NARROWS the sticky flag instead of removing it: here every look agreed on
    // absence, so one unlucky holey final poll must not fail a wait that earlier complete polls
    // already answered. Deleting the sticky arm outright would turn this into an error.
    val context = ctx(intArrayOf(0)) { n ->
      if (n == 0) {
        FakeScreenState(treeOf("Loading"), droppedNodeFetches = 0)
      } else {
        FakeScreenState(treeOf("Loading"), droppedNodeFetches = 3)
      }
    }

    val result = FindSelectorMatchesTrailblazeTool(
      selectors = listOf(selectorFor("Submit"), selectorFor("Home")),
      timeoutMs = 30,
    ).execute(context)

    val matches = decode(result)
    assertEquals(0, matches[0].size, "absence is the measured answer here, not an error")
    assertEquals(0, matches[1].size)
  }

  @Test
  fun `the summary reports how many captures it actually took`() = runBlocking {
    val context = ctx { n ->
      if (n == 0) {
        FakeScreenState(treeOf("Loading"), droppedNodeFetches = 2)
      } else {
        FakeScreenState(treeOf("Submit"), droppedNodeFetches = 0)
      }
    }

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(selectors = listOf(selectorFor("Submit"))).execute(context)
      }
    }

    // The tool's whole claim is "N selectors, one capture", so a hard-coded "from 1 capture" in
    // the summary would keep asserting it on the paths that legitimately re-capture — and this
    // line is what a reader checks the claim against.
    assertIs<TrailblazeToolResult.Success>(result)
    val summary = result.message.orEmpty()
    assertTrue(
      summary.contains("from 2 capture(s)"),
      "summary must report the real capture count, got: $summary",
    )
  }

  @Test
  fun `an unknown-completeness capture answers immediately, as every non-Android driver does`() = runBlocking {
    val captures = intArrayOf(0)
    // droppedNodeFetches = null is "unknown", which reads as complete. This is the regression
    // guard for drivers that cannot measure it: one capture, no retries, empties returned.
    val context = staticCtx(FakeScreenState(treeOf("Loading"), droppedNodeFetches = null), captures)

    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(selectors = listOf(selectorFor("Submit"))).execute(context)
      }
    }

    assertEquals(listOf(emptyList<MatchDescriptor>()), decode(result))
    assertEquals(1, captures[0])
  }

  // -- Driver mismatch --

  @Test
  fun `a driver that produces no node tree is an error, not an empty answer`() = runBlocking<Unit> {
    val result = SnapshotCache.withFrame {
      runBlocking {
        FindSelectorMatchesTrailblazeTool(selectors = listOf(selectorFor("Submit")))
          .execute(staticCtx(FakeScreenState(root = null, platform = TrailblazeDevicePlatform.IOS)))
      }
    }

    // Empty lists would send a scripted caller down its "absent element" branch on a driver that
    // structurally cannot answer the question.
    assertIs<TrailblazeToolResult.Error>(result)
  }

  // -- Recorded YAML --

  @Test
  fun `decodes the recorded YAML shape a trail uses to wait for a screen`() {
    // A recorded trail reaches this tool by name, and `selectors` is the first LIST-of-selector
    // parameter any tool has taken — a shape no other tool's round-trip covers. The sample-app
    // `loading/wait-for-content` trail is exactly this, so a decode regression here breaks a
    // shipped example rather than only a scripted caller.
    val yaml = """
      config: {}
      trail:
        - step: recorded
          recording:
            android:
              - findSelectorMatches:
                  selectors:
                    - androidAccessibility:
                        textRegex: Content Loaded
                  timeoutMs: 30000
    """.trimIndent()

    val tool = createTrailblazeYaml(setOf(FindSelectorMatchesTrailblazeTool::class))
      .decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.single()
      .trailblazeTool as FindSelectorMatchesTrailblazeTool

    assertEquals(listOf(selectorFor("Content Loaded")), tool.selectors)
    assertEquals(30_000L, tool.timeoutMs)
  }
}
