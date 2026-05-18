package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.ui.TrailblazeDesktopApp
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.io.File
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
 *   trailblaze report --id abc123 --video out.mp4
 *                                         - Render the timeline autoplay as an MP4 via a
 *                                           headless Playwright browser (no extra log
 *                                           noise — the HTML report's own playbackSpeed
 *                                           default of 2x decides the export length).
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

  @Option(
    names = ["--video"],
    description = [
      "Export the HTML report's timeline autoplay as an MP4 to the given path. " +
        "Drives a headless Playwright browser internally; playback speed comes from the " +
        "report's own UI default (2x today). Implies --format=html and requires --id.",
    ],
  )
  var videoOutput: File? = null

  @Option(
    names = ["--video-show-browser"],
    description = [
      "When exporting via --video, show the Playwright browser window instead of running " +
        "it headless. Useful for debugging the export.",
    ],
  )
  var videoShowBrowser: Boolean = false

  override fun call(): Int {
    val video = videoOutput
    if (video != null) {
      if (format != ReportFormat.HTML) {
        Console.error("--video only works with --format html (JSON has no timeline to record).")
        return CommandLine.ExitCode.USAGE
      }
      if (id == null) {
        Console.error(
          "--video requires --id <session-id>. Multi-session reports don't auto-advance to a " +
            "timeline view, so the autoplay trigger would never fire.",
        )
        return CommandLine.ExitCode.USAGE
      }
    } else if (videoShowBrowser) {
      // --video-show-browser only does anything when an export run is happening; failing
      // loudly here beats silently ignoring it and leaving the user wondering why they
      // didn't see the browser window they asked for.
      Console.error(
        "--video-show-browser only applies when --video is also set " +
          "(it controls headlessness of the export run).",
      )
      return CommandLine.ExitCode.USAGE
    }
    return generateSessionReport(
      parent.appProvider(),
      id,
      open,
      format,
      videoOutput = video,
      videoShowBrowser = videoShowBrowser,
    )
  }
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
  videoOutput: File? = null,
  videoShowBrowser: Boolean = false,
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

  if (videoOutput != null) {
    try {
      Console.log("Exporting timeline autoplay to ${videoOutput.absolutePath} ...")
      ReportVideoExporter.export(
        reportHtml = reportFile,
        outputMp4 = videoOutput,
        headless = !videoShowBrowser,
      )
      Console.info("Video: ${videoOutput.absolutePath}")
    } catch (e: Exception) {
      Console.error("Failed to export report video: ${e.message}")
      return CommandLine.ExitCode.SOFTWARE
    }
  }

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
