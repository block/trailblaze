package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.main
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.llm.LlmLogCostEnricher
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.report.models.LogsSummary
import xyz.block.trailblaze.report.snapshot.SnapshotCollector
import xyz.block.trailblaze.report.snapshot.SnapshotViewerGenerator
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import java.util.concurrent.CompletableFuture
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

  private val noWasmReportFlag = FlagOption(
    longName = "no-wasm-report",
    help = "Skip the legacy WASM report (trailblaze_report.html); emit only the interactive report.",
    default = false,
  )

  private val logsDir: File get() = logsDirArg.value
  private val useRelativeImageUrls: Boolean get() = useRelativeImageUrlsFlag.value
  private val skipWasmReport: Boolean get() = noWasmReportFlag.value

  override fun parseArgs(args: Array<String>) {
    val positionalArgs = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        useRelativeImageUrlsFlag.matches(arg) -> useRelativeImageUrlsFlag.set()
        noWasmReportFlag.matches(arg) -> noWasmReportFlag.set()
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
    Console.error("Usage: generate-report ${logsDirArg.getUsage()} ${useRelativeImageUrlsFlag.getUsage()} ${noWasmReportFlag.getUsage()}")
    Console.error("")
    Console.error("Generate Trailblaze HTML report from logs directory")
    Console.error("")
    Console.error("Arguments:")
    Console.error("  ${logsDirArg.getUsage()}  ${logsDirArg.getHelp()}")
    Console.error("")
    Console.error("Options:")
    Console.error("  ${useRelativeImageUrlsFlag.getUsage()}  ${useRelativeImageUrlsFlag.getHelp()}")
    Console.error("  ${noWasmReportFlag.getUsage()}  ${noWasmReportFlag.getHelp()}")
  }

  override fun run() {
    Console.log("logsDir: ${logsDir.canonicalPath}")
    Console.log("useRelativeImageUrls: $useRelativeImageUrls")

    // Reorganize adb-pulled files into per-session directories BEFORE constructing LogsRepo.
    // LogsRepo's single-read cache is built at construction, so doing the moves first means that
    // cache captures the final on-disk layout. Every emitter below can then share ONE parse per
    // session (via getCachedLogsForSession) instead of re-reading each session off disk.
    moveJsonFilesToSessionDirs(logsDir)
    moveScreenshotsToSessionDirs(logsDir)

    val costEnricher = LlmLogCostEnricher { modelId -> BuiltInLlmModelRegistry.find(modelId) }
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false, costEnricher = costEnricher::enrich)

    val standaloneFileReport = true
    val logsSummaryEvents = renderSummary(logsRepo, standaloneFileReport)
    val logsSummaryJson = TrailblazeJsonInstance.encodeToString(LogsSummary.serializer(), logsSummaryEvents)
    val summaryJsonFile = File(logsDir, "summary.json")
    summaryJsonFile.writeText(logsSummaryJson)

    // Use explicit root dir if provided (e.g. from Gradle's generateReportTemplate task),
    // otherwise fall back to inferring from the logs directory parent.
    val rootWorkingDir = System.getProperty("trailblaze.rootDir")?.let { File(it) }
      ?: logsRepo.logsDir.parentFile

    // Every run produces the lightweight, self-contained interactive report — the same artifact
    // `trailblaze report` (the CLI/daemon path) emits. The legacy WASM report is emitted ALONGSIDE
    // it unless --no-wasm-report was passed.
    //
    // The WASM report is CPU-bound (image/log compression + video-frame decode); the interactive
    // report spends most of its time waiting on an external bun subprocess. They are independent
    // readers of the same parsed sessions (LogsRepo's cache is read-only once built) that write
    // distinct files, so run them concurrently — overlapping the bun wait with the WASM build is
    // nearly-free wall-clock. Best-effort (bun may be missing, the subprocess may fail), so the
    // async task captures and logs failures and resolves to null instead of aborting the run.
    val interactiveHtmlFile = File(logsDir, "trailblaze_report_interactive.html")
    val interactiveReport: CompletableFuture<File?> = CompletableFuture.supplyAsync {
      runCatching { RunReportGenerator().generate(logsRepo, logsRepo.getSessionIds()) }
        .onFailure { Console.error("Warning: interactive report generation threw: ${it.message}") }
        .getOrNull()
    }

    try {
      if (skipWasmReport) {
        Console.log("Skipping legacy WASM report (--no-wasm-report); emitting the interactive report only.")
      } else {
        val trailblazeReportHtmlFile = File(logsDir, "trailblaze_report.html")
        Console.log("file://${trailblazeReportHtmlFile.absolutePath}")

        // Trailblaze supports two layouts: standalone (`trailblaze-ui/` next to the
        // working dir) and nested (Trailblaze embedded under a sibling subdirectory
        // of a larger repo, where the embedding parent re-exports the framework).
        val standaloneUiDir = File(rootWorkingDir, "trailblaze-ui")
        val nestedUiDir = File(File(rootWorkingDir, "opensource"), "trailblaze-ui")
        val trailblazeUiProjectDir = (if (standaloneUiDir.exists()) standaloneUiDir else nestedUiDir).also {
          Console.log("Using project directory: ${it.canonicalPath}")
        }

        WasmReport.generate(
          logsRepo = logsRepo,
          trailblazeUiProjectDir = trailblazeUiProjectDir,
          outputFile = trailblazeReportHtmlFile,
          reportTemplateFile = File(rootWorkingDir, "trailblaze_report_template.html"),
          useRelativeImageUrls = useRelativeImageUrls,
        )
      }
    } finally {
      // Always harvest the interactive report, even if the WASM build above threw — it's now the
      // primary artifact, so a WASM failure must not discard it or orphan its timestamped temp file.
      val generatedInteractiveHtml = interactiveReport.join()
      if (generatedInteractiveHtml != null) {
        generatedInteractiveHtml.copyTo(interactiveHtmlFile, overwrite = true)
        generatedInteractiveHtml.delete()
        Console.log("file://${interactiveHtmlFile.absolutePath}")
      } else {
        Console.error("Warning: could not generate the interactive report (bun unavailable or subprocess failure).")
      }
    }

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

    val sessionIds = logsRepo.getSessionIds()
    Console.log("📸 Collecting snapshots from ${sessionIds.size} session(s)...")

    // Reuse the single parse LogsRepo cached at construction (the file moves ran before it was
    // built), so the snapshot viewer doesn't re-read every session off disk.
    val logsBySession = sessionIds.associateWith { sessionId ->
      logsRepo.getCachedLogsForSession(sessionId)
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
  // The HTML report command understands neither --dedup (removed; dedup is now unconditional) nor
  // the test-results-only --triage flag — strip both before handing args to it.
  val reportArgs = args.filterNot { it == "--dedup" || it == "--triage" }.toTypedArray()
  GenerateReportCliCommand().main(reportArgs)
  // The test-results command dropped --dedup and doesn't know the HTML-only
  // --use-relative-image-urls / --no-wasm-report; --triage is still a valid flag here and must
  // pass through.
  val filteredArgs = args
    .filterNot { it == "--use-relative-image-urls" || it == "--no-wasm-report" || it == "--dedup" }
    .toTypedArray()
  GenerateTestResultsCliCommand().main(argv = filteredArgs)
}

fun moveJsonFilesToSessionDirs(logsDir: File) {
  val jsonFilesInLogsDir = logsDir.listFiles()
    ?.filter { it.extension == "json" }
    // `trailblaze_test_report*.json` is the aggregate test-results document produced by
    // `GenerateTestResultsCliCommand`, not a per-event TrailblazeLog. Older CI paths left
    // it alongside the raw log events in `logsDir`, and the polymorphic decode below would
    // fail on it with `Class discriminator was missing` — surfaced in CI build analyzers
    // as a noisy `RUNTIME_ERROR` even though the report generation itself succeeded.
    // Filter it at the source so the inner decode never sees it.
    ?.filterNot { it.name.startsWith("trailblaze_test_report") }
    ?: emptyList()
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
          is TrailblazeLog.AgentDriverLog -> log.copy(
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

// Canonical screenshot file extensions, derived from [TrailblazeImageFormat]. Adding a
// new image format anywhere in the codebase makes it visible to this scanner
// automatically. The extra "jpeg" entry covers the long-form JPEG extension that
// TrailblazeImageFormat normalizes to "jpg" for output.
private val IMAGE_EXTENSIONS = TrailblazeImageFormat.entries.map { it.fileExtension }.toSet() + setOf("jpeg")

fun moveScreenshotsToSessionDirs(logsDir: File) {
  val imageFiles = logsDir.listFiles()?.filter { it.extension in IMAGE_EXTENSIONS } ?: emptyList()
  if (imageFiles.isEmpty()) return

  // The natural filename format is `{sessionId}_{timestamp}.{ext}`, but when the session id
  // would push the filename past NAME_MAX (255 bytes), TrailblazeLogger falls back to
  // `{sha8(sessionId)}_{timestamp}.{ext}`. In that fallback the leading token is the hash,
  // not the real session id, so filename-based inference via substringBeforeLast("_") routes
  // the file into a `<sha8>/` dir that LogsRepo never looks under. Build a screenshot →
  // session map from the JSONs already organized by moveJsonFilesToSessionDirs so we route
  // by what the log says, falling back to filename parsing only for orphaned files.
  val sessionByScreenshotName = buildSessionByScreenshotNameMap(logsDir)

  imageFiles.forEach { imageFile ->
    try {
      val sessionId = sessionByScreenshotName[imageFile.name]
        ?: imageFile.nameWithoutExtension.substringBeforeLast("_").takeIf { it.isNotEmpty() }

      if (sessionId != null) {
        val sessionDir = File(logsDir, sessionId)
        sessionDir.mkdirs()

        val destFile = File(sessionDir, imageFile.name)
        imageFile.copyTo(destFile, overwrite = true)
        imageFile.delete()
        Console.log("Moved ${imageFile.name} to session directory: $sessionId")
      } else {
        Console.log("Could not determine session ID for image file: ${imageFile.name}, skipping")
      }
    } catch (e: Exception) {
      Console.log("Error processing image file ${imageFile.absolutePath}: ${e.message}")
    }
  }
}

private fun buildSessionByScreenshotNameMap(logsDir: File): Map<String, String> {
  val sessionDirs = logsDir.listFiles()?.filter { it.isDirectory } ?: return emptyMap()
  val result = mutableMapOf<String, String>()
  sessionDirs.forEach { sessionDir ->
    val jsonFiles = sessionDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
    jsonFiles.forEach { jsonFile ->
      try {
        val log = TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(jsonFile.readText())
        if (log is HasScreenshot) {
          log.screenshotFile?.let { screenshotFile ->
            // Strip the `<sessionId>/` prefix that moveJsonFilesToSessionDirs may have rewritten in.
            val justName = screenshotFile.substringAfterLast('/')
            result[justName] = log.session.value
          }
        }
      } catch (_: Exception) {
        // Malformed/unrelated JSON — not authoritative, skip.
      }
    }
  }
  return result
}

fun renderSummary(logsRepo: LogsRepo, isStandaloneFileReport: Boolean): LogsSummary {
  val map = logsRepo.getSessionIds().associateWith { logsRepo.getCachedLogsForSession(it) }
  val logsSummary = LogsSummary.fromLogs(map.mapKeys { it.key.value }, isStandaloneFileReport)
  return logsSummary
}
