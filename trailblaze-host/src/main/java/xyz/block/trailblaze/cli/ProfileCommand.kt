package xyz.block.trailblaze.cli

import java.io.File
import java.util.concurrent.Callable
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.PerformanceAnalysisGenerator
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console

/**
 * Generate the performance-analysis report - the Instruments-style trail-session profiler
 * ([PerformanceAnalysisGenerator]) - for the sessions in a logs directory. Ad-hoc by design:
 * `trailblaze run` / `trailblaze report` don't emit this report; you ask for it when you're
 * optimizing a trail.
 *
 * Runs entirely standalone (no daemon): reads the logs from disk, profiles every session found,
 * and writes one self-contained HTML file at `<logs-dir>/trailblaze_performance_analysis.html`.
 *
 * Examples:
 *   trailblaze profile                 - profile the configured logs directory
 *   trailblaze profile ./logs          - profile a specific logs directory (e.g. CI artifacts)
 *   trailblaze profile ./logs --open   - ...and open the report in a browser
 */
@Command(
  name = "profile",
  mixinStandardHelpOptions = true,
  description = [
    "Generate the performance-analysis report (an Instruments-style time profiler over each " +
      "session's tools, LLM calls, timeouts, and idle gaps) for a logs directory. " +
      "Defaults to the configured logs directory when <logs-dir> is omitted. Writes " +
      "<logs-dir>/trailblaze_performance_analysis.html. Requires `bun` on PATH.",
  ],
)
class ProfileCommand : Callable<Int> {

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<logs-dir>",
    description = [
      "Logs directory to profile (the directory holding per-session subdirectories). " +
        "Defaults to the configured logs directory.",
    ],
  )
  var logsDir: File? = null

  @Option(
    names = ["--open"],
    description = ["Open the report in the default browser after generation."],
  )
  var open: Boolean = false

  override fun call(): Int {
    val dir = logsDir ?: File(TrailblazeDesktopUtil.getEffectiveLogsDirectory(CliConfigHelper.getOrCreateConfig()))
    if (!dir.isDirectory) {
      reportCliError(
        verb = "Profile",
        target = dir.path,
        reason = "logs directory not found",
        hint = "pass the directory holding per-session log subdirectories, e.g. `trailblaze profile ./logs`",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    // Bare directory enumeration (same order as LogsRepo.getSessionIds): constructing a
    // single-read LogsRepo here would parse every session's logs at init, and the generator's
    // snapshot capture reads them again — the profiler needs exactly one read.
    val sessionIds = dir.listFiles()
      ?.filter { it.isDirectory }
      ?.sortedByDescending { it.name }
      ?.map { SessionId(it.name) }
      ?: emptyList()
    if (sessionIds.isEmpty()) {
      reportCliError(
        verb = "Profile",
        target = dir.path,
        reason = "no sessions found in this logs directory",
        hint = "run a trail first, or point at a directory that holds per-session log subdirectories",
      )
      return TrailblazeExitCode.MISUSE.code
    }
    val generator = PerformanceAnalysisGenerator()
    if (!generator.isBunAvailable) {
      reportCliError(
        verb = "Profile",
        target = dir.path,
        reason = "`bun` not found on PATH (the profiler runs on bun)",
        hint = "install bun (https://bun.sh), or `source bin/activate-hermit` in a repo checkout",
      )
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    val generated = generator.generate(dir, sessionIds)
    if (generated == null) {
      reportCliError(
        verb = "Profile",
        target = dir.path,
        reason = "report generation failed (see the [PerformanceAnalysisGenerator] log lines above)",
        hint = "re-run with -v for the subprocess output",
      )
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    val canonical = File(dir, "trailblaze_performance_analysis.html")
    generated.copyTo(canonical, overwrite = true)
    generated.delete()
    Console.info("Performance analysis: file://${canonical.absolutePath}")
    if (open) TrailblazeDesktopUtil.openInDefaultBrowser("file://${canonical.absolutePath}")
    return TrailblazeExitCode.SUCCESS.code
  }
}
