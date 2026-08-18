package xyz.block.trailblaze.cli

import xyz.block.trailblaze.config.KnownTargetWorkspace
import xyz.block.trailblaze.config.KnownTargetWorkspaceLoader
import java.io.File

/**
 * Authoring-time warning for accidental target-id capture.
 *
 * A workspace trailmap wholesale shadows a classpath trailmap with the same id — the documented
 * [xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader] precedence rule. That is
 * deliberate inside a target's own home repo (its workspace overriding the bundled copy of its own
 * target), but a foot-gun anywhere else: name a new trailmap after an id another repo has
 * registered in the known-target-workspaces registry and every run in that workspace silently
 * resolves the id to the new trailmap instead of the real target, with no signal.
 *
 * This lint makes that visible where it's cheapest to fix — `trailblaze check` / `compile`, the
 * moment the trailmap is authored. A **warning, never an error**: overriding is a legitimate
 * documented workflow, and the registry ([KnownTargetWorkspaceLoader]) is declarative pointers
 * that can go stale, so nothing here may fail a build.
 */
object KnownTargetShadowLint {

  /** A trailmap discovered on disk in the workspace — its manifest id and the directory it lives in. */
  data class WorkspaceTrailmap(val id: String, val directory: File)

  /**
   * One warning line per workspace trailmap whose id the registry homes in a repo this workspace
   * is not a checkout of. Empty when [records] is empty (every OSS install today), when no id
   * collides, when a colliding trailmap is a staged copy, or when a colliding id's registered home
   * matches one of this workspace's own git remotes — a target's home repo overriding its own
   * bundled trailmap is the intended workflow, not a finding.
   *
   * **Staged copies are not findings.** [isStagedCopy] is true for a trailmap directory git ignores,
   * which marks content a build materialized (e.g. a pinned clone of the home repo checked out into
   * the workspace) rather than source someone authored here. Warning on those reports the pinning
   * mechanism working as designed, on every CI run, forever — noise that trains people to ignore the
   * warning that matters. Checked before [workspaceRepoShortNames] so an all-staged collision set
   * never pays for the remote probe.
   *
   * [workspaceRepoShortNames] supplies the workspace's own repo identities (`org/repo` short
   * forms, per [KnownTargetWorkspace.shortNameForRepo]) and is a lazy provider because it costs a
   * `git` subprocess — it is only invoked once at least one non-staged id actually collides. An
   * empty result means the workspace's repo can't be identified (not a git checkout, no remotes, no
   * `git`); the warning then stands, since "can't tell whose checkout this is" is exactly when the
   * author needs the pointer to who owns the id. A fork-ONLY clone of the home repo (origin = the
   * fork, no `upstream` remote) reports the fork's `org/repo` and so warns inside what is
   * semantically the target's own repo — accepted, because the alternative is suppressing on a
   * guess, and this is advisory output the author can act on or ignore.
   *
   * Ids match case-insensitively, mirroring [KnownTargetWorkspaceLoader.workspaceFor] — the same
   * rule every other target-id surface resolves with.
   */
  fun warningsFor(
    workspaceTrailmaps: Collection<WorkspaceTrailmap>,
    records: List<KnownTargetWorkspace>,
    workspaceRepoShortNames: () -> Set<String>,
    isStagedCopy: (File) -> Boolean,
    commandLabel: String,
  ): List<String> {
    if (records.isEmpty()) return emptyList()
    val collisions = workspaceTrailmaps
      .distinctBy { it.id }
      .sortedBy { it.id }
      .mapNotNull { trailmap ->
        KnownTargetWorkspaceLoader.workspaceFor(trailmap.id, records)?.let { trailmap to it }
      }
    if (collisions.isEmpty()) return emptyList()
    val authored = collisions.filterNot { (trailmap, _) -> isStagedCopy(trailmap.directory) }
    if (authored.isEmpty()) return emptyList()
    val ownRepos = workspaceRepoShortNames()
    return authored.mapNotNull { (trailmap, record) ->
      val home = record.shortName
      if (ownRepos.any { it.equals(home, ignoreCase = true) }) {
        null
      } else {
        "trailblaze $commandLabel: Warning: workspace trailmap '${trailmap.id}' shadows a target " +
          "the known-target-workspaces registry homes in $home — a workspace trailmap replaces a " +
          "same-id classpath trailmap wholesale, so '${trailmap.id}' now resolves to this " +
          "workspace's trailmap. If overriding $home's target isn't intended, rename this " +
          "trailmap's id."
      }
    }
  }
}
