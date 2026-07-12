package xyz.block.trailblaze.recordings

import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import java.io.File

/**
 * Shared save-back routing + unified read-merge-write for every recording surface: the CLI
 * (`trailblaze run`), MCP trail authoring, and the desktop recording tab. Each surface used to
 * write its own `<classifier>.trail.yaml` unconditionally; this is the single place that decides
 * whether a save routes to the unified `trail.yaml` (merging one device's classifier slot) or to a
 * legacy sibling, and the single place that reads-merges-writes the unified file (atomic write +
 * corrupt-file refusal). A future cross-process file lock lands here once, not three times.
 *
 * The pure merge itself lives in [UnifiedTrailAdapter.mergeRecordedClassifier]
 * ([xyz.block.trailblaze.yaml.unified]); this object adds the JVM file I/O around it. It is
 * deliberately gate-agnostic: callers resolve the rollout gate ([resolveGate]) and pass the result
 * in, so the CLI can layer its `--unified-recordings` flag on top of the env/persisted tiers while
 * MCP/desktop use env/persisted only.
 *
 * The legacy `<classifier>.trail.yaml` write stays with each surface (its filename and no-classifier
 * fallback differ), so this object never writes a legacy file — it only tells the caller whether to,
 * and refuses to let one shadow a unified trail.
 */
object UnifiedRecordingWriter {

  /** Env var that opts the gate on (`1`/`true`) or off (`0`/`false`) for every surface. */
  const val ENV_UNIFIED_RECORDINGS: String = "TRAILBLAZE_UNIFIED_RECORDINGS"

  /**
   * Resolve the unified-recordings gate. Tier order: an explicit [flagOverride] (the CLI's
   * `--[no-]unified-recordings`) wins, then the [ENV_UNIFIED_RECORDINGS] env var, then the caller's
   * [persistedConfig] (`trailblaze config unified-recordings`), then on — unified is the default
   * save format; any tier set to false is the opt-out back to legacy `<classifier>.trail.yaml`
   * saving.
   *
   * MCP/desktop pass `flagOverride = null` (no CLI flag to honor); the CLI passes its parsed flag.
   * The env-var name and its parsing live here so the three surfaces can never disagree on them.
   */
  fun resolveGate(flagOverride: Boolean?, persistedConfig: Boolean?): Boolean =
    flagOverride
      ?: parseBooleanGate(System.getenv(ENV_UNIFIED_RECORDINGS))
      ?: persistedConfig
      ?: true

  /**
   * Parse an on/off gate string, accepting the documented `1`/`true` (and `0`/`false`) forms
   * case-insensitively (the rest of the framework's env flags accept `1`, and the user-facing
   * errors/docs tell users to set `=1`). Returns `null` for absent or unrecognized values so the
   * caller falls through to the next tier. Kept pure + `internal` so it's unit-testable without
   * touching the process environment.
   */
  internal fun parseBooleanGate(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Shared user-facing messages — kept here so the MCP and desktop surfaces (which map the same
  // outcomes to their own SaveResult/Result wrappers) can't drift their wording. The CLI keeps its
  // own richer console phrasing.
  // ─────────────────────────────────────────────────────────────────────────────

  /** Refusal message for a legacy `<classifier>.trail.yaml` write that would shadow a unified trail. */
  fun legacyShadowRefusalMessage(legacyFileName: String, dir: File): String =
    "Refusing to write $legacyFileName next to a unified trail.yaml in ${dir.absolutePath}. " +
      "Enable unified recordings ($ENV_UNIFIED_RECORDINGS=1 or `trailblaze config unified-recordings true`) " +
      "to merge this recording into the unified trail."

  /** Refusal message for merging into an unreadable existing unified trail (left untouched). */
  fun corruptRefusalMessage(target: File, reason: String): String =
    "Existing unified trail is unreadable and was left untouched (${target.absolutePath}): $reason. " +
      "Fix or delete that file, then retry."

  /** Message when a merge produced no steps to write. */
  const val EMPTY_MERGE_MESSAGE: String = "Recording has no steps to merge into the unified trail."

  /**
   * True when a unified `trail.yaml` is present for [trailFileOrDir] — the gate-OFF refusal guard.
   * A save surface must never drop a legacy `<classifier>.trail.yaml` sibling into (or overwrite a
   * `trail.yaml` in) a migrated directory, because the legacy write can't update the unified file
   * and would only shadow it. "Present" means either the executed file IS itself unified (a named
   * file whose CONTENT is the unified format), or the directory holds a `trail.yaml`.
   */
  fun unifiedTrailPresent(trailFileOrDir: File): Boolean {
    if (executedFileIsUnified(trailFileOrDir)) return true
    val dir = dirOf(trailFileOrDir) ?: return false
    return File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).isFile
  }

  /**
   * True when [file] is itself a unified trail: the bare `trail.yaml` by name, or any other file
   * whose content parses as a unified document. `{{var}}` templates are resolved before parsing
   * (mirroring the run path), so a unified file whose unquoted template is invalid as raw YAML —
   * but which the runner resolves and executes — is still detected. Guard-safe: a v1 file, an
   * unreadable file, or a directory returns false.
   */
  fun executedFileIsUnified(file: File): Boolean {
    if (!file.isFile) return false
    if (TrailRecordings.isUnifiedTrailFile(file.name)) return true
    return runCatching {
      val resolved = TrailYamlTemplateResolver.resolve(file.readText(), file)
      createTrailblazeYaml().decodeTrailDocument(resolved)
    }.getOrNull() is TrailDocument.Unified
  }

  /**
   * The unified file a UNIFIED save reads and writes for [trailFileOrDir]: the executed file itself
   * when it is a unified document (bare `trail.yaml` by name, or a named unified file by content —
   * returned as-is even for a parentless path), otherwise the directory's shared `trail.yaml`. Null
   * only when the executed file is not itself unified and no directory resolves (orphan path with no
   * parent). Single source of truth for the writer and the re-run skip guard so they never disagree.
   */
  fun unifiedRecordingTarget(trailFileOrDir: File): File? {
    if (executedFileIsUnified(trailFileOrDir)) return trailFileOrDir
    val dir = dirOf(trailFileOrDir) ?: return null
    return File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
  }

  /**
   * Whether a save-back for [trailFileOrDir] under [classifier] should merge into the unified
   * `trail.yaml` (`true`) or write a legacy `<classifier>.trail.yaml` sibling (`false`), given the
   * already-resolved [unifiedEnabled] gate:
   *
   *  - Gate off, or a blank [classifier] (no key for a unified slot) → legacy.
   *  - The executed file IS a unified trail (bare or named-by-content) → unified (merge into it).
   *  - The directory already has a bare `trail.yaml` → unified (never drop a sibling beside it).
   *  - The directory holds legacy `<classifier>.trail.yaml` sibling(s) and no unified file → legacy
   *    (don't fork a half-migrated directory; migrating those is a separate, deliberate step).
   *  - Greenfield (neither present) → unified (new recordings default to the unified format).
   *
   * The unified file this decision reads/writes is [unifiedRecordingTarget] — consult the two
   * together so the router and writer never disagree on the target.
   */
  fun shouldRouteUnified(trailFileOrDir: File, classifier: String, unifiedEnabled: Boolean): Boolean {
    if (!unifiedEnabled) return false
    if (classifier.isBlank()) return false
    if (executedFileIsUnified(trailFileOrDir)) return true
    val dir = dirOf(trailFileOrDir) ?: return false
    if (File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).isFile) return true
    val hasLegacySibling = dir.listFiles { f ->
      f.isFile &&
        f.name.endsWith(TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX) &&
        !TrailRecordings.isUnifiedTrailFile(f.name)
    }?.isNotEmpty() == true
    return !hasLegacySibling
  }

  /**
   * True when the unified trail this save-back would write ([unifiedRecordingTarget]) already
   * carries a non-empty recording for [classifier]'s slot (in any step or the trailhead) — so a
   * non-self-heal re-run can skip rather than replace it. False when the file is absent
   * (greenfield), unreadable, or the slot has no recording yet.
   */
  fun unifiedClassifierAlreadyRecorded(trailFileOrDir: File, classifier: String): Boolean {
    val unifiedFile = unifiedRecordingTarget(trailFileOrDir) ?: return false
    if (!unifiedFile.isFile) return false
    val unified = runCatching { createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText()) }
      .getOrNull() ?: return false
    val stepHit = unified.trail.any { it.recordings[classifier]?.isNotEmpty() == true }
    val trailheadHit = unified.trailhead?.recordings?.get(classifier)?.isNotEmpty() == true
    return stepHit || trailheadHit
  }

  /** Outcome of a [mergeIntoUnified] read-merge-write. */
  sealed interface MergeOutcome {
    /** The classifier slot was merged and the unified file written atomically to [target]. */
    data class Merged(val target: File) : MergeOutcome

    /**
     * The recorded trailhead carries [toolCount] (> 1) tools, which the unified one-tool-per-
     * classifier trailhead can't represent. Nothing was written — the caller must preserve the
     * recording as a legacy `<classifier>.trail.yaml` sibling instead of losing it.
     */
    data class MultiToolTrailheadUnsupported(val toolCount: Int) : MergeOutcome

    /**
     * The existing unified [target] is unreadable ([reason] is the parse error). Left untouched —
     * an unreadable trail must never be silently clobbered by a merge.
     */
    data class RefusedCorrupt(val target: File, val reason: String) : MergeOutcome

    /** The merge produced no steps (degenerate recording); nothing written (an empty `trail:` is invalid). */
    object SkippedEmpty : MergeOutcome

    /** No unified target resolved (orphan path with no parent); nothing written. */
    object NoTarget : MergeOutcome
  }

  /**
   * Read the existing unified target for [trailFileOrDir], merge [recordedItems] (the v1 items a
   * recording generates) under [classifier], and write the result atomically. Preserves every other
   * classifier already on disk; existing NL and `recordable:false` intent win on drift (enforced by
   * [UnifiedTrailAdapter.mergeRecordedClassifier]). Fails loud rather than corrupting: an unreadable
   * target is refused untouched, a multi-tool trailhead is reported so the caller can fall back to a
   * legacy sibling, and an empty merge is skipped (an empty `trail:` is unparseable).
   *
   * No user-facing logging — the caller maps the returned [MergeOutcome] to its own output/return so
   * each surface keeps its own UX (CLI console lines, MCP/desktop result objects).
   */
  fun mergeIntoUnified(
    trailFileOrDir: File,
    recordedItems: List<TrailYamlItem>,
    classifier: String,
  ): MergeOutcome {
    // The unified trailhead is one tool per classifier (the emitter enforces it). A v1 recording can
    // carry a multi-tool trailhead the unified format simply can't represent; merging anyway would
    // build a trail that throws on encode and silently lose the recording. Report it so the caller
    // preserves the recording as a legacy sibling. Checked before any file read so it never partially
    // mutates the target.
    val recordedTrailheadTools = recordedItems
      .filterIsInstance<TrailYamlItem.TrailheadTrailItem>()
      .firstOrNull()?.trailhead?.tools.orEmpty()
    if (recordedTrailheadTools.size > 1) {
      return MergeOutcome.MultiToolTrailheadUnsupported(recordedTrailheadTools.size)
    }

    val unifiedFile = unifiedRecordingTarget(trailFileOrDir) ?: return MergeOutcome.NoTarget

    // Serialize the whole read-merge-write per target file so two concurrent IN-PROCESS writers
    // (e.g. two MCP daemon sessions saving the same trail) can't both read the pre-merge file and
    // race their writes — the second write would otherwise drop the first's classifier slot.
    // `writeFileAtomically` prevents torn files; this prevents lost updates. A cross-PROCESS lock is
    // still deferred (the CLI fan-out is sequential, so it's the one path that's already safe).
    return synchronized(lockFor(unifiedFile)) {
      val yaml = createTrailblazeYaml()

      // Read the existing file up front so a parse failure fails loud HERE, leaving both the target
      // and the caller's recording untouched, rather than after we've committed to writing.
      val existing = if (unifiedFile.isFile) {
        runCatching { yaml.decodeUnifiedTrail(unifiedFile.readText()) }.getOrElse { e ->
          return@synchronized MergeOutcome.RefusedCorrupt(unifiedFile, e.message ?: e.toString())
        }
      } else {
        null
      }

      val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recordedItems, classifier)
      // A merge with no steps would emit an empty `trail:`, which the unified parser rejects — skip
      // rather than write an unreadable file (only reachable from a degenerate recording with no
      // prompt steps and no existing trail to preserve).
      if (merged.trail.isEmpty()) {
        MergeOutcome.SkippedEmpty
      } else {
        writeFileAtomically(unifiedFile, yaml.encodeUnifiedTrailToString(merged))
        MergeOutcome.Merged(unifiedFile)
      }
    }
  }

  /**
   * Write [content] to [target] via a temp file in the same directory followed by an atomic rename,
   * so a partial write never leaves a truncated (unreadable) file — the single file that now holds
   * every device's slot. Creates the parent directory if needed. Falls back to a plain replace if
   * the filesystem doesn't support atomic moves.
   */
  fun writeFileAtomically(target: File, content: String) {
    // Resolve the parent from the ABSOLUTE path so the temp file lands in the same directory (and
    // thus the same filesystem) as the target even when [target] is a relative/parentless path —
    // otherwise the temp would land in the system temp dir and the cross-filesystem ATOMIC_MOVE
    // would always fall through to the non-atomic branch. A fixed ≥3-char prefix also avoids
    // File.createTempFile's minimum-prefix-length requirement for very short target names.
    val dir = target.absoluteFile.parentFile
    dir?.mkdirs()
    val tmp = File.createTempFile("unified-recording-", ".tmp", dir)
    try {
      tmp.writeText(content)
      try {
        java.nio.file.Files.move(
          tmp.toPath(),
          target.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      tmp.delete() // no-op once the move succeeded; cleans up the temp on any failure
    }
  }

  /**
   * The directory a save targets: [f] itself when it's a directory, else its parent (null for an
   * orphan path). PRECONDITION: when passing a directory, it must already exist on disk — a
   * not-yet-created directory path reports `isDirectory == false` and would resolve to its PARENT
   * here, so callers create the trail directory (`mkdirs`) before routing/target resolution.
   */
  private fun dirOf(f: File): File? = if (f.isDirectory) f else f.parentFile

  // Per-target-path lock registry for [mergeIntoUnified]'s in-process read-merge-write. Keyed by
  // canonical path so two File instances pointing at the same unified trail share one monitor. The
  // map is small and bounded by the number of distinct trail files a process ever writes.
  private val pathLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

  private fun lockFor(file: File): Any =
    pathLocks.computeIfAbsent(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)) { Any() }
}
