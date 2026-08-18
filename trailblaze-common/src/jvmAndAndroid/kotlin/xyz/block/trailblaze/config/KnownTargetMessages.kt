package xyz.block.trailblaze.config

/**
 * The user-facing sentences built from [KnownTargetWorkspace] records.
 *
 * Every surface that reports an unresolvable target renders its pointer from here, so the wording
 * can't drift between the place someone hits the problem and the place they go looking for the
 * answer: the CLI's `config target` error and listing, the two MCP target-switch tools, trail
 * validation, the recording RPC, `--target` resolution in the waypoint commands, and the daemon's
 * run path. Each keeps its own lead sentence and appends the shared hint.
 *
 * Each function returns null / an empty list when no record covers the target. An installation with
 * no registry on its classpath must read exactly as it did before this existed, so callers append
 * these rather than replacing their own lead sentence.
 */
object KnownTargetMessages {

  /**
   * Appended to a "target not found" message when the id is declared as living in another repo.
   *
   * The message has to answer the question the bare available-list provokes — "so which of these do
   * I use instead?" — with "none of them, you're in the wrong place." Naming the cwd is what makes
   * that land: the same command works unchanged from a clone of the named repo.
   */
  fun unavailableTargetHint(
    targetId: String,
    workingDirectory: String? = null,
    workspaces: List<KnownTargetWorkspace> = KnownTargetWorkspaceLoader.discover(),
  ): String? {
    val workspace = KnownTargetWorkspaceLoader.workspaceFor(targetId, workspaces) ?: return null
    return buildString {
      append("'$targetId' lives in ${workspace.shortName}")
      workspace.description?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
      append(" — its trailmap and trails are in that repo, not in this Trailblaze installation.")
      if (workingDirectory != null) {
        append("\nYou are running from $workingDirectory.")
      }
      append("\nClone it and run Trailblaze from that directory:")
      append("\n  ${workspace.cloneCommand}")
    }
  }

  /**
   * Single-line variant of [unavailableTargetHint], for surfaces that normalize an error to one
   * line. Trail validation truncates every other error it reports to
   * `lineSequence().first().take(300)`, so a multi-line hint there would either be cut mid-sentence
   * or break the one-line shape the editor renders.
   */
  fun unavailableTargetSummary(
    targetId: String,
    workspaces: List<KnownTargetWorkspace> = KnownTargetWorkspaceLoader.discover(),
  ): String? {
    val workspace = KnownTargetWorkspaceLoader.workspaceFor(targetId, workspaces) ?: return null
    return "'$targetId' lives in ${workspace.shortName}, not in this Trailblaze installation — " +
      "clone it and run from that directory: ${workspace.cloneCommand}"
  }

  /**
   * The "not installed" section for a target listing: every declared target absent from
   * [availableTargetIds], grouped by the repo that homes it.
   *
   * Grouped by repo rather than listed per target because the unit of action is the clone, and
   * because a record carries only ids — an absent target has no loaded display name to show.
   *
   * Returns the lines to emit, or empty when nothing is missing. The caller owns spacing.
   */
  fun notInstalledListing(
    availableTargetIds: Collection<String>,
    workspaces: List<KnownTargetWorkspace> = KnownTargetWorkspaceLoader.discover(),
  ): List<String> {
    val missingByWorkspace = workspaces.mapNotNull { workspace ->
      val missing = workspace.targets
        // A record listing an id twice (or twice under different casing) would otherwise render
        // `targets: shared, shared`. Deduped per record, not globally: when two DIFFERENT repos each
        // claim the same target, showing both claims is the honest rendering of a misconfiguration a
        // global dedupe would hide.
        .distinctBy { it.lowercase() }
        .filterNot { target ->
          availableTargetIds.any { it.equals(target, ignoreCase = true) }
        }
      if (missing.isEmpty()) null else workspace to missing
    }
    if (missingByWorkspace.isEmpty()) return emptyList()

    val lines = mutableListOf("Not installed — these targets live in another repo:")
    for ((workspace, missing) in missingByWorkspace) {
      val descriptionSuffix = workspace.description
        ?.takeIf { it.isNotBlank() }
        ?.let { " — $it" }
        .orEmpty()
      lines += "  ${workspace.shortName}$descriptionSuffix"
      lines += "    targets: ${missing.joinToString(", ")}"
      lines += "    ${workspace.cloneCommand}"
    }
    return lines
  }

  /**
   * Suffix for a target that IS loaded here but whose home is another repo — its trailmap ships in
   * the binary (often as a pinned copy) while its trails exist only in that repo. Someone who can
   * run the target but has nothing to run against it needs the pointer just as much as someone who
   * can't run it at all.
   *
   * Null when no record homes the target, so targets that live here read unchanged.
   */
  fun homeAnnotation(
    targetId: String,
    workspaces: List<KnownTargetWorkspace> = KnownTargetWorkspaceLoader.discover(),
  ): String? = KnownTargetWorkspaceLoader.workspaceFor(targetId, workspaces)
    ?.let { "trails in ${it.shortName}" }
}
