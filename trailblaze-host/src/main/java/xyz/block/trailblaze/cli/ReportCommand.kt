package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * Generate an HTML report for sessions in the logs directory.
 *
 * Examples:
 *   trailblaze report                     - Generate HTML report for all sessions
 *   trailblaze report --id abc123         - Generate HTML report for a single session
 *   trailblaze report --open              - Generate and open in browser
 *   trailblaze report --format json       - Emit a CI-style JSON test-results artifact
 *   trailblaze report --format json --id abc123
 */
@Command(
  name = "report",
  mixinStandardHelpOptions = true,
  description = ["Generate an HTML or JSON report from session recordings"]
)
class ReportCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Option(
    names = ["--id"],
    description = ["Session ID to report on (defaults to all sessions)"],
  )
  var id: String? = null

  @Option(
    names = ["--open"],
    description = ["Open the report in the default browser after generation (HTML only)"]
  )
  var open: Boolean = false

  @Option(
    names = ["--format"],
    description = ["Output format: html (default) or json — JSON emits a CiSummaryReport artifact"]
  )
  var format: ReportFormat = ReportFormat.HTML

  override fun call(): Int = generateSessionReport(parent.appProvider(), id, open, format)
}

/**
 * Output format for `trailblaze report` / `trailblaze session report`.
 *
 * `JSON` produces a [xyz.block.trailblaze.report.models.CiSummaryReport] artifact —
 * the same schema emitted by `:trailblaze-report:generateTestResultsArtifacts` so CI
 * pipelines can consume both surfaces interchangeably.
 */
enum class ReportFormat { HTML, JSON }

/**
 * Generate an HTML report for one or all sessions. Shared by [ReportCommand]
 * and [SessionReportCommand] so both entry points produce identical output.
 *
 * @param sessionId When non-null, narrows the report to a single session.
 *   Prefix matching is applied so callers can pass an abbreviated ID.
 */
internal fun generateSessionReport(
  app: TrailblazeDesktopApp,
  sessionId: String?,
  open: Boolean,
  format: ReportFormat = ReportFormat.HTML,
): Int {
  val logsRepo = app.deviceManager.logsRepo
  val allIds = logsRepo.getSessionIds()
  if (allIds.isEmpty()) {
    Console.log("No sessions found in logs directory.")
    return CommandLine.ExitCode.OK
  }

  val sessionIds = if (sessionId != null) {
    val matches = allIds.filter { it.value == sessionId || it.value.startsWith(sessionId) }
    if (matches.isEmpty()) {
      Console.error("Error: No session matching '$sessionId' found.")
      return CommandLine.ExitCode.SOFTWARE
    }
    if (matches.size > 1) {
      Console.error("Error: Session prefix '$sessionId' is ambiguous: ${matches.joinToString(", ") { it.value }}")
      return CommandLine.ExitCode.SOFTWARE
    }
    matches
  } else {
    allIds
  }

  Console.log("Generating ${format.name.lowercase()} report for ${sessionIds.size} session(s)...")

  val reportGenerator = app.createCliReportGenerator()
  val reportFile = when (format) {
    ReportFormat.HTML -> reportGenerator.generateReport(logsRepo, sessionIds)
    ReportFormat.JSON -> reportGenerator.generateJsonReport(logsRepo, sessionIds)
  }
  if (reportFile == null) {
    when (format) {
      ReportFormat.HTML -> {
        Console.error("Failed to generate HTML report. No report template found.")
        Console.error("Ensure trailblaze_report_template.html is bundled or at the git root.")
      }
      ReportFormat.JSON -> {
        Console.error("Failed to generate JSON report — no resolvable session info for the requested ID(s).")
      }
    }
    return CommandLine.ExitCode.SOFTWARE
  }

  Console.info("\nReport: file://${reportFile.absolutePath}")

  if (open) {
    if (format == ReportFormat.HTML) {
      TrailblazeDesktopUtil.openInDefaultBrowser("file://${reportFile.absolutePath}")
    } else {
      // JSON artifacts aren't browser-renderable; surface a hint instead of silently
      // ignoring --open so users notice the flag has no effect for this format.
      Console.info("(--open is ignored for --format json; opening it in a browser would just dump raw JSON.)")
    }
  }

  // Background threads spawned by the report generator keep the JVM alive after
  // a successful run; force exit to match the prior `trailblaze report` behavior.
  exitProcess(CommandLine.ExitCode.OK)
}
