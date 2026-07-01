package xyz.block.trailblaze.cli.shortcut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.waypoint.WaypointCondition
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.cli.androidWaypoint
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Unit tests for the deterministic v1 shortcut analyzer. Confirms:
 *  1. (A->B) transitions with `minSupport`+ sessions and a dominant action fingerprint
 *     produce one proposal per edge.
 *  2. Below-floor support is skipped with a typed reason.
 *  3. Disagreeing action fingerprints across sessions short-circuit the proposal.
 *  4. The proposalKey is stable across runs (cross-week dedupe).
 *  5. `synthesizeToolBody` rejects unsupported action types (returns null).
 *  6. Scroll / Swipe / BackPress / InputText / HideKeyboard get the right ToolBody.
 *  7. Tap synthesis returns null when the hit-test produces only an index-fallback.
 */
class ShortcutProposerTest {

  @Test
  fun `analyze emits one proposal per (A,B) edge meeting min-support`() {
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val waypointB = waypointById("trailmap/to", resourceId = "com.example:id/to_btn")
    val sessions = (1..3).map { idx ->
      listOf(
        stepAt(sessionId = "s$idx", screen = screenWith("com.example:id/from_btn"), action = AgentDriverAction.Scroll(forward = true)),
        stepAt(sessionId = "s$idx", screen = screenWith("com.example:id/to_btn"), action = null),
      )
    }
    val analysis = ShortcutProposer.analyze(
      sessions = sessions,
      waypoints = listOf(waypointA, waypointB),
    )
    assertEquals(1, analysis.proposals.size, "expected one proposal for the single edge")
    val p = analysis.proposals.first()
    assertEquals("trailmap/from", p.fromWaypointId)
    assertEquals("trailmap/to", p.toWaypointId)
    assertEquals(3, p.supportSessions)
    assertTrue(p.toolBody is ShortcutProposer.ToolBody.Scroll)
    assertEquals(true, (p.toolBody as ShortcutProposer.ToolBody.Scroll).forward)
  }

  @Test
  fun `analyze skips edges below min-support`() {
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val waypointB = waypointById("trailmap/to", resourceId = "com.example:id/to_btn")
    // Only two supporting sessions → below default min-support of 3.
    val sessions = (1..2).map { idx ->
      listOf(
        stepAt(sessionId = "s$idx", screen = screenWith("com.example:id/from_btn"), action = AgentDriverAction.BackPress),
        stepAt(sessionId = "s$idx", screen = screenWith("com.example:id/to_btn"), action = null),
      )
    }
    val analysis = ShortcutProposer.analyze(
      sessions = sessions,
      waypoints = listOf(waypointA, waypointB),
    )
    assertTrue(analysis.proposals.isEmpty())
    assertEquals(1, analysis.skipped.size)
    assertTrue(
      analysis.skipped.first().reason.contains("min-support"),
      "skip reason should name the floor: got `${analysis.skipped.first().reason}`",
    )
  }

  @Test
  fun `analyze skips edges where action fingerprints disagree`() {
    // 4 sessions: 2 do Scroll(forward=true), 2 do BackPress. 50/50 split fails the
    // default 2/3 agreement floor.
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val waypointB = waypointById("trailmap/to", resourceId = "com.example:id/to_btn")
    val sessions = listOf(
      listOf(
        stepAt("s1", screenWith("com.example:id/from_btn"), AgentDriverAction.Scroll(forward = true)),
        stepAt("s1", screenWith("com.example:id/to_btn"), null),
      ),
      listOf(
        stepAt("s2", screenWith("com.example:id/from_btn"), AgentDriverAction.Scroll(forward = true)),
        stepAt("s2", screenWith("com.example:id/to_btn"), null),
      ),
      listOf(
        stepAt("s3", screenWith("com.example:id/from_btn"), AgentDriverAction.BackPress),
        stepAt("s3", screenWith("com.example:id/to_btn"), null),
      ),
      listOf(
        stepAt("s4", screenWith("com.example:id/from_btn"), AgentDriverAction.BackPress),
        stepAt("s4", screenWith("com.example:id/to_btn"), null),
      ),
    )
    val analysis = ShortcutProposer.analyze(
      sessions = sessions,
      waypoints = listOf(waypointA, waypointB),
    )
    assertTrue(analysis.proposals.isEmpty())
    assertEquals(1, analysis.skipped.size)
    assertTrue(
      analysis.skipped.first().reason.contains("agreement"),
      "skip reason should call out fingerprint disagreement: ${analysis.skipped.first().reason}",
    )
  }

  @Test
  fun `proposalKey is stable across runs`() {
    val a = ShortcutProposer.proposalKey("trailmap/from", "trailmap/to", "abc123")
    val b = ShortcutProposer.proposalKey("trailmap/from", "trailmap/to", "abc123")
    assertEquals(a, b)
    assertEquals("shortcut|trailmap/from|trailmap/to|abc123", a)
  }

  @Test
  fun `generateShortcutId encodes from-to in slug`() {
    val id = ShortcutProposer.generateShortcutId("trailmap/android/drawer_open", "trailmap/android/settings_screen")
    assertEquals("auto-drawer_open__to__settings_screen", id)
  }

  @Test
  fun `synthesizeToolBody returns Scroll for AgentDriverAction Scroll`() {
    val body = ShortcutProposer.synthesizeToolBody(
      AgentDriverAction.Scroll(forward = false),
      screenWith("com.example:id/x"),
    )
    assertTrue(body is ShortcutProposer.ToolBody.Scroll)
    assertEquals(false, (body as ShortcutProposer.ToolBody.Scroll).forward)
  }

  @Test
  fun `synthesizeToolBody returns Swipe with direction`() {
    val body = ShortcutProposer.synthesizeToolBody(
      AgentDriverAction.Swipe(direction = "UP", durationMs = 300L),
      screenWith("com.example:id/x"),
    )
    assertTrue(body is ShortcutProposer.ToolBody.Swipe)
    assertEquals("UP", (body as ShortcutProposer.ToolBody.Swipe).direction)
  }

  @Test
  fun `synthesizeToolBody returns PressBack for BackPress`() {
    val body = ShortcutProposer.synthesizeToolBody(
      AgentDriverAction.BackPress,
      screenWith("com.example:id/x"),
    )
    assertEquals(ShortcutProposer.ToolBody.PressBack, body)
  }

  @Test
  fun `synthesizeToolBody returns InputText for EnterText`() {
    val body = ShortcutProposer.synthesizeToolBody(
      AgentDriverAction.EnterText("hello"),
      screenWith("com.example:id/x"),
    )
    assertTrue(body is ShortcutProposer.ToolBody.InputText)
    assertEquals("hello", (body as ShortcutProposer.ToolBody.InputText).text)
  }

  @Test
  fun `synthesizeToolBody returns HideKeyboard for HideKeyboard`() {
    val body = ShortcutProposer.synthesizeToolBody(
      AgentDriverAction.HideKeyboard,
      screenWith("com.example:id/x"),
    )
    assertEquals(ShortcutProposer.ToolBody.HideKeyboard, body)
  }

  @Test
  fun `synthesizeToolBody returns null for LongPressPoint (v1 has no long-press body)`() {
    val body = ShortcutProposer.synthesizeToolBody(
      AgentDriverAction.LongPressPoint(x = 100, y = 200),
      screenWith("com.example:id/x"),
    )
    assertNull(body, "LongPressPoint must drop to null in v1 — routing through tap would lose semantic")
  }

  @Test
  fun `synthesizeToolBody returns null for unhandled action types`() {
    // LaunchApp is a session-lifecycle action, not a shortcut atom. v1 explicitly
    // drops these — the synthesizer should return null so the proposal is skipped.
    val body = ShortcutProposer.synthesizeToolBody(
      AgentDriverAction.LaunchApp(appId = "com.example"),
      screenWith("com.example:id/x"),
    )
    assertNull(body)
  }

  @Test
  fun `analyze yields stable proposal ordering across runs`() {
    // The proposer's docstring claims deterministic v1; pin it explicitly so a future
    // refactor that accidentally uses Map-iteration ordering or Set-based grouping
    // shows up as a test failure here.
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val waypointB = waypointById("trailmap/to", resourceId = "com.example:id/to_btn")
    val waypointC = waypointById("trailmap/other", resourceId = "com.example:id/other_btn")
    val sessions = (1..3).flatMap { idx ->
      listOf(
        listOf(
          stepAt("s$idx", screenWith("com.example:id/from_btn"), AgentDriverAction.Scroll(forward = true)),
          stepAt("s$idx", screenWith("com.example:id/to_btn"), null),
        ),
        listOf(
          stepAt("o$idx", screenWith("com.example:id/from_btn"), AgentDriverAction.BackPress),
          stepAt("o$idx", screenWith("com.example:id/other_btn"), null),
        ),
      )
    }
    val a = ShortcutProposer.analyze(sessions, listOf(waypointA, waypointB, waypointC)).proposals.map { it.proposalKey }
    val b = ShortcutProposer.analyze(sessions, listOf(waypointA, waypointB, waypointC)).proposals.map { it.proposalKey }
    assertEquals(a, b, "same input must yield same proposalKey ordering")
  }

  @Test
  fun `re-analyzing with surviving proposals applied yields zero new proposals`() {
    // Idempotence invariant on the analyzer + guard chain — applying surviving
    // proposals to a virtual trailmap must make the second pass empty. If this fails,
    // the analyzer isn't stable on its own output and we'd see auto-PR loops.
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val waypointB = waypointById("trailmap/to", resourceId = "com.example:id/to_btn")
    val sessions = (1..4).map { idx ->
      listOf(
        stepAt("s$idx", screenWith("com.example:id/from_btn"), AgentDriverAction.Scroll(forward = true)),
        stepAt("s$idx", screenWith("com.example:id/to_btn"), null),
      )
    }
    val first = ShortcutProposer.analyze(sessions, listOf(waypointA, waypointB))
    assertTrue(first.proposals.isNotEmpty(), "fixture should produce at least one proposal")
    val firstVerdict = ShortcutSiblingCollisionGuard.check(
      proposals = first.proposals,
      existingShortcuts = emptyList(),
    )
    val virtualTrailmap = firstVerdict.survived.map {
      ShortcutSiblingCollisionGuard.ExistingShortcut(
        from = it.fromWaypointId, to = it.toWaypointId, variant = null,
      )
    }
    val second = ShortcutProposer.analyze(sessions, listOf(waypointA, waypointB))
    val secondVerdict = ShortcutSiblingCollisionGuard.check(
      proposals = second.proposals,
      existingShortcuts = virtualTrailmap,
    )
    assertTrue(
      secondVerdict.survived.isEmpty(),
      "second pass must be empty after applying surviving proposals; got ${secondVerdict.survived}",
    )
  }

  @Test
  fun `synthesizeToolBody returns null when only the Global index fallback selector resolves`() {
    // Pin the load-bearing rejection in `synthesizeTap`: when the captured tree's tap
    // target has no stable identifying property (no resourceId, no text, no
    // contentDescription, etc.), TrailblazeNodeSelectorGenerator falls back to a
    // positional "Global index fallback" selector. The proposer must reject this — a
    // shortcut whose first tap is implicitly "tap the Nth node" doesn't survive layout
    // changes. The screen below has two identical, propertyless nodes so no unique
    // selector exists short of the index fallback.
    val children = listOf(
      TrailblazeNode(
        nodeId = 100,
        bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 100),
        driverDetail = DriverNodeDetail.AndroidAccessibility(),
      ),
      TrailblazeNode(
        nodeId = 101,
        bounds = TrailblazeNode.Bounds(left = 0, top = 100, right = 100, bottom = 200),
        driverDetail = DriverNodeDetail.AndroidAccessibility(),
      ),
    )
    val root = TrailblazeNode(
      nodeId = 1,
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 200),
      children = children,
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val annotatedScreenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 100
      override val deviceHeight: Int = 200
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeNodeTree: TrailblazeNode = root
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val pageContextSummary: String? = null
    }
    val body = ShortcutProposer.synthesizeToolBody(AgentDriverAction.TapPoint(x = 50, y = 50), screen)
    assertNull(body, "Tap on a propertyless node must be rejected (no stable selector); got $body")
  }

  @Test
  fun `fingerprint differs for selectors that differ on hierarchy or spatial fields`() {
    // Pin the canonicalSelector recursive walk: two selectors identical at the leaf
    // but differing in any hierarchy/spatial child must produce distinct fingerprints.
    // Earlier subset-only canonicalSelector collapsed these — pin the contract so a
    // regression that drops a recursive branch surfaces immediately.
    val leaf = TrailblazeNodeSelector(
      androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(
        textRegex = "^Settings$",
      ),
    )
    val plain = ShortcutProposer.ToolBody.TapOnElementBySelector(
      selector = leaf,
      selectorDescription = "Text",
    )
    val withContainsChild = ShortcutProposer.ToolBody.TapOnElementBySelector(
      selector = leaf.copy(
        containsChild = TrailblazeNodeSelector(
          androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(
            textRegex = "^child$",
          ),
        ),
      ),
      selectorDescription = "Text",
    )
    val withAbove = ShortcutProposer.ToolBody.TapOnElementBySelector(
      selector = leaf.copy(
        above = TrailblazeNodeSelector(
          androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(
            textRegex = "^above$",
          ),
        ),
      ),
      selectorDescription = "Text",
    )
    val fpPlain = ShortcutProposer.fingerprint(plain)
    val fpChild = ShortcutProposer.fingerprint(withContainsChild)
    val fpAbove = ShortcutProposer.fingerprint(withAbove)
    assertTrue(fpPlain != fpChild, "containsChild presence must change the fingerprint")
    assertTrue(fpPlain != fpAbove, "above presence must change the fingerprint")
    assertTrue(fpChild != fpAbove, "containsChild vs. above must produce distinct fingerprints")
  }

  @Test
  fun `analyze returns empty Analysis for empty sessions list`() {
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val analysis = ShortcutProposer.analyze(sessions = emptyList(), waypoints = listOf(waypointA))
    assertTrue(analysis.proposals.isEmpty())
    assertTrue(analysis.skipped.isEmpty())
  }

  @Test
  fun `analyze returns empty Analysis when waypoints list is empty (no labels assignable)`() {
    val sessions = (1..5).map { idx ->
      listOf(
        stepAt("s$idx", screenWith("com.example:id/from_btn"), AgentDriverAction.BackPress),
        stepAt("s$idx", screenWith("com.example:id/to_btn"), null),
      )
    }
    val analysis = ShortcutProposer.analyze(sessions = sessions, waypoints = emptyList())
    assertTrue(analysis.proposals.isEmpty(), "no waypoints to label → no labeled transitions")
    assertTrue(analysis.skipped.isEmpty())
  }

  @Test
  fun `analyze emits no proposals for single-step sessions (loop bound returns immediately)`() {
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val sessions = (1..5).map { idx ->
      // Only one step per session — `for (i in 0 until session.size - 1)` immediately
      // exits, no transition pairs to examine.
      listOf(stepAt("s$idx", screenWith("com.example:id/from_btn"), AgentDriverAction.BackPress))
    }
    val analysis = ShortcutProposer.analyze(sessions = sessions, waypoints = listOf(waypointA))
    assertTrue(analysis.proposals.isEmpty())
    assertTrue(analysis.skipped.isEmpty())
  }

  @Test
  fun `analyze emits proposal when agreement exactly meets the floor`() {
    // 3 sessions agree on Scroll, 0 disagree → 100% > 66.7%. Also test the boundary
    // case where exactly 2/3 sessions agree (the default floor).
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val waypointB = waypointById("trailmap/to", resourceId = "com.example:id/to_btn")
    val sessions = listOf(
      listOf(
        stepAt("s1", screenWith("com.example:id/from_btn"), AgentDriverAction.Scroll(forward = true)),
        stepAt("s1", screenWith("com.example:id/to_btn"), null),
      ),
      listOf(
        stepAt("s2", screenWith("com.example:id/from_btn"), AgentDriverAction.Scroll(forward = true)),
        stepAt("s2", screenWith("com.example:id/to_btn"), null),
      ),
      listOf(
        stepAt("s3", screenWith("com.example:id/from_btn"), AgentDriverAction.BackPress),
        stepAt("s3", screenWith("com.example:id/to_btn"), null),
      ),
    )
    val analysis = ShortcutProposer.analyze(
      sessions = sessions,
      waypoints = listOf(waypointA, waypointB),
      fingerprintAgreement = 2.0 / 3.0,
    )
    // Dominant action (Scroll) is supported by 2/3 = 66.7% which equals the floor.
    // Implementation uses `< fingerprintAgreement` so equality passes — assert the
    // proposal IS emitted.
    assertEquals(1, analysis.proposals.size, "exactly-at-floor agreement should pass; got skipped=${analysis.skipped}")
  }

  @Test
  fun `isIndexOnlyFallback is false for selectors with index plus a real matcher`() {
    // Boundary case: a selector that legitimately carries `index = N` AND a real
    // matcher (e.g. `androidAccessibility.textRegex`) is NOT an index-only fallback —
    // the index is a tiebreaker, not the sole identifying signal. Earlier subset
    // checks of `isIndexOnlyFallback` only covered the pure-fallback case; this pins
    // the contract that a future refactor dropping any of the per-field null checks
    // would silently misclassify mixed selectors as fallback and start rejecting
    // valid taps.
    val mixed = TrailblazeNodeSelector(
      index = 2,
      androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(
        textRegex = "^Foo$",
      ),
    )
    assertEquals(false, ShortcutProposer.isIndexOnlyFallback(mixed))

    // Confirm the pure-fallback case still IS classified — pin both sides of the
    // boundary so a careless refactor can't accidentally flip the polarity.
    val pure = TrailblazeNodeSelector(index = 3)
    assertEquals(true, ShortcutProposer.isIndexOnlyFallback(pure))
  }

  @Test
  fun `canonicalSelector and isIndexOnlyFallback see every TrailblazeNodeSelector field`() {
    // Insurance against the "new field added to TrailblazeNodeSelector without
    // updating these helpers" failure mode. Construct a selector with every reachable
    // non-default field set; (a) the canonical-selector fingerprint must reflect each
    // field (a distinct fingerprint vs. one with that field cleared), and (b)
    // `isIndexOnlyFallback` must return false on it (it's not fallback — every field
    // is populated).
    //
    // If a future field is added to TrailblazeNodeSelector but NOT added below, this
    // test still passes (the new field is silently absent from the maximal selector).
    // The more durable insurance is a positive-list KDoc comment + reviewer vigilance,
    // but this test still catches the common case of "field removed from the helpers
    // without removing it from the type."
    val descendantA = TrailblazeNodeSelector(
      androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(textRegex = "^da$"),
    )
    val descendantB = TrailblazeNodeSelector(
      androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(textRegex = "^db$"),
    )
    val anchor = TrailblazeNodeSelector(
      androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(textRegex = "^anchor$"),
    )
    val maximal = TrailblazeNodeSelector(
      androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(
        textRegex = "^leaf$",
        isClickable = true,
        inputType = 32,
        collectionItemRowIndex = 1,
        collectionItemColumnIndex = 2,
      ),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(textRegex = "^cc$"),
      ),
      childOf = TrailblazeNodeSelector(
        androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(textRegex = "^co$"),
      ),
      containsDescendants = listOf(descendantA, descendantB),
      above = anchor,
      below = anchor,
      leftOf = anchor,
      rightOf = anchor,
      index = 7,
    )
    // A maximal selector has identifying matchers + hierarchy + spatial children;
    // index is just one of many predicates. NOT an index-only fallback.
    assertEquals(false, ShortcutProposer.isIndexOnlyFallback(maximal))

    // Fingerprint sanity: dropping each major field shifts the fingerprint. Pin a
    // representative pair to catch the common "helper stopped recursing" regression.
    val fpMax = ShortcutProposer.fingerprint(
      ShortcutProposer.ToolBody.TapOnElementBySelector(selector = maximal, selectorDescription = "max"),
    )
    val fpNoDescendants = ShortcutProposer.fingerprint(
      ShortcutProposer.ToolBody.TapOnElementBySelector(
        selector = maximal.copy(containsDescendants = emptyList()),
        selectorDescription = "max",
      ),
    )
    val fpNoSpatial = ShortcutProposer.fingerprint(
      ShortcutProposer.ToolBody.TapOnElementBySelector(
        selector = maximal.copy(above = null, below = null, leftOf = null, rightOf = null),
        selectorDescription = "max",
      ),
    )
    val fpNoIndex = ShortcutProposer.fingerprint(
      ShortcutProposer.ToolBody.TapOnElementBySelector(
        selector = maximal.copy(index = null),
        selectorDescription = "max",
      ),
    )
    assertTrue(fpMax != fpNoDescendants, "dropping containsDescendants must change fp")
    assertTrue(fpMax != fpNoSpatial, "dropping spatial siblings must change fp")
    assertTrue(fpMax != fpNoIndex, "dropping index must change fp")
  }

  @Test
  fun `analyze drops transitions where label changes are spurious`() {
    // A single session with stepN matching A and stepN+1 matching A also (no transition)
    // should not produce a proposal.
    val waypointA = waypointById("trailmap/from", resourceId = "com.example:id/from_btn")
    val sessions = (1..5).map { idx ->
      listOf(
        stepAt("s$idx", screenWith("com.example:id/from_btn"), AgentDriverAction.BackPress),
        stepAt("s$idx", screenWith("com.example:id/from_btn"), null),
      )
    }
    val analysis = ShortcutProposer.analyze(
      sessions = sessions,
      waypoints = listOf(waypointA),
    )
    assertTrue(analysis.proposals.isEmpty(), "stepN -> stepN with same label is not a transition")
  }

  // ---------------- fixtures ----------------

  private fun waypointById(id: String, resourceId: String): WaypointDefinition {
    return androidWaypoint(
      id = id,
      required = listOf(
        WaypointCondition(
          selector = TrailblazeNodeSelector(
            androidAccessibility = xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility(
              resourceIdRegex = "^${Regex.escape(resourceId)}$",
            ),
          ),
          description = "",
        ),
      ),
      forbidden = emptyList(),
    )
  }

  private fun screenWith(vararg resourceIds: String): ScreenState {
    val children = resourceIds.mapIndexed { i, rid ->
      TrailblazeNode(
        nodeId = (1000 + i).toLong(),
        driverDetail = DriverNodeDetail.AndroidAccessibility(resourceId = rid),
      )
    }
    val root = TrailblazeNode(
      nodeId = 1,
      children = children,
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    return object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val annotatedScreenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeNodeTree: TrailblazeNode = root
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
      override val pageContextSummary: String? = null
    }
  }

  private fun stepAt(
    sessionId: String,
    screen: ScreenState,
    action: AgentDriverAction?,
  ): ShortcutProposer.SessionStepWithAction = ShortcutProposer.SessionStepWithAction(
    sessionId = sessionId,
    stepIndex = 0,
    stepId = "$sessionId-step",
    screen = screen,
    action = action,
  )
}
