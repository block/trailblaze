package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.main
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.models.LogsSummary
import xyz.block.trailblaze.report.snapshot.SnapshotCollector
import xyz.block.trailblaze.report.snapshot.SnapshotViewerGenerator
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File

class GenerateReportCliCommand :
  SimpleCliCommand(
    name = "generate-report",
  ) {

  private val logsDirArg = FileArgument(
    name = "logs-dir",
    help = "Directory containing Trailblaze log files",
    mustExist = true,
    canBeFile = false,
    mustBeReadable = true,
  )

  private val useRelativeImageUrlsFlag = FlagOption(
    longName = "use-relative-image-urls",
    help = "Use relative URLs for images (e.g., for Buildkite artifacts). When enabled, images are not embedded in HTML.",
    default = false,
  )

  private val logsDir: File get() = logsDirArg.value
  private val useRelativeImageUrls: Boolean get() = useRelativeImageUrlsFlag.value

  override fun parseArgs(args: Array<String>) {
    val positionalArgs = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        useRelativeImageUrlsFlag.matches(arg) -> useRelativeImageUrlsFlag.set()
        arg.startsWith("--") -> parseError("Unknown option: $arg")
        else -> positionalArgs.add(arg)
      }
      i++
    }

    if (positionalArgs.isEmpty()) {
      parseError("Missing required argument: logs-dir")
    }

    if (positionalArgs.size > 1) {
      parseError("Too many arguments")
    }

    try {
      logsDirArg.parse(positionalArgs[0])
    } catch (e: IllegalStateException) {
      parseError(e.message ?: "Invalid argument")
    }
  }

  override fun printUsage() {
    System.err.println("Usage: generate-report ${logsDirArg.getUsage()} ${useRelativeImageUrlsFlag.getUsage()}")
    System.err.println()
    System.err.println("Generate Trailblaze HTML report from logs directory")
    System.err.println()
    System.err.println("Arguments:")
    System.err.println("  ${logsDirArg.getUsage()}  ${logsDirArg.getHelp()}")
    System.err.println()
    System.err.println("Options:")
    System.err.println("  ${useRelativeImageUrlsFlag.getUsage()}  ${useRelativeImageUrlsFlag.getHelp()}")
  }

  override fun run() {
    println("logsDir: ${logsDir.canonicalPath}")
    println("useRelativeImageUrls: $useRelativeImageUrls")

    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)

    // Move the files into session directories.  This is needed after an adb pull
    moveJsonFilesToSessionDirs(logsDir)
    movePngsToSessionDirs(logsDir)

    val standaloneFileReport = true
    val logsSummaryEvents = renderSummary(logsRepo, standaloneFileReport)
    val logsSummaryJson = TrailblazeJsonInstance.encodeToString(LogsSummary.serializer(), logsSummaryEvents)
    val summaryJsonFile = File(logsDir, "summary.json")
    summaryJsonFile.writeText(logsSummaryJson)

    val trailblazeReportHtmlFile = File(logsDir, "trailblaze_report.html")
    println("file://${trailblazeReportHtmlFile.absolutePath}")

    val isInternal = File(logsRepo.logsDir.parentFile, "opensource").exists()

    val trailblazeUiProjectDir = if (isInternal) {
      File(logsRepo.logsDir.parentFile, "opensource/trailblaze-ui")
    } else {
      File(logsRepo.logsDir.parentFile, "trailblaze-ui")
    }.also {
      println("Using project directory: ${it.canonicalPath}")
    }

    WasmReport.generate(
      logsRepo = logsRepo,
      trailblazeUiProjectDir = trailblazeUiProjectDir,
      outputFile = trailblazeReportHtmlFile,
      reportTemplateFile = File(logsRepo.logsDir.parentFile, "trailblaze_report_template.html"),
      useRelativeImageUrls = useRelativeImageUrls,
    )

    // Generate snapshot viewer using pre-parsed logs from LogsRepo (integrated mode)
    // This avoids re-scanning and re-parsing all the JSON files
    generateSnapshotViewerIntegrated(logsRepo)

    // Clean up file watchers to allow JVM to exit
    logsRepo.close()
  }
}

/**
 * Generate snapshot viewer HTML using pre-parsed logs from LogsRepo.
 * 
 * This is more efficient than the standalone mode because it reuses logs
 * that have already been parsed for the main report generation, avoiding
 * duplicate file I/O and JSON parsing.
 */
private fun generateSnapshotViewerIntegrated(logsRepo: LogsRepo) {
  println()
  println("--- Generating Snapshot Viewer (integrated mode)")
  
  try {
    val snapshotViewerFile = File(logsRepo.logsDir, "snapshot_viewer.html")
    
    // Get all session IDs and their logs from LogsRepo (already parsed)
    val sessionIds = logsRepo.getSessionIds()
    println("📂 Using ${sessionIds.size} session(s) from LogsRepo")
    
    // Build maps for the collector
    val logsBySession = sessionIds.associateWith { sessionId ->
      logsRepo.getLogsForSession(sessionId)
    }
    
    val sessionInfoBySession = sessionIds.associateWith { sessionId ->
      logsRepo.getSessionInfo(sessionId)
    }
    
    // Collect snapshots from pre-parsed logs (avoids duplicate file I/O)
    val collector = SnapshotCollector(logsRepo.logsDir)
    val snapshots = collector.collectSnapshots(logsBySession, sessionInfoBySession)
    
    if (snapshots.isEmpty()) {
      println()
      println("ℹ️  No snapshots found - skipping snapshot viewer generation")
      println("   This is normal if TakeSnapshotTool was not used in any tests")
      println()
      return
    }
    
    // Print summary
    println()
    println(collector.getSummary(snapshots))
    
    // Generate HTML
    println()
    val generator = SnapshotViewerGenerator()
    generator.generateHtml(snapshots, snapshotViewerFile)
    
    println()
    println("✅ Snapshot viewer generated successfully!")
    println("   File: ${snapshotViewerFile.absolutePath}")
    println("   Size: ${snapshotViewerFile.length() / 1024} KB")
    
  } catch (e: Exception) {
    println()
    println("⚠️  Error generating snapshot viewer: ${e.message}")
    e.printStackTrace()
    // Don't fail the entire report generation if snapshot viewer fails
    println("   Continuing without snapshot viewer...")
  }
}

fun main(args: Array<String>) {
  GenerateReportCliCommand().main(args)
  GenerateTestResultsCliCommand().main(argv = args)
}

fun moveJsonFilesToSessionDirs(logsDir: File) {
  val jsonFilesInLogsDir = logsDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
  jsonFilesInLogsDir.forEach { downloadedJsonFile ->
    try {
      val log: TrailblazeLog = TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(
        downloadedJsonFile.readText(),
      )
      downloadedJsonFile.delete()

      val sessionId = log.session
      val sessionDir = File(logsDir, sessionId.value)
      sessionDir.mkdirs()

      if (log is HasScreenshot) {
        log.screenshotFile?.let { screenshotFile ->
          val currentScreenshotFileBytes = File(logsDir, screenshotFile).readBytes()
          sessionDir.delete()
          val destScreenshotFile = File(sessionDir, screenshotFile)
          destScreenshotFile.writeBytes(currentScreenshotFileBytes)
        }
        val screenshotFileInSessionDirPath = "${sessionId.value}/${log.screenshotFile}"
        when (log) {
          is TrailblazeLog.MaestroDriverLog -> log.copy(
            screenshotFile = screenshotFileInSessionDirPath,
          )

          is TrailblazeLog.TrailblazeLlmRequestLog -> log.copy(
            screenshotFile = screenshotFileInSessionDirPath,
          )

          else -> {}
        }
      }

      val outputFile = File(
        sessionDir,
        downloadedJsonFile.nameWithoutExtension + "${log::class.java.simpleName}.json",
      )

      outputFile.writeText(TrailblazeJsonInstance.encodeToString(log))
      println("Deleting ${downloadedJsonFile.canonicalPath}")
    } catch (e: Exception) {
      println("Error processing ${downloadedJsonFile.absolutePath}: ${e.message}")
    }
  }
}

fun movePngsToSessionDirs(logsDir: File) {
  val pngFilesInLogsDir = logsDir.listFiles()?.filter { it.extension == "png" } ?: emptyList()
  pngFilesInLogsDir.forEach { pngFile ->
    try {
      // Filename format is: {sessionId}_{timestamp}.png
      // We need to extract everything before the last underscore (which is the timestamp)
      val sessionId = pngFile.nameWithoutExtension.substringBeforeLast("_")

      if (sessionId.isNotEmpty()) {
        val sessionDir = File(logsDir, sessionId)
        sessionDir.mkdirs()

        val destFile = File(sessionDir, pngFile.name)
        pngFile.copyTo(destFile, overwrite = true)
        pngFile.delete()
        println("Moved ${pngFile.name} to session directory: $sessionId")
      } else {
        println("Could not determine session ID for PNG file: ${pngFile.name}, skipping")
      }
    } catch (e: Exception) {
      println("Error processing PNG file ${pngFile.absolutePath}: ${e.message}")
    }
  }
}

fun renderSummary(logsRepo: LogsRepo, isStandaloneFileReport: Boolean): LogsSummary {
  val map = logsRepo.getSessionIds().associateWith { logsRepo.getLogsForSession(it) }
  val logsSummary = LogsSummary.fromLogs(map.mapKeys { it.key.value }, isStandaloneFileReport)
  return logsSummary
}

fun getStatusMessage(agentTaskStatus: AgentTaskStatus?): String = when (agentTaskStatus) {
  is AgentTaskStatus.Failure.MaxCallsLimitReached ->

    buildString {
      append("Failed, Maximum Calls Limit Reached ${agentTaskStatus.statusData.callCount}")
      append(" in ${agentTaskStatus.statusData.totalDurationMs / 1000} seconds")
    }

  is AgentTaskStatus.Failure.ObjectiveFailed -> buildString {
    append("Objective Failed after ${agentTaskStatus.statusData.callCount} Calls")
    append(" in ${agentTaskStatus.statusData.totalDurationMs / 1000} seconds")
    append(" with agent reason: \"${agentTaskStatus.llmExplanation}\"")
  }

  is AgentTaskStatus.InProgress -> "Running, ${agentTaskStatus.statusData.callCount} LLM Requests so far. "
  is AgentTaskStatus.Success.ObjectiveComplete -> buildString {
    append("Successfully Completed after ${agentTaskStatus.statusData.callCount} Calls")
    append(" in ${agentTaskStatus.statusData.totalDurationMs / 1000} seconds")
    append(" with agent reason: \"${agentTaskStatus.llmExplanation}\"")
  }

  null -> "Session Not Found"
}
