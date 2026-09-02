package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.main
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.llm.LlmLogCostEnricher
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.models.LogsSummary
import xyz.block.trailblaze.report.snapshot.SnapshotCollector
import xyz.block.trailblaze.report.snapshot.SnapshotViewerGenerator
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import xyz.block.trailblaze.util.Console

open class GenerateReportCliCommand(
  /**
   * Injected by [main] so both commands in one process share one [SingleReadLogsRepoProvider]
   * — i.e. ONE capture of every session, feeding both the shared [LogsRepo] and this command's
   * interactive-report leg — instead of each command re-parsing the same directory. Null (the
   * default, and every direct/standalone use) means the command builds its own provider and
   * closes it when done; a supplied provider is owned (and closed) by the caller.
   */
  private val sharedLogsSource: SingleReadLogsRepoProvider? = null,
) : SimpleCliCommand(
  name = "generate-report",
) {

  private val logsDirArg = FileArgument(
    name = "logs-dir",
    help = "Directory containing Trailblaze log files",
    mustExist = true,
    canBeFile = false,
    mustBeReadable = true,
  )

  /**
   * Makes the interactive report reference images at `<sessionId>/<file>` — relative to the report's
   * own URL — instead of embedding them. It's what keeps a many-session CI report a small document.
   *
   * This flag is a promise about the world: the caller guarantees the session image tree is
   * served at `<report-url-dir>/<sessionId>/<file>`. Only one CI script actually does that
   * (the per-step/per-plan report, which uploads the logs dir's images at the artifact root
   * alongside the report). The aggregate summary reports upload only their HTML, into a
   * `reports/summary-<key>/` subdirectory — so linking there would silently turn every
   * screenshot into a 404. They keep embedding.
   */
  private val linkImagesFlag = FlagOption(
    longName = "link-images",
    help = "Interactive report only: reference images at <sessionId>/<file> relative to the report " +
      "instead of embedding them. The caller MUST host the session image tree beside the report.",
    default = false,
  )

  private val logsDir: File get() = logsDirArg.value
  private val linkImages: Boolean get() = linkImagesFlag.value

  override fun parseArgs(args: Array<String>) {
    val positionalArgs = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        linkImagesFlag.matches(arg) -> linkImagesFlag.set()
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
    Console.error("Usage: generate-report ${logsDirArg.getUsage()} ${linkImagesFlag.getUsage()}")
    Console.error("")
    Console.error("Generate Trailblaze HTML report from logs directory")
    Console.error("")
    Console.error("Arguments:")
    Console.error("  ${logsDirArg.getUsage()}  ${logsDirArg.getHelp()}")
    Console.error("")
    Console.error("Options:")
    Console.error("  ${linkImagesFlag.getUsage()}  ${linkImagesFlag.getHelp()}")
  }

  override fun run(): Unit = ReportTiming.stage("ReportMain.run") {
    Console.log("logsDir: ${logsDir.canonicalPath}")
    Console.log("linkImages: $linkImages")

    // Reorganize adb-pulled files into per-session directories BEFORE constructing LogsRepo.
    // LogsRepo's single-read cache is built at construction, so doing the moves first means that
    // cache captures the final on-disk layout. Every emitter below can then share ONE parse per
    // session (via getCachedLogsForSession) instead of re-reading each session off disk.
    // The screenshot pass reuses the decode the JSON pass already performed (the two run
    // back-to-back over the same files) instead of re-decoding every session-dir JSON.
    val sessionByScreenshotName = ReportTiming.stage("ReportMain.moveJsonFilesToSessionDirs") { moveJsonFilesToSessionDirs(logsDir) }
    ReportTiming.stage("ReportMain.moveScreenshotsToSessionDirs") { moveScreenshotsToSessionDirs(logsDir, sessionByScreenshotName) }

    // One capture of every session (see [SingleReadLogsRepoProvider]): the same
    // SessionLogSnapshots seed the LogsRepo below AND feed the interactive report leg, so
    // this command reads + decodes each log file exactly once.
    val ownsLogsSource = sharedLogsSource == null
    val logsSource = sharedLogsSource ?: SingleReadLogsRepoProvider()
    try {
      val logsRepo = ReportTiming.stage("ReportMain.logsRepoParse") { logsSource.get(logsDir) }

      val standaloneFileReport = true
      val logsSummaryEvents = renderSummary(logsRepo, standaloneFileReport)
      val logsSummaryJson = TrailblazeJsonInstance.encodeToString(LogsSummary.serializer(), logsSummaryEvents)
      val summaryJsonFile = File(logsDir, "summary.json")
      summaryJsonFile.writeText(logsSummaryJson)

      // Inferred from the logs directory parent; [afterReportGenerated] overrides resolve
      // repo-relative paths against it. `absoluteFile` first because a single-segment relative
      // logs dir (`generate-report logs`, which atf.sh can produce) has a null parent.
      val rootWorkingDir = logsRepo.logsDir.absoluteFile.parentFile

      // The run produces the lightweight interactive report — the same artifact `trailblaze
      // report` (the CLI/daemon path) emits, and self-contained unless --link-images was passed
      // (see [linkImagesFlag]).
      val interactiveHtmlFile = File(logsDir, "trailblaze_report_interactive.html")

      // Generation is best-effort; landing the canonical filename is not. A renderer failure
      // leaves the error below rather than a half-written artifact, and the copy is kept outside
      // the catch so a failure to land it surfaces instead of being swallowed.
      val generatedHtml = try {
        // Built from the shared snapshots — no re-read of the log files the repo already decoded.
        RunReportGenerator().generateFromSnapshots(
          logsRepo,
          logsSource.snapshots(logsDir),
          // "" = document-relative `<sessionId>/<file>`, which the browser resolves against the
          // report's own URL. See [linkImagesFlag] for why this is its own flag.
          imageBaseUrl = if (linkImages) "" else null,
          // This report covers the whole logs directory, so every skip recorded in it is in
          // scope. A CI build gets a fresh logs dir per run, so "everything here" is "this run".
          skips = SkippedTrails.read(logsDir),
        )
      } catch (e: Exception) {
        // Exception, not Throwable: an OOM here is the signal `generate_build_combined_report.sh`
        // classifies on, and swallowing it would relabel a memory failure as a renderer bug.
        Console.error("Warning: report generation threw: ${e.message}")
        null
      }
      if (generatedHtml != null) {
        generatedHtml.copyTo(interactiveHtmlFile, overwrite = true)
        generatedHtml.delete()
        Console.log("file://${interactiveHtmlFile.absolutePath}")
      } else {
        // bun is a required dependency, so this is a broken toolchain (or a genuine renderer
        // bug), not a supported degraded mode.
        Console.error(
          "ERROR: could not generate the Trailblaze report. Check the [RunReportGenerator] output " +
            "above — a missing `bun` (a required dependency) or a report subprocess failure.",
        )
      }

      afterReportGenerated(logsRepo, rootWorkingDir)

      // Generate snapshot viewer using pre-parsed logs from LogsRepo (integrated mode)
      // This avoids re-scanning and re-parsing all the JSON files
      generateSnapshotViewerIntegrated(logsRepo)
    } finally {
      // Clean up file watchers to allow JVM to exit — on success AND on a report failure
      // rethrown above (a leaked provider keeps its repo's coroutine scope alive). A
      // caller-supplied provider's repo is still in use by the next command in the process —
      // its owner closes it.
      if (ownsLogsSource) logsSource.close()
    }
  }

  /**
   * Hook for subclasses to perform additional processing after the report is generated.
   * Called before snapshot viewer generation.
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

/**
 * Memoizes one shared parse per logs directory: each session is captured ONCE (as
 * [SessionLogSnapshot]s, one read + decode per log file) and that capture serves everything —
 * it seeds the single-read [LogsRepo] the memoized [get] returns, and [snapshots] hands the
 * raw view to the interactive-report leg. Lets the two commands [main] runs in sequence share
 * a single parse of every session instead of each re-reading the whole logs dir. The provider
 * owns the repos: close them via [close] after the last command completes.
 */
class SingleReadLogsRepoProvider {

  private class SharedLogs(val snapshots: List<SessionLogSnapshot>, val logsRepo: LogsRepo)

  private val logsByDir = mutableMapOf<File, SharedLogs>()

  @Synchronized
  fun get(logsDir: File): LogsRepo = shared(logsDir).logsRepo

  /** The one-per-session capture [get]'s repo was seeded from, for the same [logsDir]. */
  @Synchronized
  fun snapshots(logsDir: File): List<SessionLogSnapshot> = shared(logsDir).snapshots

  private fun shared(logsDir: File): SharedLogs = logsByDir.getOrPut(logsDir.canonicalFile) {
    val costEnricher = LlmLogCostEnricher { modelId -> BuiltInLlmModelRegistry.find(modelId) }
    // Same session-dir enumeration AND descending-name ordering as LogsRepo.getSessionIds, done
    // up front so the capture can happen BEFORE the repo exists (the repo's init-time parse is
    // what the seeding skips). The ordering matters: the interactive report embeds sessions in
    // capture order, and the pre-snapshot path rendered them in getSessionIds order.
    val sessionIds = logsDir.listFiles()
      ?.filter { it.isDirectory }
      ?.sortedByDescending { it.name }
      ?.map { SessionId(it.name) }
      ?: emptyList()
    val snapshots = SessionLogSnapshot.captureAll(logsDir, sessionIds, costEnricher::enrich)
    SharedLogs(
      snapshots = snapshots,
      logsRepo = LogsRepo(
        logsDir = logsDir,
        watchFileSystem = false,
        costEnricher = costEnricher::enrich,
        preParsedLogs = snapshots.associate { it.sessionId to it.logs },
      ),
    )
  }

  @Synchronized
  fun close() {
    logsByDir.values.forEach { it.logsRepo.close() }
    logsByDir.clear()
  }
}

/**
 * One report invocation drives two commands over the same argv — [GenerateReportCliCommand] and
 * [GenerateTestResultsCliCommand] — and each rejects a flag it doesn't declare. This is the single
 * place that says which flags belong to which, so an entry point that composes the same two
 * commands (including a subclassing one downstream) can't carry a stale copy of the split.
 */
object ReportCliArgs {
  /**
   * HTML-report-only. The test-results command is a clikt command, so one of these reaching it
   * aborts the whole run with `Error: no such option <flag>`.
   */
  private val HTML_REPORT_ONLY_FLAGS = setOf("--link-images")

  /** Test-results-only: the cross-cutting failure analysis the HTML report doesn't emit. */
  private val TEST_RESULTS_ONLY_FLAGS = setOf("--triage")

  /**
   * Retired, but still passed by callers: dedup is unconditional now, and the legacy WASM report
   * is gone — which retires both the `--no-wasm-report` that skipped it and the
   * `--use-relative-image-urls` that only ever reached it. Neither command takes them, and an
   * unknown `--` flag aborts the run, so a pipeline in another repo that still passes one would
   * fail its report step rather than just ignoring a no-op.
   */
  private val RETIRED_FLAGS = setOf("--dedup", "--no-wasm-report", "--use-relative-image-urls")

  /** Retired flags can arrive as `--flag` or `--flag=value`; both must be dropped. */
  private fun isRetired(arg: String) = arg.substringBefore('=') in RETIRED_FLAGS

  fun forHtmlReport(args: Array<String>): Array<String> =
    args.filterNot { it in TEST_RESULTS_ONLY_FLAGS || isRetired(it) }.toTypedArray()

  fun forTestResults(args: Array<String>): Array<String> =
    args.filterNot { it in HTML_REPORT_ONLY_FLAGS || isRetired(it) }.toTypedArray()
}

fun main(args: Array<String>) {
  // One shared parse of every session across both commands — see [SingleReadLogsRepoProvider].
  // The capture is lazy (first `get`), and ordering makes it correct: the report command runs
  // first and only touches the provider AFTER its file moves, so the shared capture (and the
  // repo seeded from it) sees the final on-disk layout for the test-results command too.
  val sharedLogsRepos = SingleReadLogsRepoProvider()
  try {
    GenerateReportCliCommand(sharedLogsRepos).main(ReportCliArgs.forHtmlReport(args))
    GenerateTestResultsCliCommand(sharedLogsRepos::get).main(argv = ReportCliArgs.forTestResults(args))
  } finally {
    sharedLogsRepos.close()
  }
}

/**
 * @return a map from screenshot filename to owning session ID, gathered from the logs decoded
 *   while moving them — [moveScreenshotsToSessionDirs] uses it to route loose screenshots
 *   without re-decoding the JSONs this pass just organized.
 */
fun moveJsonFilesToSessionDirs(logsDir: File): Map<String, SessionId> {
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
  val sessionByScreenshotName = mutableMapOf<String, SessionId>()
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
          sessionByScreenshotName[screenshotFile.substringAfterLast('/')] = sessionId
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
  return sessionByScreenshotName
}

// Canonical screenshot file extensions, derived from [TrailblazeImageFormat]. Adding a
// new image format anywhere in the codebase makes it visible to this scanner
// automatically. The extra "jpeg" entry covers the long-form JPEG extension that
// TrailblazeImageFormat normalizes to "jpg" for output.
private val IMAGE_EXTENSIONS = TrailblazeImageFormat.entries.map { it.fileExtension }.toSet() + setOf("jpeg")

fun moveScreenshotsToSessionDirs(logsDir: File, knownSessionByScreenshotName: Map<String, SessionId> = emptyMap()) {
  val imageFiles = logsDir.listFiles()?.filter { it.extension in IMAGE_EXTENSIONS } ?: emptyList()
  if (imageFiles.isEmpty()) return

  // The natural filename format is `{sessionId}_{timestamp}.{ext}`, but when the session id
  // would push the filename past NAME_MAX (255 bytes), TrailblazeLogger falls back to
  // `{sha8(sessionId)}_{timestamp}.{ext}`. In that fallback the leading token is the hash,
  // not the real session id, so filename-based inference via substringBeforeLast("_") routes
  // the file into a `<sha8>/` dir that LogsRepo never looks under. Route by what the log
  // says: prefer the map [moveJsonFilesToSessionDirs] gathered while decoding the JSONs it
  // just organized, and only re-scan the organized session dirs when some loose image isn't
  // covered by it (e.g. its log was already in a session dir before this run). Filename
  // parsing remains the last resort for orphaned files.
  val sessionByScreenshotName = if (imageFiles.all { it.name in knownSessionByScreenshotName }) {
    knownSessionByScreenshotName
  } else {
    knownSessionByScreenshotName + buildSessionByScreenshotNameMap(logsDir)
  }

  imageFiles.forEach { imageFile ->
    try {
      val sessionId = sessionByScreenshotName[imageFile.name]?.value
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

private fun buildSessionByScreenshotNameMap(logsDir: File): Map<String, SessionId> {
  val sessionDirs = logsDir.listFiles()?.filter { it.isDirectory } ?: return emptyMap()
  val result = mutableMapOf<String, SessionId>()
  sessionDirs.forEach { sessionDir ->
    val jsonFiles = sessionDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
    jsonFiles.forEach { jsonFile ->
      try {
        val log = TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(jsonFile.readText())
        if (log is HasScreenshot) {
          log.screenshotFile?.let { screenshotFile ->
            // Strip the `<sessionId>/` prefix that moveJsonFilesToSessionDirs may have rewritten in.
            val justName = screenshotFile.substringAfterLast('/')
            result[justName] = log.session
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
