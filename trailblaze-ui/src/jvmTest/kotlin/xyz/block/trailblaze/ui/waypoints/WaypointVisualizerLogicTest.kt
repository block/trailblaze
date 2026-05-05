package xyz.block.trailblaze.ui.waypoints

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry

/**
 * Locks down the pure logic that backs the waypoint visualizer:
 *   - `platformSegmentOrNull` — id parsing rules used by the platform dropdown.
 *   - `resolveOverlays` / `isSatisfied` — required/forbidden match semantics.
 *   - `matchStatusBadgeStyle` — label/color the per-entry status badge picks.
 *
 * These functions are pure and deterministic. The visualizer's overall correctness rests
 * on them, so they should not need a Compose harness to exercise.
 */
class WaypointVisualizerLogicTest {

  // ---------- platformSegmentOrNull ----------

  @Test
  fun platformSegmentOrNull_returnsNullForLocalNameOnly() {
    assertNull(def("clock-tab").platformSegmentOrNull())
  }

  @Test
  fun platformSegmentOrNull_returnsNullForTwoSegmentId() {
    // The shape used by simple packs like `clock/alarm-tab` — pack + local-name, no
    // platform segment. Dropdown stays hidden for these.
    assertNull(def("clock/alarm-tab").platformSegmentOrNull())
  }

  @Test
  fun platformSegmentOrNull_returnsSecondSegmentForThreePartId() {
    assertEquals("android", def("myapp/android/splash").platformSegmentOrNull())
    assertEquals("ios", def("myapp/ios/splash").platformSegmentOrNull())
  }

  @Test
  fun platformSegmentOrNull_returnsSecondSegmentForLongerIds() {
    // Anything past three segments is still treated as `<target>/<platform>/<rest...>`.
    assertEquals("ios", def("myapp/ios/onboarding/step1").platformSegmentOrNull())
    assertEquals(
      "android",
      def("myapp/android/settings/advanced/dev").platformSegmentOrNull(),
    )
  }

  @Test
  fun platformSegmentOrNull_handlesDegenerateInputs() {
    // Pin behavior on malformed ids so accidental changes to the split logic surface
    // here instead of silently misclassifying real waypoints.
    assertNull(def("").platformSegmentOrNull(), "empty id has no platform segment")
    assertNull(def("/").platformSegmentOrNull(), "single slash yields two empty parts → < 3")
    assertNull(def("x/").platformSegmentOrNull(), "trailing slash still only two parts")
    assertNull(def("/x").platformSegmentOrNull(), "leading slash still only two parts")
    // Three parts triggers the platform extraction even when segments are empty —
    // current behavior. If this ever needs to require non-blank segments, this test
    // is the seam to update.
    assertEquals("", def("//").platformSegmentOrNull(), "three empty parts → middle is empty")
    assertEquals("x", def("/x/y").platformSegmentOrNull(), "leading-empty three-part still slot 1")
  }

  // ---------- WaypointSelectorOverlay.isSatisfied ----------

  @Test
  fun isSatisfied_required_isTrueAtAndAboveMinCount() {
    assertTrue(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 1, matchedCount = 1, hasBounds = true)
        .isSatisfied(),
    )
    assertTrue(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 2, matchedCount = 5, hasBounds = true)
        .isSatisfied(),
    )
  }

  @Test
  fun isSatisfied_required_isFalseBelowMinCount() {
    assertFalse(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 1, matchedCount = 0, hasBounds = false)
        .isSatisfied(),
    )
    assertFalse(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 3, matchedCount = 2, hasBounds = true)
        .isSatisfied(),
    )
  }

  @Test
  fun isSatisfied_required_isFalseWhenMatchesHaveNoBounds() {
    // The structural-match-but-no-drawable-rectangle case. matchedCount alone says the
    // selector "matched", but with empty matchedBounds the user has no visual evidence
    // and the overlay refuses to claim satisfaction. The badge surfaces this distinctly
    // via `matchStatusBadgeStyle` — see the dedicated test below.
    assertFalse(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 1, matchedCount = 3, hasBounds = false)
        .isSatisfied(),
    )
    assertFalse(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 1, matchedCount = 1, hasBounds = false)
        .isSatisfied(),
    )
  }

  @Test
  fun isSatisfied_forbidden_isTrueOnlyWhenAbsent() {
    assertTrue(
      overlay(WaypointSelectorKind.FORBIDDEN, minCount = 1, matchedCount = 0, hasBounds = false)
        .isSatisfied(),
    )
    assertFalse(
      overlay(WaypointSelectorKind.FORBIDDEN, minCount = 1, matchedCount = 1, hasBounds = true)
        .isSatisfied(),
    )
    assertFalse(
      overlay(WaypointSelectorKind.FORBIDDEN, minCount = 1, matchedCount = 5, hasBounds = true)
        .isSatisfied(),
    )
  }

  // ---------- resolveOverlays ----------

  @Test
  fun resolveOverlays_buildsOneEntryPerRequiredAndForbiddenInOrder() {
    val tree = composeNode("root", children = listOf(composeNode("alpha"), composeNode("beta")))
    val overlays = resolveOverlays(
      tree = tree,
      required = listOf(
        composeRequired("alpha"),
        composeRequired("missing"),
      ),
      forbidden = listOf(composeRequired("beta")),
    )

    assertEquals(3, overlays.size)
    assertEquals(WaypointSelectorKind.REQUIRED, overlays[0].kind)
    assertEquals(0, overlays[0].entryIndex)
    assertEquals(WaypointSelectorKind.REQUIRED, overlays[1].kind)
    assertEquals(1, overlays[1].entryIndex)
    assertEquals(WaypointSelectorKind.FORBIDDEN, overlays[2].kind)
    assertEquals(0, overlays[2].entryIndex)
  }

  @Test
  fun resolveOverlays_singleMatchProducesOneBoundsAndCount() {
    val targetBounds = TrailblazeNode.Bounds(left = 10, top = 20, right = 30, bottom = 40)
    val tree = composeNode(
      tag = "root",
      children = listOf(composeNode("alpha", bounds = targetBounds)),
    )
    val overlays = resolveOverlays(
      tree = tree,
      required = listOf(composeRequired("alpha")),
      forbidden = emptyList(),
    )
    val overlay = overlays.single()
    assertEquals(1, overlay.matchedCount)
    assertEquals(listOf(targetBounds), overlay.matchedBounds)
    assertTrue(overlay.isSatisfied())
  }

  @Test
  fun resolveOverlays_multipleMatchesPopulateEveryBoundsEntry() {
    val a = TrailblazeNode.Bounds(0, 0, 10, 10)
    val b = TrailblazeNode.Bounds(20, 20, 30, 30)
    val tree = composeNode(
      tag = "root",
      children = listOf(
        composeNode("dup", bounds = a),
        composeNode("dup", bounds = b),
      ),
    )
    val overlays = resolveOverlays(
      tree = tree,
      required = listOf(composeRequired("dup", minCount = 2)),
      forbidden = emptyList(),
    )
    val overlay = overlays.single()
    assertEquals(2, overlay.matchedCount)
    assertTrue(overlay.matchedBounds.containsAll(listOf(a, b)))
    assertTrue(overlay.isSatisfied())
  }

  @Test
  fun resolveOverlays_noMatchProducesEmptyBoundsAndZeroCount() {
    val tree = composeNode("root", children = listOf(composeNode("alpha")))
    val overlays = resolveOverlays(
      tree = tree,
      required = listOf(composeRequired("missing")),
      forbidden = emptyList(),
    )
    val overlay = overlays.single()
    assertEquals(0, overlay.matchedCount)
    assertTrue(overlay.matchedBounds.isEmpty())
    assertFalse(overlay.isSatisfied())
  }

  @Test
  fun resolveOverlays_forbiddenIsSatisfiedWhenAbsent() {
    val tree = composeNode("root", children = listOf(composeNode("alpha")))
    val overlays = resolveOverlays(
      tree = tree,
      required = emptyList(),
      forbidden = listOf(composeRequired("missing")),
    )
    assertTrue(overlays.single().isSatisfied())
  }

  // ---------- matchStatusBadgeStyle ----------

  @Test
  fun matchStatusBadgeStyle_forbiddenPresentReportsCount() {
    val (label, color) = matchStatusBadgeStyle(
      overlay(WaypointSelectorKind.FORBIDDEN, minCount = 1, matchedCount = 3, hasBounds = true),
    )
    assertEquals("PRESENT (3)", label)
    assertEquals(WAYPOINT_MATCH_COLOR_BAD, color)
  }

  @Test
  fun matchStatusBadgeStyle_forbiddenAbsentIsGreen() {
    val (label, color) = matchStatusBadgeStyle(
      overlay(WaypointSelectorKind.FORBIDDEN, minCount = 1, matchedCount = 0, hasBounds = false),
    )
    assertEquals("ABSENT", label)
    assertEquals(WAYPOINT_MATCH_COLOR_OK, color)
  }

  @Test
  fun matchStatusBadgeStyle_requiredZeroMatchesIsBad() {
    val (label, color) = matchStatusBadgeStyle(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 1, matchedCount = 0, hasBounds = false),
    )
    assertEquals("NO MATCH", label)
    assertEquals(WAYPOINT_MATCH_COLOR_BAD, color)
  }

  @Test
  fun matchStatusBadgeStyle_requiredBelowMinShowsRatio() {
    val (label, color) = matchStatusBadgeStyle(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 3, matchedCount = 2, hasBounds = true),
    )
    assertEquals("2/3", label)
    assertEquals(WAYPOINT_MATCH_COLOR_BAD, color)
  }

  @Test
  fun matchStatusBadgeStyle_requiredAboveMinIsAmbiguous() {
    val (label, color) = matchStatusBadgeStyle(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 1, matchedCount = 4, hasBounds = true),
    )
    assertEquals("4 matches", label)
    assertEquals(WAYPOINT_MATCH_COLOR_AMBIGUOUS, color)
  }

  @Test
  fun matchStatusBadgeStyle_requiredExactMatchIsOk() {
    val (label, color) = matchStatusBadgeStyle(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 2, matchedCount = 2, hasBounds = true),
    )
    assertEquals("MATCH", label)
    assertEquals(WAYPOINT_MATCH_COLOR_OK, color)
  }

  @Test
  fun matchStatusBadgeStyle_requiredMatchedButNoBoundsIsAmbiguous() {
    // Pins the user-visible signal for the structural-match-but-no-drawable-rectangle
    // case: an explicit "MATCH (no bounds)" badge in the ambiguous (orange) color,
    // distinct from both NO MATCH and a confidently green MATCH. Catches the bug that
    // would otherwise show green for selectors the user can't see on the screenshot.
    val (label, color) = matchStatusBadgeStyle(
      overlay(WaypointSelectorKind.REQUIRED, minCount = 1, matchedCount = 4, hasBounds = false),
    )
    assertEquals("MATCH (no bounds)", label)
    assertEquals(WAYPOINT_MATCH_COLOR_AMBIGUOUS, color)
  }

  // ---------- formatMatchedStepsLabel ----------

  @Test
  fun formatMatchedStepsLabel_singleStepRendersInline() {
    assertEquals("matched @ 7", formatMatchedStepsLabel(listOf(7)))
  }

  @Test
  fun formatMatchedStepsLabel_handfulOfStepsRendersAsCommaList() {
    assertEquals("matched @ 1, 4, 9", formatMatchedStepsLabel(listOf(1, 4, 9)))
  }

  @Test
  fun formatMatchedStepsLabel_atLimitShowsAllNoOverflow() {
    // 5 matches the limit exactly — show all five, no overflow suffix.
    assertEquals(
      "matched @ 1, 2, 3, 4, 5",
      formatMatchedStepsLabel(listOf(1, 2, 3, 4, 5)),
    )
  }

  @Test
  fun formatMatchedStepsLabel_pastLimitTruncatesWithOverflowCount() {
    // 7 entries: first 5 inline, "+2 more" tail. Pin the exact suffix so the badge stays
    // glanceable at the list-row width.
    assertEquals(
      "matched @ 1, 2, 3, 4, 5 +2 more",
      formatMatchedStepsLabel(listOf(1, 2, 3, 4, 5, 6, 7)),
    )
  }

  @Test
  fun formatMatchedStepsLabel_preservesProvidedOrder() {
    // The renderer is order-preserving — the extractor records steps chronologically,
    // and the badge shouldn't re-sort them. A non-monotonic input proves the contract.
    assertEquals(
      "matched @ 9, 1, 4",
      formatMatchedStepsLabel(listOf(9, 1, 4)),
    )
  }

  @Test
  fun formatMatchedStepsLabel_emptyDoesNotThrow() {
    // Defensive — the composable gates on isNotEmpty, but a future caller may not. The
    // formatter must never throw on an empty list and should produce a sentinel that is
    // visibly "no data" rather than a stray "matched @".
    assertEquals("matched @ –", formatMatchedStepsLabel(emptyList()))
  }

  // ---------- WaypointMapLayout.compute ----------

  @Test
  fun mapLayout_emptyInputProducesEmptyLayout() {
    val out = WaypointMapLayout.compute(
      waypointIds = emptyList(),
      shortcuts = emptyList(),
      trailheads = emptyList(),
    )
    assertTrue(out.positions.isEmpty())
    assertEquals(0, out.layerCount)
    assertEquals(0, out.maxColumns)
  }

  @Test
  fun mapLayout_noShortcutsPutsAllNodesAtLayerZero() {
    // Authored-data-empty case (today's repo): every waypoint sits at layer 0, sorted
    // alphabetically across columns. Pin both the layer and the column so a future
    // change to the sort or the initialization can't silently drift the layout.
    val out = WaypointMapLayout.compute(
      waypointIds = listOf("c", "a", "b"),
      shortcuts = emptyList(),
      trailheads = emptyList(),
    )
    assertEquals(WaypointMapLayout.GridPosition(layer = 0, column = 0), out.positions["a"])
    assertEquals(WaypointMapLayout.GridPosition(layer = 0, column = 1), out.positions["b"])
    assertEquals(WaypointMapLayout.GridPosition(layer = 0, column = 2), out.positions["c"])
    assertEquals(1, out.layerCount)
    assertEquals(3, out.maxColumns)
  }

  @Test
  fun mapLayout_simpleChainAssignsIncreasingLayers() {
    // a → b → c. Longest-path layering: layer(a)=0, layer(b)=1, layer(c)=2.
    val out = WaypointMapLayout.compute(
      waypointIds = listOf("a", "b", "c"),
      shortcuts = listOf(
        shortcut(from = "a", to = "b"),
        shortcut(from = "b", to = "c"),
      ),
      trailheads = emptyList(),
    )
    assertEquals(0, out.positions.getValue("a").layer)
    assertEquals(1, out.positions.getValue("b").layer)
    assertEquals(2, out.positions.getValue("c").layer)
    assertEquals(3, out.layerCount)
    // Each layer has one node, so max column count is 1.
    assertEquals(1, out.maxColumns)
  }

  @Test
  fun mapLayout_pickLongestPathOverShortest() {
    // Diamond: a → b → d AND a → d. Longest path to d is 2 hops via b, so layer(d) must
    // pick the longer path. This is the load-bearing property — without it, edges would
    // overshoot their target row and cross unnecessarily.
    val out = WaypointMapLayout.compute(
      waypointIds = listOf("a", "b", "d"),
      shortcuts = listOf(
        shortcut(from = "a", to = "b"),
        shortcut(from = "b", to = "d"),
        shortcut(from = "a", to = "d"),
      ),
      trailheads = emptyList(),
    )
    assertEquals(0, out.positions.getValue("a").layer)
    assertEquals(1, out.positions.getValue("b").layer)
    assertEquals(2, out.positions.getValue("d").layer, "longest path wins on diamonds")
  }

  @Test
  fun mapLayout_danglingShortcutReferencesAreIgnored() {
    // YAML may reference a waypoint id that's been removed but not yet cleaned up. The
    // layout pass must skip such references; the renderer surfaces them separately.
    // Without this check, the iteration would still run but produce a layer for an
    // absent waypoint, which would confuse downstream column assignment.
    val out = WaypointMapLayout.compute(
      waypointIds = listOf("a"),
      shortcuts = listOf(shortcut(from = "a", to = "missing")),
      trailheads = emptyList(),
    )
    assertEquals(WaypointMapLayout.GridPosition(layer = 0, column = 0), out.positions["a"])
    // "missing" must NOT appear in the output map.
    assertTrue("missing" !in out.positions)
  }

  @Test
  fun mapLayout_trailheadsDoNotShiftWaypointLayers() {
    // Trailheads have no `from` — they target a waypoint via the virtual "outside"
    // anchor that renders above the grid. The grid layout itself must therefore ignore
    // trailheads entirely: a trailhead pointing at `home` should NOT push `home` from
    // layer 0 to layer 1 (which would also push every shortcut chain one row down for
    // no visual benefit). This pins that the trailhead arg is layout-inert.
    val out = WaypointMapLayout.compute(
      waypointIds = listOf("home", "settings"),
      shortcuts = listOf(shortcut(from = "home", to = "settings")),
      trailheads = listOf(
        TrailheadDisplayItem(id = "boot", description = null, to = "home"),
      ),
    )
    assertEquals(0, out.positions.getValue("home").layer)
    assertEquals(1, out.positions.getValue("settings").layer)
  }

  @Test
  fun mapLayout_cyclesAreBoundedByIterationCap() {
    // a ↔ b. A naive longest-path walker grows layers without bound on a cycle. The
    // iteration cap prevents that — both nodes land at finite layers within
    // `waypointIds.size` rounds. We don't pin the exact layers (they're SCC-arbitrary)
    // but we do pin "the algorithm terminates" and "both nodes are positioned."
    val out = WaypointMapLayout.compute(
      waypointIds = listOf("a", "b"),
      shortcuts = listOf(
        shortcut(from = "a", to = "b"),
        shortcut(from = "b", to = "a"),
      ),
      trailheads = emptyList(),
    )
    assertTrue("a" in out.positions, "cycle node 'a' must still be positioned")
    assertTrue("b" in out.positions, "cycle node 'b' must still be positioned")
    assertTrue(out.layerCount > 0)
  }

  private fun shortcut(from: String, to: String) = ShortcutDisplayItem(
    id = "$from-to-$to",
    description = null,
    from = from,
    to = to,
    variant = null,
  )

  // ---------- resolveOverlays — bounds-less matched node ----------

  @Test
  fun resolveOverlays_matchedNodeWithoutBoundsKeepsCountButLeavesBoundsEmpty() {
    // Compose semantic tree: the matching node carries a testTag but no bounds. The
    // resolver finds it (count = 1), `mapNotNull { it.bounds }` drops it from the
    // drawable list, and isSatisfied refuses to claim success — see the kdoc on
    // WaypointSelectorOverlay for why.
    val tree = composeNode("root", children = listOf(composeNode("alpha", bounds = null)))
    val overlays = resolveOverlays(
      tree = tree,
      required = listOf(composeRequired("alpha")),
      forbidden = emptyList(),
    )
    val overlay = overlays.single()
    assertEquals(1, overlay.matchedCount)
    assertTrue(overlay.matchedBounds.isEmpty())
    assertFalse(overlay.isSatisfied())
  }

  // ---------- helpers ----------

  private fun def(id: String) = WaypointDefinition(id = id)

  /**
   * Constructs an overlay for a unit-under-test. `hasBounds = true` populates a single
   * synthetic [TrailblazeNode.Bounds] in [WaypointSelectorOverlay.matchedBounds] so
   * REQUIRED-isSatisfied tests get the drawable rectangle the new contract requires.
   */
  private fun overlay(
    kind: WaypointSelectorKind,
    minCount: Int,
    matchedCount: Int,
    hasBounds: Boolean,
  ) = WaypointSelectorOverlay(
    kind = kind,
    entryIndex = 0,
    description = "test",
    minCount = minCount,
    matchedBounds = if (hasBounds) listOf(TrailblazeNode.Bounds(0, 0, 1, 1)) else emptyList(),
    matchedCount = matchedCount,
  )

  private fun composeNode(
    tag: String,
    bounds: TrailblazeNode.Bounds? = null,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nextNodeId(),
    children = children,
    bounds = bounds,
    driverDetail = DriverNodeDetail.Compose(testTag = tag),
  )

  private fun composeRequired(testTag: String, minCount: Int = 1) = WaypointSelectorEntry(
    selector = TrailblazeNodeSelector(
      compose = DriverNodeMatch.Compose(testTag = testTag),
    ),
    minCount = minCount,
  )

  private var nodeIdCounter = 0L
  private fun nextNodeId(): Long = ++nodeIdCounter
}
