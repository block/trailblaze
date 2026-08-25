package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.util.Console

/**
 * CLI command that turns one or more `test_report.json` files into a RUN INDEX — the report's
 * device-classifier matrix, where each cell links out to that run's own report rather than
 * embedding it. See [RunIndexGenerator] for why a CI build needs this instead of the embedded
 * report.
 *
 * Reached through the `generateRunIndex` Gradle task, beside `generateTestResultsArtifacts`:
 *
 * ```
 * ./gradlew :trailblaze-report:generateRunIndex --args="\
 *   reports/summary-android/test_report.json reports/summary-ios/test_report.json \
 *   --viewer-base-url https://reports.example/viewer/<sha>/index.html \
 *   --output trailblaze_report_index.html"
 * ```
 *
 * The viewer URL is a parameter, never a default: where a repo hosts its report viewer and its
 * session archives is that repo's deployment detail, not the framework's.
 */
class GenerateRunIndexCliCommand(
  private val generator: RunIndexGenerator = RunIndexGenerator(),
) : CliktCommand(name = "generate-run-index") {

  private val reportFiles by argument(
    name = "test-report-json",
    help = "One or more Trailblaze test_report.json files (CiSummaryReport). Rows from all of " +
      "them share one matrix, so a build's per-device shards and a nightly's several configs " +
      "produce a single index.",
  ).file(mustExist = true, canBeDir = false, mustBeReadable = true).multiple(required = true)

  private val viewerBaseUrl by option(
    "--viewer-base-url",
    help = "Absolute http(s) URL of the report viewer each cell links to. A run's session archive " +
      "is appended as `?zip=<url-encoded logs_zip_url>`. Omit it and the index renders outcomes " +
      "without links.",
  ).default("")

  private val output by option(
    "--output",
    help = "Where to write the index HTML.",
  ).file(mustExist = false, canBeDir = false).default(File("trailblaze_report_index.html"))

  override fun run() {
    val reports = reportFiles.map { file ->
      runCatching { JSON.decodeFromString<CiSummaryReport>(file.readText()) }
        .getOrElse { error("Could not read ${file.absolutePath} as a Trailblaze test report: ${it.message}") }
    }
    val rowCount = reports.sumOf { it.results.size }
    if (rowCount == 0) {
      // Not an error: a config whose every shard was skipped legitimately reports nothing, and
      // failing here would turn an empty run into a red step for the wrong reason.
      Console.log("[RunIndexGenerator] no results across ${reports.size} report(s); no index written.")
      return
    }
    Console.log("[RunIndexGenerator] indexing $rowCount result(s) from ${reports.size} report(s).")
    if (viewerBaseUrl.isBlank()) {
      Console.log("[RunIndexGenerator] no --viewer-base-url: cells will show outcomes without links.")
    } else {
      // Fail rather than emit an index whose every cell is dead. The viewer resolves each
      // `meta.reportUrl` through a strict http(s) check, so a relative URL — `viewer.html`, the
      // obvious thing to pass when hosting the shell `trailblaze viewer` writes beside the index —
      // renders all N cells inert with nothing said at generation time. That failure is invisible
      // until someone opens the artifact and clicks.
      val parsed = runCatching { URI(viewerBaseUrl.trim()) }.getOrNull()
      require(parsed?.scheme?.lowercase() in setOf("http", "https")) {
        "--viewer-base-url must be an absolute http(s) URL, got \"$viewerBaseUrl\". " +
          "The viewer refuses any other form, so every cell in the index would render unlinked. " +
          "Pass the URL the viewer is served from, e.g. https://reports.example/viewer/index.html."
      }
    }

    val written = generator.generate(reports, viewerBaseUrl.takeIf { it.isNotBlank() }, output)
      ?: error("Could not generate the run index. See the [RunReportGenerator] output above.")
    Console.log("file://${written.absolutePath}")
  }

  companion object {
    // Results files grow fields over time and an index only reads a handful of them, so an older
    // or newer producer's extra keys must not fail the parse.
    private val JSON = Json { ignoreUnknownKeys = true }
  }
}

fun main(args: Array<String>) = GenerateRunIndexCliCommand().main(args)
