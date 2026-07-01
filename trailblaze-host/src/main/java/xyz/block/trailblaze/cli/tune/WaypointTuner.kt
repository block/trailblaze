package xyz.block.trailblaze.cli.tune

import xyz.block.trailblaze.api.waypoint.WaypointCondition
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import java.io.File

/**
 * Pure analysis core for `trailblaze waypoint tune`. Consumes a list of per-step match
 * outcomes (already produced by [xyz.block.trailblaze.waypoint.WaypointMatcher]) and emits
 * a list of [Proposal]s — one per (waypoint, condition, kind) the analyzer believes is a
 * single-line YAML edit worth surfacing to a human reviewer.
 *
 * v2 waypoints are classifier-keyed, so a definition's conditions live in
 * [WaypointDefinition.byClassifier] blocks rather than a single top-level list. The detectors
 * analyze against the **union** of conditions across all blocks (deduped by fingerprint), and a
 * proposal's edit is applied by **fingerprint** to whichever block declares the affected
 * condition — so a drop/lower lands in the right `android:` / `ios:` block without the tuner
 * having to thread a classifier through every detector.
 *
 * Three detectors, all conservative:
 *
 * 1. **Drift** — a required condition with `matchCount = 0` in at least `minSupport`
 *    near-miss steps (a near-miss step = every other required condition matched and no
 *    forbidden condition fired). Proposal: drop the condition.
 * 2. **Off-by-one** — a required condition consistently matches `minCount - 1` (never 0,
 *    never ≥ minCount) in at least `minSupport` near-miss steps. Proposal: lower
 *    `minCount` by 1.
 * 3. **False-positive forbidden** — a forbidden condition fires in at least `minSupport`
 *    steps where every required condition would otherwise match. Proposal: drop the condition.
 *
 * No I/O — callers do file enumeration and mutation. This object is invoked from
 * `WaypointTuneCommand` and exercised by `WaypointTunerTest` against synthetic match
 * results.
 *
 * See `docs/internal/devlog/2026-05-19-waypoint-pack-refinement.md` for the design.
 */
object WaypointTuner {

  /**
   * Per-step input: a single step's result against a single waypoint definition. The
   * caller (CLI) joins these per (sessionId, waypoint) before passing the flat list in.
   */
  data class StepMatch(
    val sessionId: String,
    val stepId: String,
    val waypointId: String,
    val result: WaypointMatchResult,
  )

  /**
   * One actionable edit. The CLI materializes the mutated [definitionAfter] back to YAML
   * (via `WaypointLoader.yaml.encodeToString(...)`) and uses [proposalKey] to look up
   * cross-week dedupe state.
   */
  data class Proposal(
    val waypointId: String,
    val kind: ProposalKind,
    val rationale: String,
    val supportSessions: Int,
    val supportSteps: Int,
    val sourceFile: File,
    val definitionBefore: WaypointDefinition,
    val definitionAfter: WaypointDefinition,
    val affectedEntry: WaypointCondition,
  ) {
    /**
     * Stable identifier used for cross-week dedupe. Two proposals with the same key
     * describe the same logical edit even if generated weeks apart, against different
     * corpora.
     */
    val proposalKey: String
      get() = "$waypointId|${fingerprintEntry(affectedEntry)}|$kind"

    /**
     * Replays this proposal's edit against an arbitrary [WaypointDefinition] (need not be
     * [definitionBefore]). Used to compose multiple proposals on the same waypoint when
     * checking idempotence and cross-proposal collisions — without this we'd lose all but
     * one proposal per waypoint when `associateBy { waypointId }` collapses the list.
     *
     * Idempotent: applying twice to a definition that already has the edit is a no-op (the
     * fingerprint carries minCount, so a LOWER_MIN_COUNT proposal targeting `min=3` no longer
     * matches the already-lowered `min=2` condition on the second pass).
     */
    fun apply(def: WaypointDefinition): WaypointDefinition = applyEdit(def, kind, affectedEntry)
  }

  enum class ProposalKind {
    DROP_REQUIRED,
    LOWER_MIN_COUNT,
    DROP_FORBIDDEN,
  }

  /**
   * Carries source provenance (so the CLI knows which file to mutate) alongside the
   * parsed definition. Trailmap-bundled classpath-only waypoints are excluded — tune
   * operates on filesystem-walked YAMLs.
   */
  data class WaypointSource(
    val sourceFile: File,
    val definition: WaypointDefinition,
  )

  /**
   * Apply an edit to the classifier block(s) that declare [entry] (matched by fingerprint).
   * Blocks that don't carry the condition are untouched. Single source of mutation, shared by
   * [Proposal.apply] and the detectors.
   */
  private fun applyEdit(
    def: WaypointDefinition,
    kind: ProposalKind,
    entry: WaypointCondition,
  ): WaypointDefinition {
    val mutated = def.byClassifier.mapValues { (_, variant) ->
      when (kind) {
        ProposalKind.DROP_REQUIRED ->
          variant.copy(required = variant.required.filterNot { sameEntry(it, entry) })
        ProposalKind.LOWER_MIN_COUNT ->
          variant.copy(
            required = variant.required.map {
              if (sameEntry(it, entry)) it.copy(minCount = (it.minCount - 1).coerceAtLeast(1)) else it
            },
          )
        ProposalKind.DROP_FORBIDDEN ->
          variant.copy(forbidden = variant.forbidden.filterNot { sameEntry(it, entry) })
      }
    }
    return def.copy(byClassifier = mutated)
  }

  /** Union of required conditions across all classifier blocks, deduped by fingerprint. */
  private fun WaypointDefinition.allRequired(): List<WaypointCondition> =
    byClassifier.values.flatMap { it.required }.distinctBy { fingerprintEntry(it) }

  /** Union of forbidden conditions across all classifier blocks, deduped by fingerprint. */
  private fun WaypointDefinition.allForbidden(): List<WaypointCondition> =
    byClassifier.values.flatMap { it.forbidden }.distinctBy { fingerprintEntry(it) }

  /**
   * Run all three detectors against the session set. The returned list is in deterministic
   * order (sorted by waypoint id, then proposal kind, then condition fingerprint) so
   * idempotence and golden-test stability is easy to reason about.
   *
   * `minSupport` defaults to 5 (see devlog). `homogeneityThreshold` is the fraction of
   * near-miss steps that must point at the *same* condition for the detector to fire — the
   * "noise vs. signal" guard against waypoints that miss for many different reasons.
   */
  fun analyze(
    sources: List<WaypointSource>,
    matches: List<StepMatch>,
    minSupport: Int = DEFAULT_MIN_SUPPORT,
    homogeneityThreshold: Double = DEFAULT_HOMOGENEITY,
  ): List<Proposal> {
    require(minSupport >= 1) { "minSupport must be >= 1, got $minSupport" }
    require(homogeneityThreshold in 0.0..1.0) {
      "homogeneityThreshold must be in [0,1], got $homogeneityThreshold"
    }
    val sourceById = sources.associateBy { it.definition.id }
    val byWaypoint = matches.groupBy { it.waypointId }
    val proposals = mutableListOf<Proposal>()
    for ((waypointId, perWaypoint) in byWaypoint) {
      val source = sourceById[waypointId] ?: continue
      proposals += detectDrift(source, perWaypoint, minSupport, homogeneityThreshold)
      proposals += detectOffByOne(source, perWaypoint, minSupport, homogeneityThreshold)
      proposals += detectFalsePositiveForbidden(source, perWaypoint, minSupport, homogeneityThreshold)
    }
    return proposals.sortedWith(
      compareBy({ it.waypointId }, { it.kind.name }, { fingerprintEntry(it.affectedEntry) }),
    )
  }

  /**
   * Compose every proposal per waypoint onto a single mutated [WaypointDefinition], keyed
   * by waypoint id. Used by the CLI's idempotence check and its cross-proposal collision
   * pass — both need the *joint* end-state of all proposals on a waypoint, not just the
   * last one (the former `associateBy { waypointId }` shape collapsed to one and
   * masked multi-proposal idempotence bugs).
   *
   * Returns a map; waypoints absent from [proposals] aren't present. Proposals on a
   * waypoint are folded in their original input order; that order doesn't matter for the
   * three current detectors because each emits at most one proposal per (condition, kind)
   * and `Proposal.apply` is fingerprint-keyed (so two proposals on different conditions
   * commute). A defensive `require` flags the silent-assumption — every proposal in a
   * group must share the same [Proposal.definitionBefore], otherwise the fold's base
   * case is ambiguous.
   */
  fun composeMutatedTrailmap(proposals: List<Proposal>): Map<String, WaypointDefinition> =
    proposals.groupBy { it.waypointId }.mapValues { (_, group) ->
      require(group.distinctBy { it.definitionBefore }.size == 1) {
        "Proposals on ${group.first().waypointId} have divergent definitionBefore; " +
          "the fold's base case is ambiguous. This shouldn't happen — all proposals " +
          "from one analyzer run share the unmutated source."
      }
      group.fold(group.first().definitionBefore) { acc, p -> p.apply(acc) }
    }

  /**
   * Drift: a required condition with `matchCount = 0` in a homogeneous near-miss set.
   *
   * "Near-miss" = every other required condition matched, no forbidden present. That means
   * THIS condition is the sole reason the waypoint missed. If the session set shows this same
   * shape ≥ `minSupport` distinct sessions, and ≥ `homogeneityThreshold` of the near-miss
   * steps point at this same condition as the sole culprit, propose dropping it.
   */
  private fun detectDrift(
    source: WaypointSource,
    matches: List<StepMatch>,
    minSupport: Int,
    homogeneityThreshold: Double,
  ): List<Proposal> {
    val nearMissSteps = matches.filter { it.result.isSoleEntryNearMiss() }
    if (nearMissSteps.size < minSupport) return emptyList()
    // For each required condition, count near-miss steps where this condition was the missing
    // one with matchCount == 0.
    val out = mutableListOf<Proposal>()
    for (entry in source.definition.allRequired()) {
      val supporting = nearMissSteps.filter { step ->
        val miss = step.result.missingRequired.singleOrNull() ?: return@filter false
        sameEntry(miss.entry, entry) && miss.matchCount == 0
      }
      if (supporting.size < minSupport) continue
      val sessions = supporting.map { it.sessionId }.distinct().size
      if (sessions < minSupport) continue
      val homogeneity = supporting.size.toDouble() / nearMissSteps.size.toDouble()
      if (homogeneity < homogeneityThreshold) continue
      val mutated = applyEdit(source.definition, ProposalKind.DROP_REQUIRED, entry)
      out += Proposal(
        waypointId = source.definition.id,
        kind = ProposalKind.DROP_REQUIRED,
        rationale = "Required condition produced 0 matches in ${supporting.size} of ${nearMissSteps.size} " +
          "near-miss step(s) across $sessions session(s); the condition is the sole reason this " +
          "waypoint failed to match. Drop and let the reviewer decide whether to replace with " +
          "a looser selector.",
        supportSessions = sessions,
        supportSteps = supporting.size,
        sourceFile = source.sourceFile,
        definitionBefore = source.definition,
        definitionAfter = mutated,
        affectedEntry = entry,
      )
    }
    return out
  }

  /**
   * Off-by-one: a required condition consistently matches `minCount - 1` and never 0 in
   * near-miss steps. Lower `minCount` by 1.
   *
   * The "never 0" guard is what separates this from drift: if the condition sometimes
   * matches `minCount - 1` and sometimes matches 0, the session set is telling us the
   * selector itself is rotting (drift case), not just that minCount is too aggressive.
   * Letting both detectors fire would emit conflicting proposals on the same condition.
   */
  private fun detectOffByOne(
    source: WaypointSource,
    matches: List<StepMatch>,
    minSupport: Int,
    homogeneityThreshold: Double,
  ): List<Proposal> {
    val nearMissSteps = matches.filter { it.result.isSoleEntryNearMiss() }
    if (nearMissSteps.size < minSupport) return emptyList()
    val out = mutableListOf<Proposal>()
    for (entry in source.definition.allRequired()) {
      if (entry.minCount <= 1) continue // can't lower to 0; that's drift territory.
      val candidateSteps = nearMissSteps.filter { step ->
        val miss = step.result.missingRequired.singleOrNull() ?: return@filter false
        sameEntry(miss.entry, entry)
      }
      if (candidateSteps.isEmpty()) continue
      val anyZeroCount = candidateSteps.any { step ->
        step.result.missingRequired.single().matchCount == 0
      }
      if (anyZeroCount) continue // drift case, not off-by-one.
      val supporting = candidateSteps.filter { step ->
        step.result.missingRequired.single().matchCount == entry.minCount - 1
      }
      if (supporting.size < minSupport) continue
      val sessions = supporting.map { it.sessionId }.distinct().size
      if (sessions < minSupport) continue
      val homogeneity = supporting.size.toDouble() / nearMissSteps.size.toDouble()
      if (homogeneity < homogeneityThreshold) continue
      val mutated = applyEdit(source.definition, ProposalKind.LOWER_MIN_COUNT, entry)
      out += Proposal(
        waypointId = source.definition.id,
        kind = ProposalKind.LOWER_MIN_COUNT,
        rationale = "Required condition consistently matched ${entry.minCount - 1} (one short of " +
          "minCount=${entry.minCount}) in ${supporting.size} of ${nearMissSteps.size} near-miss " +
          "step(s) across $sessions session(s), and never hit 0. Lower minCount by 1.",
        supportSessions = sessions,
        supportSteps = supporting.size,
        sourceFile = source.sourceFile,
        definitionBefore = source.definition,
        definitionAfter = mutated,
        affectedEntry = entry,
      )
    }
    return out
  }

  /**
   * False-positive forbidden: a forbidden condition fires on steps where every required
   * condition matched. The waypoint *would* match if not for this forbidden — propose
   * dropping it.
   *
   * The reviewer can re-add a narrower forbidden after looking at the screen state.
   * We don't propose a narrowing because we'd be inventing selector text.
   */
  private fun detectFalsePositiveForbidden(
    source: WaypointSource,
    matches: List<StepMatch>,
    minSupport: Int,
    homogeneityThreshold: Double,
  ): List<Proposal> {
    val wouldMatchExceptForbiddenSteps = matches.filter { step ->
      val r = step.result
      !r.matched && r.skipped == null &&
        r.missingRequired.isEmpty() && r.presentForbidden.isNotEmpty()
    }
    if (wouldMatchExceptForbiddenSteps.size < minSupport) return emptyList()
    val out = mutableListOf<Proposal>()
    for (entry in source.definition.allForbidden()) {
      val supporting = wouldMatchExceptForbiddenSteps.filter { step ->
        // Sole forbidden firing is this condition. Multi-forbidden cases mean the screen has
        // more than one disqualifier — dropping any one of them won't enable the match,
        // so emitting a proposal there would be noise.
        val present = step.result.presentForbidden.singleOrNull() ?: return@filter false
        sameEntry(present.entry, entry)
      }
      if (supporting.size < minSupport) continue
      val sessions = supporting.map { it.sessionId }.distinct().size
      if (sessions < minSupport) continue
      val homogeneity = supporting.size.toDouble() / wouldMatchExceptForbiddenSteps.size.toDouble()
      if (homogeneity < homogeneityThreshold) continue
      val mutated = applyEdit(source.definition, ProposalKind.DROP_FORBIDDEN, entry)
      out += Proposal(
        waypointId = source.definition.id,
        kind = ProposalKind.DROP_FORBIDDEN,
        rationale = "Forbidden condition fired as the sole disqualifier on ${supporting.size} of " +
          "${wouldMatchExceptForbiddenSteps.size} step(s) across $sessions session(s) where " +
          "every required condition matched. Drop and let the reviewer decide whether to add a " +
          "narrower forbidden.",
        supportSessions = sessions,
        supportSteps = supporting.size,
        sourceFile = source.sourceFile,
        definitionBefore = source.definition,
        definitionAfter = mutated,
        affectedEntry = entry,
      )
    }
    return out
  }

  /**
   * A step is a "sole-entry near miss" when exactly one required condition failed and no
   * forbidden fired — i.e. there's one logical fix. Multi-condition misses are excluded
   * because we'd be guessing which condition to tune.
   */
  private fun WaypointMatchResult.isSoleEntryNearMiss(): Boolean =
    !matched && skipped == null &&
      missingRequired.size == 1 && presentForbidden.isEmpty()

  /**
   * Stable fingerprint for a condition, used both as the dedupe key and to identify the
   * "same" condition across two parses of the same YAML.
   *
   * Uses the selector's `description()` (which `TrailblazeNodeSelector` already exposes
   * for human-readable rendering) plus the minCount as the discriminator. A condition with no
   * selector (a future non-selector condition kind) falls back to its description text. That's
   * good enough for dedupe — if two authors write the same selector different ways the
   * description text will differ, and the dedupe will emit one PR per variant, which is
   * the correct behavior since the YAML edits would also differ.
   */
  private fun fingerprintEntry(entry: WaypointCondition): String =
    "${entry.selector?.description() ?: "no-selector:${entry.description.orEmpty()}"}|min=${entry.minCount}"

  /**
   * Two conditions are "the same" when their fingerprints match. The matcher round-trips the
   * condition into the match result, so the condition instance returned via
   * [WaypointMatchResult.MissingRequired.entry] should equal one of the definition's
   * required conditions — but we don't rely on object identity (target-template expansion
   * may have re-copied the condition). Fingerprint compare is the stable join.
   */
  private fun sameEntry(a: WaypointCondition, b: WaypointCondition): Boolean =
    fingerprintEntry(a) == fingerprintEntry(b)

  internal const val DEFAULT_MIN_SUPPORT = 5
  internal const val DEFAULT_HOMOGENEITY = 0.9
}
