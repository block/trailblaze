package xyz.block.trailblaze.usages

import java.io.File
import java.security.MessageDigest

/**
 * One tool's source material: the `.ts`/`.js` module that implements it, plus the YAML descriptor
 * that declared it (null for bare typed-binding `.ts` files with no descriptor). The descriptor is
 * part of the tool's identity for change detection: fields like `runtime`, `supportedPlatforms`,
 * `requiresHost`, or `inputSchema` change whether and how recorded usages dispatch without
 * touching a byte of script.
 */
data class ToolSource(
  val script: File,
  val descriptor: File? = null,
)

/**
 * Identity of one declared scripted tool. Keyed by trailmap as well as name because the runtime
 * loader enforces name uniqueness only WITHIN a trailmap — two trailmaps may legally declare the
 * same tool name, and a name-only inventory would drop the second one, so an edit to it would
 * never reach the modified set.
 *
 * [trailmap] is the trailmap's DIRECTORY name, not its manifest id. The directory is the scoping
 * unit this scan can establish without decoding every `trailmap.yaml` on both sides, and it is
 * already how the ignored-tool grouping identifies a trailmap.
 */
data class ToolKey(
  val trailmap: String,
  val name: String,
)

/**
 * One side's scripted-tool inventory: [ToolKey] → its [ToolSource]. [warnings] carries
 * anything that made the inventory possibly incomplete (malformed descriptor, ambiguous bare
 * `.ts`), mirroring [ToolUsagesReport.warnings] fail-open semantics.
 */
data class ToolSourceSnapshot(
  val toolSources: Map<ToolKey, ToolSource>,
  val warnings: List<String> = emptyList(),
)

/**
 * One side's fingerprint of one tool, at the two strengths [ChangedToolAnalysis.compute] knows how
 * to compare.
 *
 * [closure] covers the tool's script AND its resolved import closure, so an edit to a shared helper
 * it imports changes it. Null when that closure couldn't be resolved — the tool didn't bundle.
 *
 * [bytes] covers the tool's own script and descriptor bytes alone. Always computable, and blind to
 * imports. Null only when the tool's own files couldn't be read.
 *
 * Carrying both is what lets [ChangedToolAnalysis.compute] refuse to compare a closure fingerprint
 * against a bytes fingerprint. They measure different things, so a difference between them says
 * nothing about whether the tool changed.
 */
data class ToolFingerprint(
  val closure: String?,
  val bytes: String?,
)

/** The classified diff between two [ToolSourceSnapshot]s. Field meanings match [ChangedSinceSummary]. */
data class ChangedToolsResult(
  val added: List<String>,
  val removed: List<String>,
  val modified: List<String>,
  val diagnostics: List<UsagesDiagnostic>,
)

/**
 * [ExcludedRefInvisibleTools.snapshot] with the ref-invisible tools dropped, and one
 * [UsagesDiagnostic] per affected trailmap.
 *
 * The diagnostics ride alongside rather than inside the snapshot's own warnings because they mean a
 * different thing: "these tools exist and we deliberately did not compare them" (validate them in
 * their owning repo) is not "our scan of this side hit a malformed descriptor" (fix the descriptor).
 * Folding them together would file both under one kind and lose the action.
 */
data class ExcludedRefInvisibleTools(
  val snapshot: ToolSourceSnapshot,
  val diagnostics: List<UsagesDiagnostic>,
)

/**
 * Classifies which scripted tools changed between a base snapshot (a git ref's tree) and the
 * current snapshot (the working tree).
 *
 * Presence is compared by NAME (a tool that moved files but fingerprints identically is unchanged;
 * a rename is an add + a remove — both are reported, which is what a caller validating trails
 * wants, since trails invoke tools by name). Tools present on both sides are compared by
 * [hashTool], which the caller backs with the bundler's content key over the tool's source AND its
 * resolved import closure, combined with the descriptor's bytes — so editing a shared helper flags
 * every tool that imports it, and a descriptor-only edit (`runtime:`, `inputSchema:`) flags too.
 * It is byte-level, so a comment-only edit also flags — conservative on purpose.
 *
 * The INVENTORY is keyed per trailmap ([ToolKey]) but the RESULT is bare names, because that is
 * what a trail invokes. So when two trailmaps declare the same name, a change to either flags the
 * name, and the usages query then reports every trail using it — including trails served by the
 * untouched copy. That over-reports rather than under-reports, which is the right bias for a
 * blast-radius check.
 *
 * [hashTool] reports each side at both strengths ([ToolFingerprint]), and the two sides are only
 * ever compared LIKE FOR LIKE. When one side resolved an import closure and the other couldn't, the
 * tool is counted as modified (fail OPEN: a tool we can't compare must not silently read as
 * unchanged) with a warning saying so — comparing the two directly would report every tool as
 * modified on every run, which reads as a detected edit and is not one.
 */
object ChangedToolAnalysis {

  /**
   * The [UsagesDiagnostic.subject] values a [UsagesDiagnostic.TOOL_INVENTORY_INCOMPLETE] uses: which
   * inventory the scan could not read completely. Named constants rather than literals because the
   * explicit (`usages <name>`) path reports against the same vocabulary from
   * `UsagesCommand`, and a consumer matching on the subject must not have to know that two
   * producers spell it independently.
   */
  const val SIDE_BASE: String = "base"

  /** The working tree's inventory — the only side an explicit, non-`--changed-since` run reads. */
  const val SIDE_CURRENT: String = "current"

  /**
   * Drops tools whose implementing script is in [ignoredScripts] (git-ignored — e.g. a trailmap
   * staged into the workspace from a pinned clone of another repo). No ref checkout will ever
   * contain those files, so a working-tree-vs-ref comparison would misreport every one of them
   * as "added" on every run. Each affected trailmap gets one warning naming its excluded tools,
   * so the report says what it could not compare instead of silently narrowing.
   */
  fun excludeRefInvisible(
    snapshot: ToolSourceSnapshot,
    ignoredScripts: Set<File>,
  ): ExcludedRefInvisibleTools {
    if (ignoredScripts.isEmpty()) return ExcludedRefInvisibleTools(snapshot, emptyList())
    val (invisible, visible) = snapshot.toolSources.entries.partition { it.value.script.absoluteFile in ignoredScripts }
    if (invisible.isEmpty()) return ExcludedRefInvisibleTools(snapshot, emptyList())
    val byTrailmap = invisible.groupBy { it.key.trailmap }
    val diagnostics = byTrailmap.entries.sortedBy { it.key }.map { (trailmap, tools) ->
      UsagesDiagnostic(
        kind = UsagesDiagnostic.TOOL_COMPARISON_EXCLUDED,
        subject = trailmap,
        message = "trailmap '$trailmap': ${tools.size} tool(s) are gitignored (staged from another " +
          "source, invisible to every git ref) and excluded from the comparison — validate them " +
          "against their owning repo: ${tools.map { it.key.name }.sorted().joinToString(", ")}",
      )
    }
    return ExcludedRefInvisibleTools(
      snapshot = ToolSourceSnapshot(toolSources = visible.associate { it.key to it.value }, warnings = snapshot.warnings),
      diagnostics = diagnostics,
    )
  }

  fun compute(
    base: ToolSourceSnapshot,
    current: ToolSourceSnapshot,
    hashTool: (source: ToolSource, toolName: String) -> ToolFingerprint,
  ): ChangedToolsResult {
    val diagnostics = mutableListOf<UsagesDiagnostic>()
    // The SIDE is the subject: an inventory gap on one side is what makes a tool read as added or
    // removed, and which side it happened on is what tells those two answers apart.
    base.warnings.forEach { diagnostics += inventoryDiagnostic(SIDE_BASE, it) }
    current.warnings.forEach { diagnostics += inventoryDiagnostic(SIDE_CURRENT, it) }

    // Presence is name-level: a tool that MOVED trailmaps is neither added nor removed, because
    // trails invoking it by name still resolve. It lands in `modified` below instead — its
    // declaring set changed, which can change how a recorded usage dispatches.
    val baseByName = declarationsByName(base)
    val currentByName = declarationsByName(current)

    val added = (currentByName.keys - baseByName.keys).sorted()
    val removed = (baseByName.keys - currentByName.keys).sorted()

    val modified = mutableListOf<String>()
    for (name in currentByName.keys.intersect(baseByName.keys).sorted()) {
      val baseDecls = baseByName.getValue(name)
      val currentDecls = currentByName.getValue(name)
      if (baseDecls.keys != currentDecls.keys) {
        // Same name, different set of declaring trailmaps (moved, or a second trailmap started
        // declaring it) — a dispatch change even when every surviving copy is byte-identical.
        modified += name
        continue
      }
      // `any` short-circuits, which is deliberate: fingerprinting runs esbuild, and once one
      // declaring trailmap's copy has changed the name is already flagged.
      val changed = currentDecls.any { (trailmap, currentSource) ->
        val baseFingerprint = hashTool(baseDecls.getValue(trailmap), name)
        val currentFingerprint = hashTool(currentSource, name)
        when {
          // Both sides resolved their import closure — the strong comparison this command is for.
          baseFingerprint.closure != null && currentFingerprint.closure != null ->
            baseFingerprint.closure != currentFingerprint.closure

          // Neither did (e.g. a `runtime: subprocess` tool importing Node built-ins). Both sides
          // degraded the same way, so the comparison still means something — just not about imports.
          baseFingerprint.closure == null && currentFingerprint.closure == null &&
            baseFingerprint.bytes != null && currentFingerprint.bytes != null -> {
            diagnostics += degradedDiagnostic(
              name,
              "$trailmap/$name: neither side's import closure could be resolved — compared " +
                "by the tool's own script and descriptor bytes, so an edit to a file it imports will " +
                "not flag it",
            )
            baseFingerprint.bytes != currentFingerprint.bytes
          }

          // Everything else is not a comparison. Either one side measured an import closure and the
          // other couldn't, or a side couldn't be read at all. Fail OPEN — a tool we can't compare
          // must never read as unchanged — but say that's what happened, so a consumer doesn't take
          // it for a detected edit.
          else -> {
            // Unreadable beats closure-less: a side with no fingerprint AT ALL is the one that
            // stopped the comparison, even when the other side also lacks a closure. Folding the
            // two conditions together blames whichever side happens to be tested first.
            val side = when {
              baseFingerprint.bytes == null && currentFingerprint.bytes == null -> "either side"
              baseFingerprint.bytes == null -> "the base side"
              currentFingerprint.bytes == null -> "the current side"
              baseFingerprint.closure == null -> "the base side"
              else -> "the current side"
            }
            diagnostics += degradedDiagnostic(
              name,
              "$trailmap/$name: $side could not be fingerprinted the way the other side " +
                "was, so the two are not comparable — counted as modified (fail open), not a detected edit",
            )
            true
          }
        }
      }
      if (changed) modified += name
    }

    return ChangedToolsResult(added = added, removed = removed, modified = modified, diagnostics = diagnostics)
  }

  /**
   * The subject is the bare tool NAME, not `trailmap/name`, so a consumer asking "was tool X's
   * comparison degraded?" can match it against the same names it reads everywhere else in the
   * report ([ToolUsagesReport.tools], [ChangedSinceSummary.modified] — all bare names, because that
   * is what a trail invokes). The declaring trailmap stays in the message, where the precision is
   * still available to a reader chasing which copy degraded.
   */
  private fun degradedDiagnostic(toolName: String, message: String) = UsagesDiagnostic(
    kind = UsagesDiagnostic.TOOL_COMPARISON_DEGRADED,
    subject = toolName,
    message = message,
  )

  private fun inventoryDiagnostic(side: String, warning: String) = UsagesDiagnostic(
    kind = UsagesDiagnostic.TOOL_INVENTORY_INCOMPLETE,
    subject = side,
    message = "$side: $warning",
  )

  /** Regroups an inventory as tool name → (declaring trailmap → its source). */
  private fun declarationsByName(snapshot: ToolSourceSnapshot): Map<String, Map<String, ToolSource>> =
    snapshot.toolSources.entries
      .groupBy { it.key.name }
      .mapValues { (_, entries) -> entries.associate { it.key.trailmap to it.value } }

  /**
   * Combines a script-content key with the descriptor's content hash into one tool fingerprint.
   * Returns null when the descriptor exists but can't be read — fail open, per [compute]'s
   * null-hash contract. A descriptor-less tool's fingerprint is the script key alone.
   */
  fun composeFingerprint(scriptKey: String, descriptor: File?): String? {
    if (descriptor == null) return scriptKey
    val descriptorHash = runCatching { sha256(descriptor.readBytes()) }.getOrNull() ?: return null
    return "$scriptKey+$descriptorHash"
  }

  fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
