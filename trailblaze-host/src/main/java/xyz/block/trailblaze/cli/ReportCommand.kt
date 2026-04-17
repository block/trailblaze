package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable
import kotlin.system.exitProcess

/**
 * Generate an HTML report for sessions in the logs directory.
 *
 * Examples:
 *   trailblaze report              - Generate report for all sessions
 *   trailblaze report --open       - Generate and open in browser
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
    names = ["--open"],
    description = ["Open the report in the default browser after generation"]
  )
  var open: Boolean = false

  override fun call(): Int {
    val app = parent.appProvider()
    val logsRepo = app.deviceManager.logsRepo

    val sessionIds = logsRepo.getSessionIds()
    if (sessionIds.isEmpty()) {
      Console.log("No sessions found in logs directory.")
      return CommandLine.ExitCode.OK
    }

    Console.log("Generating report for ${sessionIds.size} session(s)...")

    val reportGenerator = app.createCliReportGenerator()
    val reportFile = reportGenerator.generateReport(logsRepo, sessionIds)
    if (reportFile == null) {
      Console.error("Failed to generate report. No report template found.")
      Console.error("Ensure trailblaze_report_template.html is bundled or at the git root.")
      return CommandLine.ExitCode.SOFTWARE
    }

    Console.info("\nReport: file://${reportFile.absolutePath}")

    if (open) {
      TrailblazeDesktopUtil.openInDefaultBrowser("file://${reportFile.absolutePath}")
    }

    exitProcess(CommandLine.ExitCode.OK)
  }
}
