package xyz.block.trailblaze.cli.shortcut

import xyz.block.trailblaze.cli.shortcut.ShortcutProposer.Proposal

/**
 * Validates a batch of [Proposal]s against the existing pack's shortcut set and against
 * each other. Structurally different from
 * `xyz.block.trailblaze.cli.tune.WaypointSiblingCollisionGuard`:
 *
 *  - Waypoints collide on **screen-match overlap** (does my match set overlap a sibling's?).
 *  - Shortcuts collide on **(from, to, variant) set membership** in the pack's shortcut
 *    registry — the runtime's contextual descriptor filter can't pick between two
 *    shortcuts whose addressing tuple agrees.
 *
 * Kept in the same package as `WaypointSiblingCollisionGuard` for discoverability; not
 * folded into a unified primitive because the inputs (sessions vs. existing shortcut set)
 * and the comparison (matcher vs. set membership) share nothing structural. See the
 * design devlog for the analysis of #3126's consolidation target.
 */
object ShortcutSiblingCollisionGuard {

  /** Addressing tuple of an existing shortcut on disk. `variant == null` is the default
   * (the runtime treats `null` and absent identically). */
  data class ExistingShortcut(val from: String, val to: String, val variant: String?)

  data class Verdict(
    val survived: List<Proposal>,
    val rejections: List<Rejection>,
  )

  data class Rejection(val proposal: Proposal, val reason: String)

  /**
   * Filters [proposals] against [existingShortcuts] (the pack's authored shortcut set)
   * and against each other. Returns the survivors plus typed rejection reasons for the
   * sidecar writer.
   *
   * Rules:
   *  1. **Cycle guard** — `from == to`. Recordings sometimes produce self-loops because
   *     two consecutive steps both match the same waypoint with a different secondary
   *     match flickering in between; never useful as a shortcut.
   *  2. **Pre-existing collision** — drop proposals whose `(from, to, variant=null)`
   *     tuple matches any existing shortcut. v1 only emits variant=null proposals (see
   *     devlog), so the comparison is straightforward.
   *  3. **Cross-proposal collision** — if two surviving proposals share the same
   *     `(from, to)`, keep the higher-support one. Stable tiebreak: lexicographic
   *     `proposalKey` when support is equal.
   */
  fun check(
    proposals: List<Proposal>,
    existingShortcuts: Collection<ExistingShortcut>,
  ): Verdict {
    val rejections = mutableListOf<Rejection>()
    val afterCycleGuard = mutableListOf<Proposal>()
    for (p in proposals) {
      if (p.fromWaypointId == p.toWaypointId) {
        rejections += Rejection(p, "self-edge (from == to): not a useful shortcut")
        continue
      }
      afterCycleGuard += p
    }

    // Pre-existing collision: any existing shortcut with the same (from, to, variant=null).
    val occupied = existingShortcuts
      .filter { it.variant == null }
      .map { it.from to it.to }
      .toSet()
    val afterPreExisting = mutableListOf<Proposal>()
    for (p in afterCycleGuard) {
      if (occupied.contains(p.fromWaypointId to p.toWaypointId)) {
        rejections += Rejection(
          p,
          "pre-existing shortcut already covers (${p.fromWaypointId} -> ${p.toWaypointId}) " +
            "with variant=null; author can add a variant by hand if both should coexist",
        )
        continue
      }
      afterPreExisting += p
    }

    // Cross-proposal collision: keep the higher-support one for each (from, to).
    val byEdge = afterPreExisting.groupBy { it.fromWaypointId to it.toWaypointId }
    val survived = mutableListOf<Proposal>()
    for ((edge, group) in byEdge) {
      val sorted = group.sortedWith(
        compareByDescending<Proposal> { it.supportSessions }
          .thenBy { it.proposalKey },
      )
      survived += sorted.first()
      for (loser in sorted.drop(1)) {
        rejections += Rejection(
          loser,
          "cross-proposal collision on (${edge.first} -> ${edge.second}); " +
            "kept higher-support proposal (key=${sorted.first().proposalKey})",
        )
      }
    }

    // Order survivors by descending support so the CLI's top-K picks the same set as
    // the analyzer's pre-guard ordering. Ties broken by proposalKey for determinism.
    return Verdict(
      survived = survived.sortedWith(
        compareByDescending<Proposal> { it.supportSessions }.thenBy { it.proposalKey },
      ),
      rejections = rejections,
    )
  }
}
