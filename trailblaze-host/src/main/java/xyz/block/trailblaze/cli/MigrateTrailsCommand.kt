package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.migration.TrailMigrator
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
      TrailMigrator(TrailblazeYaml.Default).migrate(dir)
    } catch (e: IllegalArgumentException) {
      Console.error("trailblaze migrate-trails: ${e.message ?: e::class.simpleName}")
      return EXIT_USAGE
    } catch (e: Exception) {
      Console.error("trailblaze migrate-trails: migration failed: ${e.message ?: e::class.simpleName}")
      return EXIT_FAILURE
    }

    val drift = TrailMigrator.driftComments(result.report.drift) +
      TrailMigrator.memoryDriftComments(result.report.memoryDrift)
    val yamlText = TrailblazeYaml.Default.encodeUnifiedTrailToString(
      trail = result.trail,
      leadingComments = drift,
    )

    val output = outputPath ?: File(dir, "trail.yaml")

    // Refuse to overwrite a source file — `--output` could accidentally point
    // at one of the inputs (e.g. `--output android-phone.trail.yaml`) and we
    // would otherwise destroy that input mid-migration.
    val inputPaths = result.report.platformFilesLoaded
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
      return EXIT_FAILURE
    }

    Console.log(
      "Migrated ${result.report.platformFilesLoaded.size} platform file(s) → 1 unified file",
    )
    Console.log("Output: ${output.absolutePath}")
    Console.log("Steps: ${result.trail.trail.size}")
    Console.log("Drift warnings: ${result.report.drift.size}")
    if (result.report.familyCollapses.isNotEmpty()) {
      val summary = result.report.familyCollapses.groupBy { it.family }.map { (family, entries) ->
        val collapsed = entries.count { !it.diverged }
        val diverged = entries.count { it.diverged }
        "$family(${collapsed}c/${diverged}d)"
      }.joinToString(", ")
      Console.log("Family-collapses: $summary")
    }
    Console.log("Steps marked recordable: false: ${result.report.unrecordableSteps}")
    return EXIT_OK
  }

  private companion object {
    val EXIT_OK: Int = TrailblazeExitCode.SUCCESS.code
    // Trail-migration failures are closest to ASSERTION_FAILED (1) — the
    // migrator ran successfully but produced an outcome (some trails didn't
    // migrate cleanly) that a chained command (`migrate-trails && commit`)
    // should treat as "do not proceed."
    val EXIT_FAILURE: Int = TrailblazeExitCode.ASSERTION_FAILED.code
    val EXIT_USAGE: Int = TrailblazeExitCode.MISUSE.code
  }
}
