package xyz.block.trailblaze.cli.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.waypoint.WaypointCondition
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.cli.androidForbidden
import xyz.block.trailblaze.cli.androidRequired
import xyz.block.trailblaze.cli.androidWaypoint
import java.io.File

/**
 * Pure-analyzer tests for [WaypointTuner]. We construct synthetic [WaypointMatchResult]
 * objects directly rather than running the matcher — the analyzer's job is to count
 * patterns across step results, and that contract is exercised most cleanly without a
 * real screen state.
 *
 * v2 waypoints are classifier-keyed; these single-platform fixtures use an `android` block
 * (via the shared `androidWaypoint` / `androidRequired` / `androidForbidden` helpers).
 *
 * The sibling-collision guard isn't covered here (it's a thin shim over the matcher and
 * is tested via the matcher's own tests + a future end-to-end CLI smoke test). The three
 * detectors are the load-bearing logic.
 */
class WaypointTunerTest {

  // ---------------- drift detector ----------------

  @Test
  fun `drift detector fires when one required entry consistently matches zero in near-miss sessions`() {
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("Home"), literalText("StaleLabel")),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("home.waypoint.yaml"), def))
    val matches = (1..6).map { i ->
      step(
        sessionId = "s$i",
        stepId = "s$i/step1",
        waypointId = def.id,
        result = nearMissOnEntry(def, missingEntryIndex = 1, matchCount = 0),
      )
    }

    val proposals = WaypointTuner.analyze(sources, matches)
    assertEquals(1, proposals.size, "exactly one drop-required proposal expected")
    val p = proposals.single()
    assertEquals(WaypointTuner.ProposalKind.DROP_REQUIRED, p.kind)
    assertEquals("myapp/home", p.waypointId)
    assertEquals(6, p.supportSessions)
    assertEquals(1, p.definitionAfter.androidRequired.size, "the drifted entry must be removed")
    assertEquals(
      "Home",
      p.definitionAfter.androidRequired.single().selector?.androidAccessibility?.textRegex?.removePrefix("^")?.removeSuffix("$"),
    )
  }

  @Test
  fun `drift detector skips when support count below min-support`() {
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("Home"), literalText("StaleLabel")),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("home.waypoint.yaml"), def))
    val matches = (1..4).map { i ->
      step("s$i", "s$i/step", def.id, nearMissOnEntry(def, missingEntryIndex = 1, matchCount = 0))
    }

    assertTrue(
      WaypointTuner.analyze(sources, matches, minSupport = 5).isEmpty(),
      "4 near-misses is below the default minSupport=5",
    )
  }

  @Test
  fun `drift detector skips when near-miss set is heterogeneous (different entries miss in different sessions)`() {
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("EntryA"), literalText("EntryB"), literalText("EntryC")),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("home.waypoint.yaml"), def))
    val matches = listOf(
      step("s1", "s1/step", def.id, nearMissOnEntry(def, 0, matchCount = 0)),
      step("s2", "s2/step", def.id, nearMissOnEntry(def, 1, matchCount = 0)),
      step("s3", "s3/step", def.id, nearMissOnEntry(def, 2, matchCount = 0)),
      step("s4", "s4/step", def.id, nearMissOnEntry(def, 0, matchCount = 0)),
      step("s5", "s5/step", def.id, nearMissOnEntry(def, 1, matchCount = 0)),
      step("s6", "s6/step", def.id, nearMissOnEntry(def, 2, matchCount = 0)),
    )
    // No single entry crosses the 90% homogeneity bar even though total near-misses
    // exceed minSupport. This is the noise-vs-signal guard.
    assertTrue(WaypointTuner.analyze(sources, matches).isEmpty())
  }

  // ---------------- off-by-one detector ----------------

  @Test
  fun `off-by-one detector fires when required entry consistently matches minCount minus one`() {
    val def = waypoint(
      id = "myapp/tabs",
      required = listOf(literalText("Tab", minCount = 3)),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("tabs.waypoint.yaml"), def))
    val matches = (1..6).map { i ->
      step("s$i", "s$i/step", def.id, nearMissOnEntry(def, 0, matchCount = 2))
    }

    val proposals = WaypointTuner.analyze(sources, matches)
    assertEquals(1, proposals.size)
    val p = proposals.single()
    assertEquals(WaypointTuner.ProposalKind.LOWER_MIN_COUNT, p.kind)
    assertEquals(2, p.definitionAfter.androidRequired.single().minCount, "minCount should be decremented by 1")
  }

  @Test
  fun `off-by-one detector skips when any session has zero matches (that's drift, not off-by-one)`() {
    val def = waypoint(
      id = "myapp/tabs",
      required = listOf(literalText("Tab", minCount = 3)),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("tabs.waypoint.yaml"), def))
    // 5 sessions at matchCount=2, 1 session at matchCount=0 → mixed signal. The drift
    // detector should fire on the homogeneity-fail (counts = 0 is a drift symptom) and
    // off-by-one should hold off.
    val matches = (1..5).map { i ->
      step("s$i", "s$i/step", def.id, nearMissOnEntry(def, 0, matchCount = 2))
    } + step("s6", "s6/step", def.id, nearMissOnEntry(def, 0, matchCount = 0))

    val proposals = WaypointTuner.analyze(sources, matches)
    assertTrue(
      proposals.none { it.kind == WaypointTuner.ProposalKind.LOWER_MIN_COUNT },
      "off-by-one must not fire when the same entry also hits 0 elsewhere",
    )
  }

  @Test
  fun `off-by-one detector skips entries with minCount of one`() {
    // Can't lower minCount below 1 (the data class enforces it).
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("Home", minCount = 1)),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("home.waypoint.yaml"), def))
    val matches = (1..6).map { i ->
      step("s$i", "s$i/step", def.id, nearMissOnEntry(def, 0, matchCount = 0))
    }
    val proposals = WaypointTuner.analyze(sources, matches)
    assertTrue(
      proposals.none { it.kind == WaypointTuner.ProposalKind.LOWER_MIN_COUNT },
      "off-by-one can't propose lowering below 1",
    )
    // Drift detector still fires though — same input, different proposal kind.
    assertEquals(1, proposals.count { it.kind == WaypointTuner.ProposalKind.DROP_REQUIRED })
  }

  // ---------------- forbidden detector ----------------

  @Test
  fun `false-positive forbidden detector fires when forbidden is sole disqualifier`() {
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("Home")),
      forbidden = listOf(literalText("SignIn")),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("home.waypoint.yaml"), def))
    val matches = (1..5).map { i ->
      step(
        "s$i", "s$i/step", def.id,
        forbiddenFired(def, requiredMatched = true, presentForbiddenIdx = 0, count = 1),
      )
    }

    val proposals = WaypointTuner.analyze(sources, matches)
    assertEquals(1, proposals.size)
    val p = proposals.single()
    assertEquals(WaypointTuner.ProposalKind.DROP_FORBIDDEN, p.kind)
    assertTrue(p.definitionAfter.androidForbidden.isEmpty())
  }

  @Test
  fun `forbidden detector skips multi-forbidden steps (dropping one wouldn't enable the match)`() {
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("Home")),
      forbidden = listOf(literalText("SignIn"), literalText("OtherBlocker")),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("home.waypoint.yaml"), def))
    // Both forbidden entries fire on every step — dropping either one wouldn't help.
    val matches = (1..6).map { i ->
      step(
        "s$i", "s$i/step", def.id,
        WaypointMatchResult(
          definitionId = def.id,
          matched = false,
          matchedRequired = listOf(
            WaypointMatchResult.MatchedRequired(def.androidRequired[0], matchCount = 1),
          ),
          missingRequired = emptyList(),
          presentForbidden = listOf(
            WaypointMatchResult.PresentForbidden(def.androidForbidden[0], matchCount = 1),
            WaypointMatchResult.PresentForbidden(def.androidForbidden[1], matchCount = 1),
          ),
        ),
      )
    }
    assertTrue(WaypointTuner.analyze(sources, matches).isEmpty())
  }

  // ---------------- proposal key stability ----------------

  @Test
  fun `proposal key is stable across runs for the same logical edit`() {
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("Home"), literalText("StaleLabel")),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("home.waypoint.yaml"), def))
    val matches = (1..6).map { i ->
      step("s$i", "s$i/step", def.id, nearMissOnEntry(def, 1, matchCount = 0))
    }

    val keyA = WaypointTuner.analyze(sources, matches).single().proposalKey
    val keyB = WaypointTuner.analyze(sources, matches).single().proposalKey
    assertEquals(keyA, keyB, "the same input must yield the same proposal key for cross-week dedupe")
    assertTrue(keyA.startsWith("myapp/home|"), "key prefixed by waypoint id: was $keyA")
    assertTrue(keyA.endsWith("|DROP_REQUIRED"), "key suffixed by proposal kind: was $keyA")
  }

  // ---------------- composeMutatedTrailmap ----------------

  @Test
  fun `composeMutatedTrailmap folds multiple proposals on the same waypoint in order`() {
    // Two synthetic proposals on the same waypoint, different entries. composeMutatedTrailmap
    // must apply both — the previous `associateBy { waypointId }` path silently kept
    // only one, the bug that round-1 finding #1 and Codex P1 flagged.
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("Home", minCount = 2), literalText("StaleLabel")),
    )
    val p1 = syntheticProposal(
      def = def,
      kind = WaypointTuner.ProposalKind.DROP_REQUIRED,
      affected = def.androidRequired[1],
      mutated = androidWaypoint(def.id, required = listOf(def.androidRequired[0])),
    )
    val p2 = syntheticProposal(
      def = def,
      kind = WaypointTuner.ProposalKind.LOWER_MIN_COUNT,
      affected = def.androidRequired[0],
      mutated = androidWaypoint(
        def.id,
        required = listOf(def.androidRequired[0].copy(minCount = 1), def.androidRequired[1]),
      ),
    )
    val joint = WaypointTuner.composeMutatedTrailmap(listOf(p1, p2))
    val mutated = joint["myapp/home"] ?: error("expected joint mutation for myapp/home")
    assertEquals(1, mutated.androidRequired.size, "StaleLabel dropped")
    assertEquals(1, mutated.androidRequired.single().minCount, "Home's minCount lowered")
  }

  @Test
  fun `composeMutatedTrailmap rejects mixed definitionBefore in the same waypoint group`() {
    // Two proposals on the same waypoint whose definitionBefores diverge. Shouldn't
    // happen in practice (all proposals from one analyzer run share the unmutated
    // source) but the `require` is the assertion guarding that invariant.
    val defV1 = waypoint(id = "myapp/home", required = listOf(literalText("A")))
    val defV2 = waypoint(id = "myapp/home", required = listOf(literalText("B")))
    val p1 = syntheticProposal(
      def = defV1,
      kind = WaypointTuner.ProposalKind.DROP_REQUIRED,
      affected = defV1.androidRequired.single(),
      mutated = androidWaypoint(defV1.id),
    )
    val p2 = syntheticProposal(
      def = defV2,
      kind = WaypointTuner.ProposalKind.DROP_REQUIRED,
      affected = defV2.androidRequired.single(),
      mutated = androidWaypoint(defV2.id),
    )
    assertFailsWith<IllegalArgumentException> {
      WaypointTuner.composeMutatedTrailmap(listOf(p1, p2))
    }
  }

  @Test
  fun `composeMutatedTrailmap on disjoint waypoints preserves both edits`() {
    val home = waypoint(id = "myapp/home", required = listOf(literalText("Home")))
    val settings = waypoint(id = "myapp/settings", required = listOf(literalText("Settings")))
    val pHome = syntheticProposal(
      def = home,
      kind = WaypointTuner.ProposalKind.DROP_REQUIRED,
      affected = home.androidRequired.single(),
      mutated = androidWaypoint(home.id),
    )
    val pSettings = syntheticProposal(
      def = settings,
      kind = WaypointTuner.ProposalKind.DROP_REQUIRED,
      affected = settings.androidRequired.single(),
      mutated = androidWaypoint(settings.id),
    )
    val joint = WaypointTuner.composeMutatedTrailmap(listOf(pHome, pSettings))
    assertEquals(2, joint.size, "both waypoints should appear in the joint mutation")
    assertTrue(joint["myapp/home"]?.androidRequired?.isEmpty() == true)
    assertTrue(joint["myapp/settings"]?.androidRequired?.isEmpty() == true)
  }

  @Test
  fun `composeMutatedTrailmap on empty input is empty`() {
    assertTrue(WaypointTuner.composeMutatedTrailmap(emptyList()).isEmpty())
  }

  // ---------------- parameter guards ----------------

  @Test
  fun `analyze rejects minSupport below 1`() {
    val def = waypoint(id = "x", required = listOf(literalText("A")))
    val sources = listOf(WaypointTuner.WaypointSource(File("x.waypoint.yaml"), def))
    assertFailsWith<IllegalArgumentException> {
      WaypointTuner.analyze(sources, emptyList(), minSupport = 0)
    }
  }

  @Test
  fun `analyze rejects homogeneity threshold outside zero-to-one range`() {
    val def = waypoint(id = "x", required = listOf(literalText("A")))
    val sources = listOf(WaypointTuner.WaypointSource(File("x.waypoint.yaml"), def))
    assertFailsWith<IllegalArgumentException> {
      WaypointTuner.analyze(sources, emptyList(), homogeneityThreshold = 1.1)
    }
    assertFailsWith<IllegalArgumentException> {
      WaypointTuner.analyze(sources, emptyList(), homogeneityThreshold = -0.1)
    }
  }

  // ---------------- proposal-apply helper ----------------

  @Test
  fun `proposal apply composes drop-required and lower-mincount on the same waypoint`() {
    val def = waypoint(
      id = "myapp/home",
      required = listOf(literalText("Home", minCount = 2), literalText("StaleLabel")),
    )
    val sources = listOf(WaypointTuner.WaypointSource(File("home.waypoint.yaml"), def))
    val matches = (1..6).flatMap { i ->
      listOf(
        step("s$i", "s$i/a", def.id, nearMissOnEntry(def, missingEntryIndex = 1, matchCount = 0)),
        step("s$i", "s$i/b", def.id, nearMissOnEntry(def, missingEntryIndex = 0, matchCount = 1)),
      )
    }
    // Homogeneity is computed per-detector against the *whole* near-miss set; with a
    // 50/50 mix of drift-on-entry-1 and off-by-one-on-entry-0 the default 0.9 threshold
    // suppresses both. Lower the threshold for this test — we're exercising apply()
    // composition, not the homogeneity gate (that's covered elsewhere).
    val proposals = WaypointTuner.analyze(sources, matches, homogeneityThreshold = 0.4)
    assertEquals(2, proposals.size, "expected one drop-required + one lower-mincount: $proposals")
    // Apply both proposals back onto the original definition; order must not matter for
    // proposals targeting different entries (Proposal.apply must commute).
    val composedA = proposals.fold(def) { acc, p -> p.apply(acc) }
    val composedB = proposals.reversed().fold(def) { acc, p -> p.apply(acc) }
    assertEquals(composedA, composedB, "Proposal.apply must commute for proposals on different entries")
    assertEquals(1, composedA.androidRequired.size, "StaleLabel dropped")
    assertEquals(1, composedA.androidRequired.single().minCount, "Home's minCount lowered from 2 to 1")
  }

  // ---------------- fixtures ----------------

  private fun waypoint(
    id: String,
    required: List<WaypointCondition> = emptyList(),
    forbidden: List<WaypointCondition> = emptyList(),
  ): WaypointDefinition = androidWaypoint(id = id, required = required, forbidden = forbidden)

  private fun literalText(text: String, minCount: Int = 1): WaypointCondition =
    WaypointCondition(
      selector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^$text$"),
      ),
      minCount = minCount,
    )

  private fun step(
    sessionId: String,
    stepId: String,
    waypointId: String,
    result: WaypointMatchResult,
  ): WaypointTuner.StepMatch = WaypointTuner.StepMatch(sessionId, stepId, waypointId, result)

  /**
   * Build a synthetic [WaypointTuner.Proposal] directly from a definition + mutated
   * definition pair, bypassing the analyzer. Used by the `composeMutatedTrailmap` tests
   * where the analyzer's gating semantics aren't under test — just the fold (which recomputes
   * the end state via `Proposal.apply`, so `mutated` here is only the stored `definitionAfter`).
   */
  private fun syntheticProposal(
    def: WaypointDefinition,
    kind: WaypointTuner.ProposalKind,
    affected: WaypointCondition,
    mutated: WaypointDefinition,
  ): WaypointTuner.Proposal = WaypointTuner.Proposal(
    waypointId = def.id,
    kind = kind,
    rationale = "test",
    supportSessions = 1,
    supportSteps = 1,
    sourceFile = File("${def.id.replace('/', '_')}.waypoint.yaml"),
    definitionBefore = def,
    definitionAfter = mutated,
    affectedEntry = affected,
  )

  /**
   * Synthesizes a `WaypointMatchResult` representing a sole-entry near miss: every
   * required entry except [missingEntryIndex] matched (with matchCount=1), [missingEntryIndex]
   * has [matchCount] (less than its minCount, by construction), no forbidden present.
   */
  private fun nearMissOnEntry(
    def: WaypointDefinition,
    missingEntryIndex: Int,
    matchCount: Int,
  ): WaypointMatchResult {
    val required = def.androidRequired
    val matched = required.filterIndexed { i, _ -> i != missingEntryIndex }
      .map { WaypointMatchResult.MatchedRequired(it, matchCount = it.minCount) }
    val missing = listOf(
      WaypointMatchResult.MissingRequired(required[missingEntryIndex], matchCount),
    )
    return WaypointMatchResult(
      definitionId = def.id,
      matched = false,
      matchedRequired = matched,
      missingRequired = missing,
      presentForbidden = emptyList(),
    )
  }

  /**
   * Synthesizes a "would match if not for the forbidden": every required matched, the
   * forbidden at [presentForbiddenIdx] fired with [count] matches.
   */
  private fun forbiddenFired(
    def: WaypointDefinition,
    requiredMatched: Boolean,
    presentForbiddenIdx: Int,
    count: Int,
  ): WaypointMatchResult {
    require(requiredMatched) { "fixture only models the would-match-except-forbidden case" }
    val matched = def.androidRequired.map { WaypointMatchResult.MatchedRequired(it, matchCount = it.minCount) }
    val forbidden = listOf(
      WaypointMatchResult.PresentForbidden(def.androidForbidden[presentForbiddenIdx], matchCount = count),
    )
    return WaypointMatchResult(
      definitionId = def.id,
      matched = false,
      matchedRequired = matched,
      missingRequired = emptyList(),
      presentForbidden = forbidden,
    )
  }
}
