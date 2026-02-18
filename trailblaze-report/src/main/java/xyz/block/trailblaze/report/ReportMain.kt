package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.main
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.report.models.LogsSummary
import xyz.block.trailblaze.report.snapshot.SnapshotCollector
import xyz.block.trailblaze.report.snapshot.SnapshotViewerGenerator
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import xyz.block.trailblaze.util.Console

open class GenerateReportCliCommand :
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
    Console.error("Usage: generate-report ${logsDirArg.getUsage()} ${useRelativeImageUrlsFlag.getUsage()}")
    Console.error("")
    Console.error("Generate Trailblaze HTML report from logs directory")
    Console.error("")
    Console.error("Arguments:")
    Console.error("  ${logsDirArg.getUsage()}  ${logsDirArg.getHelp()}")
    Console.error("")
    Console.error("Options:")
    Console.error("  ${useRelativeImageUrlsFlag.getUsage()}  ${useRelativeImageUrlsFlag.getHelp()}")
  }

  override fun run() {
    Console.log("logsDir: ${logsDir.canonicalPath}")
    Console.log("useRelativeImageUrls: $useRelativeImageUrls")

    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)

    // Move the files into session directories.  This is needed after an adb pull
    moveJsonFilesToSessionDirs(logsDir)
    movePngsToSessionDirs(logsDir)

    val standaloneFileReport = true
    val logsSummaryEvents = renderSummary(logsRepo, standaloneFileReport)
    val logsSummaryJson = TrailblazeJsonInstance.encodeToString(LogsSummary.serializer(), logsSummaryEvents)
    val summaryJsonFile = File(logsDir, "summary.json")
    summaryJsonFile.writeText(logsSummaryJson)

    val rootWorkingDir = logsRepo.logsDir.parentFile

    val trailblazeReportHtmlFile = File(logsDir, "trailblaze_report.html")
    Console.log("file://${trailblazeReportHtmlFile.absolutePath}")

    val isInternal = File(rootWorkingDir, "opensource").exists()

    val trailblazeUiProjectDir = if (isInternal) {
      File(rootWorkingDir, "opensource/trailblaze-ui")
    } else {
      File(rootWorkingDir, "trailblaze-ui")
    }.also {
      Console.log("Using project directory: ${it.canonicalPath}")
    }

    WasmReport.generate(
      logsRepo = logsRepo,
      trailblazeUiProjectDir = trailblazeUiProjectDir,
      outputFile = trailblazeReportHtmlFile,
      reportTemplateFile = File(rootWorkingDir, "trailblaze_report_template.html"),
      useRelativeImageUrls = useRelativeImageUrls,
    )

    afterReportGenerated(logsRepo, rootWorkingDir)

    // Generate snapshot viewer using pre-parsed logs from LogsRepo (integrated mode)
    // This avoids re-scanning and re-parsing all the JSON files
    generateSnapshotViewerIntegrated(logsRepo)

    // Clean up file watchers to allow JVM to exit
    logsRepo.close()
  }

  /**
   * Hook for subclasses to perform additional processing after the report is generated.
   * Called after the WASM report is generated but before snapshot viewer generation.
   */
  protected open fun afterReportGenerated(logsRepo: LogsRepo, rootWorkingDir: File) {
    // No-op in base class
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
  Console.log("")
  Console.log("--- Generating Snapshot Viewer (integrated mode)")

  try {
    val snapshotViewerFile = File(logsRepo.logsDir, "snapshot_viewer.html")

    // Get all session IDs and their logs from LogsRepo (already parsed)
    val sessionIds = logsRepo.getSessionIds()
    Console.log("📂 Using ${sessionIds.size} session(s) from LogsRepo")

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
      Console.log("")
      Console.log("ℹ️  No snapshots found - skipping snapshot viewer generation")
      Console.log("   This is normal if TakeSnapshotTool was not used in any tests")
      Console.log("")
      return
    }

    // Print summary
    Console.log("")
    Console.log(collector.getSummary(snapshots))

    // Generate HTML
    Console.log("")
    val generator = SnapshotViewerGenerator()
    generator.generateHtml(snapshots, snapshotViewerFile)

    Console.log("")
    Console.log("✅ Snapshot viewer generated successfully!")
    Console.log("   File: ${snapshotViewerFile.absolutePath}")
    Console.log("   Size: ${snapshotViewerFile.length() / 1024} KB")

  } catch (e: Exception) {
    Console.log("")
    Console.log("⚠️  Error generating snapshot viewer: ${e.message}")
    e.printStackTrace()
    // Don't fail the entire report generation if snapshot viewer fails
    Console.log("   Continuing without snapshot viewer...")
  }
}

fun main(args: Array<String>) {
  GenerateReportCliCommand().main(args)
  // Filter out flags that are specific to GenerateReportCliCommand
  val filteredArgs = args.filter { it != "--use-relative-image-urls" }.toTypedArray()
  GenerateTestResultsCliCommand().main(argv = filteredArgs)
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
      Console.log("Deleting ${downloadedJsonFile.canonicalPath}")
    } catch (e: Exception) {
      Console.log("Error processing ${downloadedJsonFile.absolutePath}: ${e.message}")
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
        Console.log("Moved ${pngFile.name} to session directory: $sessionId")
      } else {
        Console.log("Could not determine session ID for PNG file: ${pngFile.name}, skipping")
      }
    } catch (e: Exception) {
      Console.log("Error processing PNG file ${pngFile.absolutePath}: ${e.message}")
    }
  }
}

fun renderSummary(logsRepo: LogsRepo, isStandaloneFileReport: Boolean): LogsSummary {
  val map = logsRepo.getSessionIds().associateWith { logsRepo.getLogsForSession(it) }
  val logsSummary = LogsSummary.fromLogs(map.mapKeys { it.key.value }, isStandaloneFileReport)
  return logsSummary
}
