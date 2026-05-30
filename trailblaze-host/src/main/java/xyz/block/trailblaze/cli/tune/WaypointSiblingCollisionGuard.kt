package xyz.block.trailblaze.cli.tune

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.waypoint.WaypointMatcher

/**
 * Cross-waypoint collision guard for both refinement (`waypoint tune`) and detection
 * (`waypoint propose`). Re-runs the matcher across the session set to confirm a proposed
 * waypoint edit (or new waypoint) doesn't pull a sibling's existing match set into
 * conflict before we open a PR.
 *
 * The check is: does applying the proposal cause waypoint A to *newly* match a step that
 * some other waypoint B *already* matches? If yes, A's new match set overlaps with B's
 * existing match set on a step that didn't overlap before — a collision the trailmap reviewer
 * almost certainly didn't want.
 *
 * Two entry points:
 *  - [check] — refinement ergonomic, takes a [WaypointTuner.Proposal] in hand (tune's
 *    hot loop).
 *  - [checkOverlap] — generic surface. Pass `definitionBefore = null` for the
 *    brand-new-waypoint case (detection has no prior definition to diff); pass non-null
 *    for the edit case.
 *
 * This is the load-bearing safety net for both loops. The analyzers are deliberately
 * conservative about *what* they propose, but the only way to know whether a proposal
 * pulls a sibling waypoint into a wrong match is to re-run the matcher across the
 * session set.
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
   * Refinement-ergonomic wrapper around [checkOverlap]. Runs the matcher against the
   * proposal's [WaypointTuner.Proposal.definitionBefore] (baseline) and
   * [WaypointTuner.Proposal.definitionAfter] (mutated) per step, short-circuiting the
   * after-side matcher when the before-side already matched (see [checkOverlap] for
   * the load-bearing rationale). Steps newly matched by the mutated definition are
   * checked against every *other* waypoint in [siblings] — if any sibling also
   * matches, the step is a collision.
   *
   * Sibling list passed in by the caller (the CLI hands in the rest of the trailmap). Pass
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
    val raw = checkOverlap(
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
   * Generic shape used by both the refinement guard (`waypoint tune`, via [check]) and
   * the detection guard (`waypoint propose`, which calls this directly). Equivalent
   * domain vocabulary used elsewhere in this codebase: "cross-waypoint collision",
   * "sibling bleed" (see `WaypointProposeCommand`'s validateAndJoin gate kdoc).
   *
   * Pass [definitionBefore] = null for the brand-new-waypoint case (detection): there's
   * no prior definition to diff against, so "newly matched" collapses to "matched by
   * [definitionAfter] at all." Pass a non-null [definitionBefore] for the edit case
   * (refinement): "newly matched" = matched by [definitionAfter] AND not matched by
   * [definitionBefore].
   *
   * Why the null sentinel instead of an "empty WaypointDefinition" baseline? A
   * [WaypointDefinition] with empty required/forbidden matches every screen, so the
   * `if (… definitionBefore matches) continue` short-circuit at the top of the loop
   * would classify every step as "already matched by before" and the cross-bleed check
   * would silently no-op. The new-waypoint path needs explicit "match-only" semantics,
   * which the null branch delivers (the `definitionBefore != null && …` guard skips
   * the before-side matcher entirely when null).
   *
   * Self-filter: a sibling whose id equals [waypointId] is skipped — the caller can
   * pass the full trailmap without worrying about pseudo-collisions against itself.
   *
   * Templated waypoints must come with a resolved [TargetTemplateContext] or the
   * matcher will skip them as `UNRESOLVED_TARGET_TEMPLATE` and the guard will silently
   * see no matches. Pass `target = null` for non-templated waypoints.
   */
  fun checkOverlap(
    waypointId: String,
    definitionBefore: WaypointDefinition?,
    definitionAfter: WaypointDefinition,
    siblings: List<WaypointDefinition>,
    sessions: List<SessionStep>,
    target: TargetTemplateContext? = null,
  ): RawVerdict {
    val newlyMatched = mutableListOf<String>()
    val collisions = mutableListOf<Verdict.Collision>()
    for (step in sessions) {
      if (definitionBefore != null &&
        WaypointMatcher.match(definitionBefore, step.screen, target).matched
      ) {
        continue
      }
      if (!WaypointMatcher.match(definitionAfter, step.screen, target).matched) continue
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
   * [checkOverlap] when the caller doesn't have a [WaypointTuner.Proposal] to attach.
   */
  data class RawVerdict(
    val newlyMatchedStepIds: List<String>,
    val collidingSteps: List<Verdict.Collision>,
  ) {
    val safe: Boolean get() = collidingSteps.isEmpty()
  }
}
