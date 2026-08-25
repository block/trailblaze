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

/** The classified diff between two [ToolSourceSnapshot]s. Field meanings match [ChangedSinceSummary]. */
data class ChangedToolsResult(
  val added: List<String>,
  val removed: List<String>,
  val modified: List<String>,
  val warnings: List<String>,
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
 * [hashTool] returning null means "could not fingerprint that side" — the tool is counted as
 * modified (fail OPEN: a tool we can't compare must not silently read as unchanged) and a warning
 * names it and the failing side(s), so a consumer can distinguish real modifications from
 * comparison failures.
 */
object ChangedToolAnalysis {

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
  ): ToolSourceSnapshot {
    if (ignoredScripts.isEmpty()) return snapshot
    val (invisible, visible) = snapshot.toolSources.entries.partition { it.value.script.absoluteFile in ignoredScripts }
    if (invisible.isEmpty()) return snapshot
    val byTrailmap = invisible.groupBy { it.key.trailmap }
    val warnings = byTrailmap.entries.sortedBy { it.key }.map { (trailmap, tools) ->
      "trailmap '$trailmap': ${tools.size} tool(s) are gitignored (staged from another source, " +
        "invisible to every git ref) and excluded from the comparison — validate them against " +
        "their owning repo: ${tools.map { it.key.name }.sorted().joinToString(", ")}"
    }
    return ToolSourceSnapshot(
      toolSources = visible.associate { it.key to it.value },
      warnings = snapshot.warnings + warnings,
    )
  }

  fun compute(
    base: ToolSourceSnapshot,
    current: ToolSourceSnapshot,
    hashTool: (source: ToolSource, toolName: String) -> String?,
  ): ChangedToolsResult {
    val warnings = mutableListOf<String>()
    warnings += base.warnings.map { "base: $it" }
    warnings += current.warnings.map { "current: $it" }

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
        val baseHash = hashTool(baseDecls.getValue(trailmap), name)
        val currentHash = hashTool(currentSource, name)
        if (baseHash == null || currentHash == null) {
          val side = when {
            baseHash == null && currentHash == null -> "either side"
            baseHash == null -> "the base side"
            else -> "the current side"
          }
          warnings += "$trailmap/$name: could not fingerprint $side — counted as modified (fail open)"
          true
        } else {
          baseHash != currentHash
        }
      }
      if (changed) modified += name
    }

    return ChangedToolsResult(added = added, removed = removed, modified = modified, warnings = warnings)
  }

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
