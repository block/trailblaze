package xyz.block.trailblaze.cli.tune

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.waypoint.WaypointMatcher

/**
 * Re-validates a [WaypointTuner.Proposal] across the whole pack before we open a PR.
 *
 * The check is: does applying the proposed edit to waypoint A cause A to *newly* match a
 * step that some other waypoint B *already* matches? If yes, A's new match set overlaps
 * with B's existing match set on a step that didn't overlap before — a collision the
 * pack reviewer almost certainly didn't want.
 *
 * This is the load-bearing safety net for the refinement loop. The analyzer is
 * deliberately conservative about *what* it proposes (deletes and small decrements), but
 * the only way to know whether a loosen pulls a sibling waypoint into a wrong match is
 * to re-run the matcher across the session set.
 *
 * See `docs/internal/devlog/2026-05-19-waypoint-pack-refinement.md`.
 */
object WaypointSiblingCollisionGuard {

  /**
   * One screen state to re-match against. Identified by (sessionId, stepId) so the
   * caller's diagnostic logs can point at the specific step that triggered the
   * collision flag.
   */
  data class SessionStep(
    val sessionId: String,
    val stepId: String,
    val screen: ScreenState,
  )

  /**
   * Outcome of the guard. [collidingSteps] is non-empty when at least one
   * newly-matched step also matches some sibling waypoint that was already matching it.
   * The CLI logs the colliding (stepId, siblingId) pairs and drops the proposal from
   * the PR set.
   */
  data class Verdict(
    val proposal: WaypointTuner.Proposal,
    val newlyMatchedStepIds: List<String>,
    val collidingSteps: List<Collision>,
  ) {
    val safe: Boolean get() = collidingSteps.isEmpty()

    data class Collision(val stepId: String, val siblingWaypointId: String)
  }

  /**
   * Runs the matcher twice per step in [sessions]: once with the proposal's
   * [WaypointTuner.Proposal.definitionBefore] (baseline) and once with
   * [WaypointTuner.Proposal.definitionAfter]. Steps newly matched by the mutated
   * definition are checked against every *other* waypoint in [siblings] — if any
   * sibling also matches, the step is a collision.
   *
   * Sibling list passed in by the caller (the CLI hands in the rest of the pack). Pass
   * `target = null` for non-templated waypoints; templated ones must come with a
   * resolved [TargetTemplateContext] or the matcher will skip them as
   * `UNRESOLVED_TARGET_TEMPLATE` and the guard will silently see no matches.
   */
  fun check(
    proposal: WaypointTuner.Proposal,
    siblings: List<WaypointDefinition>,
    sessions: List<SessionStep>,
    target: TargetTemplateContext? = null,
  ): Verdict {
    val raw = checkEdit(
      waypointId = proposal.waypointId,
      definitionBefore = proposal.definitionBefore,
      definitionAfter = proposal.definitionAfter,
      siblings = siblings,
      sessions = sessions,
      target = target,
    )
    return Verdict(
      proposal = proposal,
      newlyMatchedStepIds = raw.newlyMatchedStepIds,
      collidingSteps = raw.collidingSteps,
    )
  }

  /**
   * Generic shape used by both the refinement guard ([check] above) and the detection
   * (`waypoint propose`) guard. Takes an explicit (id, before, after) triple so callers
   * that don't naturally produce a [WaypointTuner.Proposal] (new-waypoint detection has
   * no "previous" entry to point at) don't have to manufacture one. Returns a
   * [RawVerdict] without the Proposal back-pointer — the caller joins it back to
   * whatever proposal type drove the call.
   */
  fun checkEdit(
    waypointId: String,
    definitionBefore: WaypointDefinition,
    definitionAfter: WaypointDefinition,
    siblings: List<WaypointDefinition>,
    sessions: List<SessionStep>,
    target: TargetTemplateContext? = null,
  ): RawVerdict {
    val newlyMatched = mutableListOf<String>()
    val collisions = mutableListOf<Verdict.Collision>()
    for (step in sessions) {
      val before = WaypointMatcher.match(definitionBefore, step.screen, target)
      val after = WaypointMatcher.match(definitionAfter, step.screen, target)
      if (before.matched || !after.matched) continue
      newlyMatched += step.stepId
      for (sibling in siblings) {
        if (sibling.id == waypointId) continue
        val siblingMatch = WaypointMatcher.match(sibling, step.screen, target)
        if (siblingMatch.matched) {
          collisions += Verdict.Collision(step.stepId, sibling.id)
        }
      }
    }
    return RawVerdict(
      newlyMatchedStepIds = newlyMatched.distinct(),
      collidingSteps = collisions.distinct(),
    )
  }

  /**
   * Variant of [Verdict] without the [Verdict.proposal] back-pointer. Used by
   * [checkEdit] and [checkNewWaypoint] when the caller doesn't have a
   * [WaypointTuner.Proposal] to attach.
   */
  data class RawVerdict(
    val newlyMatchedStepIds: List<String>,
    val collidingSteps: List<Verdict.Collision>,
  ) {
    val safe: Boolean get() = collidingSteps.isEmpty()
  }

  /**
   * Cross-waypoint bleed check for a brand-new waypoint (no `definitionBefore`).
   * Detection (`waypoint propose`) uses this — it has no prior definition to diff
   * against, so the "newly matched" interpretation reduces to "matched by the new
   * definition at all."
   *
   * For each step in [sessions]: if [newDefinition] matches, walk [siblings] for any
   * sibling that ALSO matches. Any such overlap is a collision flagged in the
   * returned [RawVerdict].
   *
   * Self-filter: a sibling whose id equals [waypointId] is skipped — the caller can
   * pass the full pack without worrying about pseudo-collisions against itself.
   *
   * Distinct from [checkEdit] because the empty-def trick fails: a [WaypointDefinition]
   * with empty required/forbidden matches every screen, so [checkEdit]'s
   * `before.matched -> continue` clause would correctly classify every step as "already
   * matched by before" and the cross-bleed check would silently no-op. New waypoints
   * need explicit "match-only" semantics, hence this method.
   */
  fun checkNewWaypoint(
    waypointId: String,
    newDefinition: WaypointDefinition,
    siblings: List<WaypointDefinition>,
    sessions: List<SessionStep>,
    target: TargetTemplateContext? = null,
  ): RawVerdict {
    val matched = mutableListOf<String>()
    val collisions = mutableListOf<Verdict.Collision>()
    for (step in sessions) {
      val newMatch = WaypointMatcher.match(newDefinition, step.screen, target)
      if (!newMatch.matched) continue
      matched += step.stepId
      for (sibling in siblings) {
        if (sibling.id == waypointId) continue
        val siblingMatch = WaypointMatcher.match(sibling, step.screen, target)
        if (siblingMatch.matched) {
          collisions += Verdict.Collision(step.stepId, sibling.id)
        }
      }
    }
    return RawVerdict(
      newlyMatchedStepIds = matched.distinct(),
      collidingSteps = collisions.distinct(),
    )
  }
}
