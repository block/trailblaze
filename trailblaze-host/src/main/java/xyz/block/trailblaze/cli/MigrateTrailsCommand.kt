package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.migration.UnifiedTrailMigrator
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailblazeYaml
import java.io.File
import java.util.concurrent.Callable

/**
 * Migrate a directory of legacy v1 `*.trail.yaml` files (plus optional
 * `blaze.yaml`) into a single unified `trail.yaml` file. See
 * `docs/devlog/2026-05-22-trail-yaml-unified-syntax.md` for the unified-format
 * spec.
 *
 * Usage:
 *
 * ```
 * trailblaze migrate-trails <input-dir>
 * trailblaze migrate-trails <input-dir> --output <path>
 * ```
 *
 * The command does NOT delete the input files — removing them is an operator
 * decision once the unified file has been reviewed.
 *
 * A lossy migration (input content the decoder couldn't round-trip) still writes
 * a usable unified file with leading `DROPPED` warning comments and exits 0 by
 * default. Pass `--fail-on-dropped-content` to make that case exit non-zero so a
 * chained pipeline (`migrate-trails && commit`) refuses to proceed on a lossy
 * migration.
 */
@Command(
  name = "migrate-trails",
  mixinStandardHelpOptions = true,
  hidden = true,
  description = [
    "Migrate a directory of legacy *.trail.yaml files (plus optional blaze.yaml) " +
      "into a single unified trail.yaml file. Does NOT delete the input files. " +
      "Hidden from `--help` because almost no one has v1 trails — this is a " +
      "one-shot migration utility, not part of the public surface. Still callable " +
      "via `trailblaze migrate-trails <input-dir>` when needed.",
  ],
)
class MigrateTrailsCommand : Callable<Int> {

  @Parameters(
    arity = "1",
    paramLabel = "<input-dir>",
    description = [
      "Directory containing one or more `<classifier>.trail.yaml` files. " +
        "The filename minus `.trail.yaml` is the device classifier (e.g. " +
        "`android-phone.trail.yaml` → `android-phone`).",
    ],
  )
  var inputDir: File? = null

  @Option(
    names = ["--output", "-o"],
    description = [
      "Path to write the unified file. Defaults to `<input-dir>/trail.yaml`.",
    ],
  )
  var outputPath: File? = null

  @Option(
    names = ["--fail-on-dropped-content"],
    description = [
      "Exit non-zero (ASSERTION_FAILED) when the migration drops input content that did " +
        "not round-trip — schema-unknown keys, or sibling keys in a tool entry the tool " +
        "decoder discards. Off by default: a lossy migration still writes a usable file " +
        "with leading DROPPED warning comments and exits 0. Turn this on in a pipeline " +
        "that must refuse to chain (e.g. `migrate-trails && commit`) on a lossy migration.",
    ],
  )
  var failOnDroppedContent: Boolean = false

  override fun call(): Int {
    val dir = inputDir ?: run {
      Console.error("trailblaze migrate-trails: <input-dir> is required.")
      return EXIT_USAGE
    }
    if (!dir.isDirectory) {
      Console.error("trailblaze migrate-trails: $dir is not a directory.")
      return EXIT_USAGE
    }

    val result = try {
      UnifiedTrailMigrator(TrailblazeYaml.Default).migrate(dir)
    } catch (e: IllegalArgumentException) {
      Console.error("trailblaze migrate-trails: ${e.message ?: e::class.simpleName}")
      return EXIT_USAGE
    } catch (e: Exception) {
      Console.error("trailblaze migrate-trails: migration failed: ${e.message ?: e::class.simpleName}")
      return EXIT_INFRA
    }

    val drift = UnifiedTrailMigrator.driftComments(result.report.drift) +
      UnifiedTrailMigrator.kindDriftComments(result.report.kindDrift) +
      UnifiedTrailMigrator.memoryDriftComments(result.report.memoryDrift) +
      UnifiedTrailMigrator.configDriftComments(result.report.configDrift) +
      UnifiedTrailMigrator.droppedContentComments(result.report.droppedContent)
    val yamlText = TrailblazeYaml.Default.encodeUnifiedTrailToString(
      trail = result.trail,
      leadingComments = drift,
    )

    val output = outputPath ?: File(dir, "trail.yaml")

    // Refuse to overwrite a source file — `--output` could accidentally point
    // at one of the inputs (e.g. `--output android-phone.trail.yaml`, or
    // `--output blaze.yaml` on a blaze-only migration) and we would otherwise
    // destroy that input mid-migration.
    val inputNames = result.report.platformFilesLoaded +
      if (result.report.blazeLoaded) listOf(TrailRecordings.BLAZE_DOT_YAML) else emptyList()
    val inputPaths = inputNames
      .map { File(dir, it).canonicalPath }
      .toSet()
    if (output.canonicalPath in inputPaths) {
      Console.error(
        "trailblaze migrate-trails: refusing to write output to $output — it is one " +
          "of the input files. Pick a different --output path.",
      )
      return EXIT_USAGE
    }

    try {
      output.writeText(yamlText)
    } catch (e: java.io.IOException) {
      Console.error(
        "trailblaze migrate-trails: failed to write output to $output: " +
          "${e.message ?: e::class.simpleName}",
      )
      return EXIT_INFRA
    }

    val sourceCount = result.report.platformFilesLoaded.size +
      if (result.report.blazeLoaded) 1 else 0
    Console.log("Migrated $sourceCount source file(s) → 1 unified file")
    Console.log("Output: ${output.absolutePath}")
    Console.log("Steps: ${result.trail.trail.size}")
    Console.log("Drift warnings: ${result.report.drift.size}")
    if (result.report.kindDrift.isNotEmpty()) {
      Console.log("Kind drift warnings (step: vs verify:): ${result.report.kindDrift.size}")
    }
    if (result.report.droppedContent.isNotEmpty()) {
      Console.log("Dropped (un-round-trippable) keys: ${result.report.droppedContent.size}")
    }
    if (result.report.familyCollapses.isNotEmpty()) {
      val summary = result.report.familyCollapses.groupBy { it.family }.map { (family, entries) ->
        val collapsed = entries.count { !it.diverged }
        val diverged = entries.count { it.diverged }
        "$family(${collapsed}c/${diverged}d)"
      }.joinToString(", ")
      Console.log("Family-collapses: $summary")
    }

    // The file is always written (with its DROPPED warnings) so the artifact is reviewable;
    // --fail-on-dropped-content only changes the exit code so a chained pipeline can refuse
    // to proceed on a lossy migration. Off by default → today's exit-0 behavior is preserved.
    val dropped = result.report.droppedContent
    if (failOnDroppedContent && dropped.isNotEmpty()) {
      Console.error(
        "trailblaze migrate-trails: --fail-on-dropped-content is set and the migration dropped " +
          "${dropped.size} un-round-trippable key(s):",
      )
      // List the first few so a CI log is actionable without opening the artifact. The full set
      // (with YAML paths) is always in the DROPPED warning block at the top of the output file.
      for (entry in dropped.take(MAX_DROPPED_LISTED)) {
        Console.error("  dropped `${entry.key}` at ${entry.path} (${entry.file} line ${entry.line})")
      }
      if (dropped.size > MAX_DROPPED_LISTED) {
        Console.error("  ... and ${dropped.size - MAX_DROPPED_LISTED} more")
      }
      Console.error("  See the DROPPED warnings in ${output.absolutePath}.")
      return EXIT_ASSERTION
    }
    return EXIT_OK
  }

  private companion object {
    val EXIT_OK: Int = TrailblazeExitCode.SUCCESS.code
    // The lossy-migration gate (--fail-on-dropped-content) is ASSERTION_FAILED (1): the migrator
    // ran and produced a usable file, but content was dropped — a "ran successfully, wrong-ish
    // outcome" a chained `migrate-trails && commit` should treat as "do not proceed."
    val EXIT_ASSERTION: Int = TrailblazeExitCode.ASSERTION_FAILED.code
    // A migrator crash or an output-write IOException is INFRA_FAILED (2) per TrailblazeExitCode —
    // "we couldn't do the work," distinct from the assertion-tier gate above.
    val EXIT_INFRA: Int = TrailblazeExitCode.INFRA_FAILED.code
    val EXIT_USAGE: Int = TrailblazeExitCode.MISUSE.code

    // Cap on how many dropped-content entries the --fail-on-dropped-content error lists to
    // stderr — enough to be actionable in a CI log; the full set lives in the output file.
    const val MAX_DROPPED_LISTED = 5
  }
}
