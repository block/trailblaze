package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.migration.UnifiedTrailMigrator
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import java.io.File

/**
 * Converts a legacy per-platform bundle folder into a single unified `.trail.yaml`, backing the Trail
 * Runner "Migrate to unified" action. Thin wrapper over [UnifiedTrailMigrator] (which does the real
 * reconciliation — NL-drift detection, per-classifier drivers/skip, family collapse, memory drift)
 * that adds the file mutation the UI needs: write the unified file into the folder as
 * `<folder>.trail.yaml`, then delete the per-platform inputs (+ `blaze.yaml`) the migrator consumed —
 * except when the migration was lossy (dropped input content), when the inputs are retained (see
 * [Outcome.inputsRetained]) so the only surviving copy of the dropped content isn't destroyed.
 *
 * The side effects live in [migrateFolder] so it can be unit-tested against a temp dir with real
 * bundle content (see `BundleMigrationTest`), rather than only through the daemon route.
 */
object BundleMigration {

  private const val TRAIL_SUFFIX = ".trail.yaml"
  private const val BLAZE_FILENAME = "blaze.yaml"

  data class Outcome(
    /** Name of the unified file written into the folder (`<folder>.trail.yaml`). */
    val outputName: String,
    /** Number of steps in the unified trail. */
    val steps: Int,
    /**
     * Migration warnings surfaced to the route/UI: NL / kind / config / memory drift + dropped-content
     * warnings, plus a leading retention note when [inputsRetained]. Superset of the leading comments
     * written into the file (the file omits the transient retention note).
     */
    val driftComments: List<String>,
    /** Per-platform input files (+ `blaze.yaml`) that were deleted. Empty when [inputsRetained]. */
    val removed: List<String>,
    /**
     * True when the migration was lossy (the migrator dropped input content it could not carry) and
     * the v1 inputs were therefore LEFT IN PLACE beside the written `trail.yaml` for manual
     * reconciliation rather than deleted. [driftComments] leads with a note explaining the retention.
     */
    val inputsRetained: Boolean = false,
  )

  /**
   * Migrate [dir] in place: write `<dir.name>.trail.yaml`, then delete the consumed v1 inputs —
   * UNLESS the migration was lossy (dropped input content), in which case the inputs are left in
   * place so the dropped content isn't destroyed (see [Outcome.inputsRetained]).
   *
   * Throws [IllegalArgumentException] when the migrator refuses the input (no `*.trail.yaml` files, a
   * top-level `- tools:` block, or a `- trailhead:` block — see [UnifiedTrailMigrator]), or when [dir]
   * already contains the target unified file (looks already migrated). Never deletes the output file.
   */
  fun migrateFolder(dir: File, yaml: TrailblazeYaml = TrailblazeYaml.Default): Outcome {
    // Guard BEFORE migrating: the target unified file would otherwise be picked up as a v1 input by
    // the migrator (it globs `*.trail.yaml`) and fail to decode as a list — refuse cleanly instead.
    val outName = dir.name + TRAIL_SUFFIX
    val outFile = File(dir, outName)
    require(!outFile.exists()) {
      "A unified file ($outName) already exists in ${dir.name} — it looks already migrated."
    }

    // Defense-in-depth: migration is only meaningful for a folder of LEGACY v1 per-platform files.
    // The migrator only refuses a unified file that HAS recordings — a folder of distinct *prompt-only*
    // unified trails would otherwise be merged into one and its files deleted (silent data loss; that
    // folder is reachable in the UI via the "back to implementations" arrow). Refuse if ANY input is
    // already unified — including a (nonsensical but possible) unified-format `blaze.yaml`, since the
    // migrator would fold + delete it too. A file that decodes as neither shape is left for the
    // migrator to reject. A genuine v1 file/blaze decodes as V1, so this never false-refuses a bundle.
    val candidateFiles = dir
      .listFiles { f -> f.isFile && (f.name.endsWith(TRAIL_SUFFIX) || f.name == BLAZE_FILENAME) }
      .orEmpty()
    val unifiedInputs = candidateFiles.filter { f ->
      runCatching { yaml.decodeTrailDocument(f.readText()) }.getOrNull() is TrailDocument.Unified
    }
    require(unifiedInputs.isEmpty()) {
      "Refusing to migrate ${dir.name}: ${unifiedInputs.size} file(s) are already unified " +
        "(${unifiedInputs.joinToString { it.name }}) — this isn't a legacy per-platform bundle."
    }

    val result = UnifiedTrailMigrator(yaml).migrate(dir) // throws IllegalArgumentException on refuse
    // All four drift channels, matching the CLI path (MigrateTrailsCommand) — kind drift (step-vs-
    // verify disagreement across platforms) and config drift (divergent scalar config resolved
    // first-file-wins) are the higher-stakes ones and must not be silently flattened.
    val comments = UnifiedTrailMigrator.driftComments(result.report.drift) +
      UnifiedTrailMigrator.kindDriftComments(result.report.kindDrift) +
      UnifiedTrailMigrator.memoryDriftComments(result.report.memoryDrift) +
      UnifiedTrailMigrator.configDriftComments(result.report.configDrift) +
      UnifiedTrailMigrator.droppedContentComments(result.report.droppedContent)
    val text = yaml.encodeUnifiedTrailToString(result.trail, comments)
    outFile.writeText(text)

    // Files the migrator consumed that we'd otherwise delete (per-platform files + blaze.yaml),
    // narrowed to the ones actually present. Never the output; only files that canonicalize inside
    // dir (defense in depth against a crafted name).
    val consumed = (result.report.platformFilesLoaded + BLAZE_FILENAME).distinct()
      .filter { it != outName }
      .map { File(dir, it) }
      .filter { it.isFile && it.canonicalPath.startsWith(dir.canonicalPath + File.separator) }

    // A lossy migration must not destroy its own source. When the migrator reports content it could
    // not carry, the migrated file only WARNS about it in a leading comment — the v1 file is the
    // only place that dropped content still exists. So leave both the v1 inputs and trail.yaml on
    // disk for a human to reconcile, rather than deleting the inputs. "Lossy" today means dropped
    // input keys; a future per-device runtime-divergence channel on the report would OR in here.
    // Keep this notion of lossy in step with the CLI's lossy-migration exit-code decision
    // (MigrateTrailsCommand) so the two paths don't contradict each other.
    val inputsRetained = result.report.droppedContent.isNotEmpty()

    val removed = mutableListOf<String>()
    if (!inputsRetained) {
      for (f in consumed) {
        if (f.delete()) removed += f.name
      }
    }

    // Surface WHY the inputs were retained so the route/UI (which shows driftComments) can explain
    // the v1 files still sitting beside trail.yaml. Kept out of the file's own leading comments,
    // which describe content — not this transient on-disk state that ends once the files are reconciled.
    val outcomeComments =
      if (inputsRetained) inputsRetainedComments(consumed.map { it.name }) + comments else comments
    return Outcome(outName, result.trail.trail.size, outcomeComments, removed, inputsRetained)
  }

  /** Leading lines explaining that a lossy migration left the v1 inputs in place (see [migrateFolder]). */
  private fun inputsRetainedComments(retained: List<String>): List<String> = listOf(
    "NOTE: kept the v1 input file(s) in place — this migration dropped content that did not",
    "round-trip (see the DROPPED warnings below), so the originals are the only surviving record.",
    "Reconcile the dropped content into the migrated file, then delete the v1 file(s) manually:",
    "  ${retained.joinToString()}",
  )
}
