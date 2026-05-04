package xyz.block.trailblaze.cli

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.capture.logcat.LogcatParser
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionInfo
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.report.ReportTemplateResolver
import xyz.block.trailblaze.report.WasmReport
import xyz.block.trailblaze.report.models.CiRunMetadata
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.report.models.ExecutionMode
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.models.RecordingSkipReason
import xyz.block.trailblaze.report.models.SOURCE_TYPE_GENERATED
import xyz.block.trailblaze.report.models.SessionRecordingInfo
import xyz.block.trailblaze.report.models.SessionResult
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.yaml.TrailConfig

/**
 * Generates a pass/fail summary and optional HTML report after CLI trail execution.
 *
 * Subclasses can override [generateReport] and [generateMarkdownReport] to customize
 * report generation (via [xyz.block.trailblaze.ui.TrailblazeDesktopApp.createCliReportGenerator]).
 */
open class CliReportGenerator {

  /**
   * Prints a compact pass/fail summary for the given session IDs.
   *
   * Polls session logs from disk with exponential backoff, waiting for each session to
   * reach a terminal status ([SessionStatus.Ended]). Sessions that are already complete
   * are resolved immediately without any delay.
   */
  fun printSummary(logsRepo: LogsRepo, sessionIds: List<SessionId>) {
    if (sessionIds.isEmpty()) return

    // Poll sessions with backoff until all have a terminal status or we time out.
    // Sessions that are already complete resolve immediately — no unnecessary delay.
    val statuses = awaitTerminalStatuses(logsRepo, sessionIds)

    var passed = 0
    var failed = 0
    for (sessionId in sessionIds) {
      when (statuses[sessionId]) {
        is SessionStatus.Ended.Succeeded,
        is SessionStatus.Ended.SucceededWithSelfHeal -> passed++
        else -> failed++
      }
    }

    val total = sessionIds.size
    val summary = buildString {
      val parts = mutableListOf<String>()
      if (passed > 0) parts.add("$passed passed")
      if (failed > 0) parts.add("$failed failed")
      append(parts.joinToString(", "))
      append(" ($total total)")
    }

    Console.log("")
    Console.log("\u2550".repeat(58))
    Console.log("  Results: $summary")
    Console.log("\u2550".repeat(58))
  }

  /**
   * Polls session logs from disk with exponential backoff, waiting for each session to
   * reach a terminal status ([SessionStatus.Ended]).
   *
   * Starts at [initialDelayMs] and doubles each iteration up to [maxDelayMs], giving up
   * after [maxWaitMs] total elapsed time. Sessions still in progress at that point are
   * mapped to [SessionStatus.Unknown].
   *
   * @return a map from session ID to its resolved [SessionStatus].
   */
  private fun awaitTerminalStatuses(
    logsRepo: LogsRepo,
    sessionIds: List<SessionId>,
    maxWaitMs: Long = 10_000,
    initialDelayMs: Long = 100,
    maxDelayMs: Long = 2_000,
  ): Map<SessionId, SessionStatus> {
    val statuses = mutableMapOf<SessionId, SessionStatus>()
    val pending = sessionIds.toMutableSet()

    var delayMs = initialDelayMs
    var totalWaited = 0L

    while (pending.isNotEmpty() && totalWaited < maxWaitMs) {
      val iterator = pending.iterator()
      while (iterator.hasNext()) {
        val sessionId = iterator.next()
        val status = logsRepo.getLogsForSession(sessionId).getSessionStatus()
        if (status is SessionStatus.Ended) {
          statuses[sessionId] = status
          iterator.remove()
        }
      }

      if (pending.isEmpty()) break

      Thread.sleep(delayMs)
      totalWaited += delayMs
      delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
    }

    // Any sessions still pending after the timeout are treated as unknown.
    for (sessionId in pending) {
      Console.log("Warning: session $sessionId did not reach a terminal status within ${maxWaitMs}ms")
      statuses[sessionId] = SessionStatus.Unknown
    }

    return statuses
  }

  /**
   * Generates a self-contained HTML report for the given session IDs.
   *
   * Subclasses can override this to customize report generation (e.g., using
   * block-specific report features).
   *
   * @return the report [File] if generation succeeded, null otherwise.
   */
  open fun generateReport(logsRepo: LogsRepo, sessionIds: List<SessionId>): File? {
    if (sessionIds.isEmpty()) return null

    val gitRoot = ReportTemplateResolver.getGitRoot()
    val trailblazeUiDir = findTrailblazeUiDir(gitRoot)
    val wasmBuildDir =
      trailblazeUiDir?.let {
        File(it, "build/kotlin-webpack/wasmJs/productionExecutable")
      }
    val hasWasmBuild = wasmBuildDir?.exists() == true

    // Resolve template: local file at git root → classpath (bundled in JAR)
    val effectiveTemplateFile = ReportTemplateResolver.resolveTemplate()

    if (effectiveTemplateFile == null && !hasWasmBuild) {
      Console.log("")
      Console.log("No report template found. To enable HTML reports, build the WASM report viewer:")
      Console.log("  ./gradlew :trailblaze-report:generateReportTemplate -Ptrailblaze.wasm=true")
      Console.log("This is a one-time step. Re-run to pick up report UI changes.")
      return null
    }

    // Create a temporary directory containing symlinks to only this run's sessions
    val tempDir = Files.createTempDirectory("trailblaze-report-").toFile()
    try {
      for (sessionId in sessionIds) {
        val sessionDir = File(logsRepo.logsDir, sessionId.value)
        if (sessionDir.exists()) {
          Files.createSymbolicLink(
            File(tempDir, sessionId.value).toPath(),
            sessionDir.toPath(),
          )
        }
      }

      val tempLogsRepo = LogsRepo(logsDir = tempDir, watchFileSystem = false)

      // Create the reports output directory
      val reportsDir = File(logsRepo.logsDir, "reports")
      reportsDir.mkdirs()

      val timestamp =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
      val outputFile = File(reportsDir, "trailblaze_report_$timestamp.html")

      WasmReport.generate(
        logsRepo = tempLogsRepo,
        trailblazeUiProjectDir = trailblazeUiDir ?: tempDir,
        outputFile = outputFile,
        reportTemplateFile = effectiveTemplateFile ?: File("nonexistent"),
      )

      tempLogsRepo.close()
      return outputFile
    } finally {
      // Clean up temp directory — delete symlinks only, NOT the actual session data.
      // IMPORTANT: File.deleteRecursively() follows symlinks and would delete the real
      // session files. We must use Files.delete() which removes the symlink itself.
      tempDir.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
      tempDir.delete()
    }
  }

  /**
   * Generates a markdown report for the given session IDs.
   *
   * The report includes run metadata, a summary table, and per-test details
   * (duration, LLM calls, cost, failure reason).
   *
   * @return the report [File] if generation succeeded, null otherwise.
   */
  open fun generateMarkdownReport(logsRepo: LogsRepo, sessionIds: List<SessionId>): File? {
    if (sessionIds.isEmpty()) return null

    val statuses = awaitTerminalStatuses(logsRepo, sessionIds)

    data class SessionData(
      val title: String,
      val outcome: Outcome,
      val durationMs: Long,
      val llmCalls: Int,
      val costUsd: Double?,
      val failureReason: String?,
      val deviceLogExcerpt: String?,
      val llmModel: String?,
      val platform: String?,
    )

    val sessions = mutableListOf<SessionData>()
    for (sessionId in sessionIds) {
      val logs = logsRepo.getLogsForSession(sessionId)
      val sessionInfo = logs.getSessionInfo() ?: continue
      val status = statuses[sessionId] ?: SessionStatus.Unknown

      val usageSummary = logs.computeUsageSummary()
      val outcome = mapStatusToOutcome(status)
      val deviceLogExcerpt = if (outcome != Outcome.PASSED) {
        extractDeviceLogExcerpt(logsRepo, sessionId)
      } else null

      sessions.add(
        SessionData(
          title = sessionInfo.displayName,
          outcome = outcome,
          durationMs = sessionInfo.durationMs,
          llmCalls = countLlmCalls(logs),
          costUsd = usageSummary?.totalCostInUsDollars,
          failureReason = extractFailureReason(status),
          deviceLogExcerpt = deviceLogExcerpt,
          llmModel = usageSummary?.llmModel?.let {
            "${it.trailblazeLlmProvider.id}/${it.modelId}"
          },
          platform = sessionInfo.trailblazeDeviceInfo?.platform?.name?.lowercase(),
        )
      )
    }

    if (sessions.isEmpty()) return null

    val totalDurationMs = sessions.sumOf { it.durationMs }
    val totalCost = sessions.mapNotNull { it.costUsd }.sum()
    val passed = sessions.count { it.outcome == Outcome.PASSED }
    val failed = sessions.size - passed
    val passRate = if (sessions.isNotEmpty()) "%.0f".format(passed.toDouble() / sessions.size * 100) else "0"
    val llmModel = sessions.firstNotNullOfOrNull { it.llmModel } ?: "unknown"
    val platform = sessions.firstNotNullOfOrNull { it.platform } ?: "unknown"
    val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    val markdown = buildString {
      appendLine("# Trailblaze Results")
      appendLine()
      appendLine("## Run Metadata")
      appendLine()
      appendLine("| Field | Value |")
      appendLine("|---|---|")
      appendLine("| Date | $date |")
      appendLine("| Platform | $platform |")
      appendLine("| LLM Model | $llmModel |")
      appendLine("| Total Duration | ${formatDuration(totalDurationMs)} |")
      appendLine("| Total LLM Cost | \$${"%,.2f".format(totalCost)} |")
      appendLine()
      appendLine("## Summary")
      appendLine()
      appendLine("| Metric | Value |")
      appendLine("|---|---|")
      appendLine("| Total | ${sessions.size} |")
      appendLine("| Passed | $passed |")
      appendLine("| Failed | $failed |")
      appendLine("| Pass Rate | $passRate% |")
      appendLine()
      appendLine("## Results")

      for (session in sessions) {
        appendLine()
        val statusLabel = if (session.outcome == Outcome.PASSED) "PASSED" else "FAILED"
        appendLine("### ${session.title} — $statusLabel")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---|")
        appendLine("| Duration | ${formatDuration(session.durationMs)} |")
        appendLine("| LLM Calls | ${session.llmCalls} |")
        if (session.costUsd != null) {
          appendLine("| LLM Cost | \$${"%,.2f".format(session.costUsd)} |")
        }
        if (session.failureReason != null) {
          val reason = session.failureReason.replace("|", "\\|").replace("\n", " ")
          appendLine("| Failure Reason | $reason |")
        }
        if (session.deviceLogExcerpt != null) {
          appendLine()
          appendLine("<details><summary>Device Logs</summary>")
          appendLine()
          appendLine("```")
          appendLine(session.deviceLogExcerpt)
          appendLine("```")
          appendLine()
          appendLine("</details>")
        }
      }
    }

    val reportsDir = File(logsRepo.logsDir, "reports")
    reportsDir.mkdirs()
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val outputFile = File(reportsDir, "trailblaze_report_$timestamp.md")
    outputFile.writeText(markdown)
    return outputFile
  }

  /**
   * Finds the trailblaze-ui project directory relative to the git root.
   *
   * Supports both layouts the base CLI may run in: standalone (`trailblaze-ui/`
   * sits next to the working dir) and nested (Trailblaze embedded under a
   * sibling subdirectory of a larger repo). Walks the standalone candidate
   * first because the standalone opensource checkout is the more common case
   * for external consumers; the embedding-parent override can still pre-empt
   * either by overriding this method.
   */
  protected open fun findTrailblazeUiDir(gitRoot: File?): File? {
    if (gitRoot == null) return null
    val standalonePath = File(gitRoot, "trailblaze-ui")
    if (standalonePath.exists()) return standalonePath
    val nestedPath = File(File(gitRoot, "opensource"), "trailblaze-ui")
    return nestedPath.takeIf { it.exists() }
  }

  internal fun mapStatusToOutcome(status: SessionStatus): Outcome = when (status) {
    is SessionStatus.Ended.Succeeded -> Outcome.PASSED
    is SessionStatus.Ended.SucceededWithSelfHeal -> Outcome.PASSED
    is SessionStatus.Ended.Failed -> Outcome.FAILED
    is SessionStatus.Ended.FailedWithSelfHeal -> Outcome.FAILED
    is SessionStatus.Ended.Cancelled -> Outcome.CANCELLED
    is SessionStatus.Ended.TimeoutReached -> Outcome.TIMEOUT
    is SessionStatus.Ended.MaxCallsLimitReached -> Outcome.MAX_CALLS_REACHED
    is SessionStatus.Started -> Outcome.ERROR
    is SessionStatus.Unknown -> Outcome.ERROR
  }

  private fun extractFailureReason(status: SessionStatus): String? = when (status) {
    is SessionStatus.Ended.Failed -> status.exceptionMessage
    is SessionStatus.Ended.FailedWithSelfHeal -> status.exceptionMessage
    is SessionStatus.Ended.Cancelled -> status.cancellationMessage
    is SessionStatus.Ended.TimeoutReached -> status.message
    is SessionStatus.Ended.MaxCallsLimitReached ->
      "Max LLM calls limit reached (${status.maxCalls}) for: ${status.objectivePrompt}"
    else -> null
  }

  /**
   * Extracts a relevant excerpt from the device log (logcat.txt) for a session.
   *
   * Looks for crash-related lines (FATAL, Exception, ANR) first. If found, returns
   * those lines with surrounding context. Otherwise returns the last [maxLines] lines.
   */
  private fun extractDeviceLogExcerpt(
    logsRepo: LogsRepo,
    sessionId: SessionId,
    maxLines: Int = 50,
  ): String? {
    val sessionDir = logsRepo.getSessionDir(sessionId)
    if (!sessionDir.exists()) return null
    val logcatFile = LogcatParser.findDeviceLogFile(sessionDir) ?: return null
    if (logcatFile.length() == 0L) return null

    val allLines = logcatFile.readLines()
    if (allLines.isEmpty()) return null

    val errorPatterns = listOf("FATAL", "Exception", "ANR", "crash", "Error")
    val errorLineIndices = allLines.indices.filter { idx ->
      errorPatterns.any { pattern -> allLines[idx].contains(pattern, ignoreCase = true) }
    }

    return if (errorLineIndices.isNotEmpty()) {
      val lastErrorIdx = errorLineIndices.last()
      val contextStart = (lastErrorIdx - 5).coerceAtLeast(0)
      val contextEnd = (lastErrorIdx + maxLines - 5).coerceAtMost(allLines.size)
      allLines.subList(contextStart, contextEnd).joinToString("\n")
    } else {
      allLines.takeLast(maxLines).joinToString("\n")
    }
  }

  private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
  }

  private fun countLlmCalls(logs: List<TrailblazeLog>): Int {
    return logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>().size
  }

  /**
   * Generates a `CiSummaryReport` JSON artifact for the given session IDs.
   *
   * Mirrors what `:trailblaze-report:generateTestResultsArtifacts` produces, but reachable
   * directly from the daemon CLI (`trailblaze report --format json` / `trailblaze session
   * report --format json`). Uses the same [CiSummaryReport] / [SessionResult] schema so
   * downstream tooling (CI dashboards, build annotations) can consume both surfaces
   * interchangeably. Subclasses can override to attach extra metadata before serializing.
   *
   * ## Schema stability
   *
   * The emitted JSON shape is part of Trailblaze's public CI contract, on equal footing
   * with `:trailblaze-report:generateTestResultsArtifacts`. Field renames or removals are
   * a breaking change for downstream consumers (CI dashboards, build annotations, GitHub
   * comment renderers). Adds-only changes are safe; field deletes/renames need a
   * deprecation cycle and a release note. See [CiSummaryReport] / [SessionResult] for the
   * canonical shape.
   *
   * @return the JSON [File] if generation succeeded, null if no sessions resolved or the
   *   write failed (a write failure is logged via [Console.error] and reported as null
   *   rather than propagated, so a transient disk issue can't crash a CLI invocation).
   */
  open fun generateJsonReport(logsRepo: LogsRepo, sessionIds: List<SessionId>): File? {
    if (sessionIds.isEmpty()) return null

    val statuses = awaitTerminalStatuses(logsRepo, sessionIds)
    val results = sessionIds.mapNotNull { sessionId ->
      buildSessionResult(logsRepo, sessionId, statuses[sessionId] ?: SessionStatus.Unknown)
    }
    if (results.isEmpty()) return null

    val report = CiSummaryReport(metadata = emptyMetadata(), results = results)

    val reportsDir = File(logsRepo.logsDir, "reports")
    if (!reportsDir.mkdirs() && !reportsDir.isDirectory) {
      Console.error("Could not create reports directory: ${reportsDir.absolutePath}")
      return null
    }
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val outputFile = File(reportsDir, "trailblaze_test_results_$timestamp.json")
    return try {
      outputFile.writeText(jsonReportSerializer.encodeToString(report))
      outputFile
    } catch (e: Exception) {
      // Disk full, permission denied, encoder failure — surface the cause so users can
      // act on it, but don't propagate; a transient write issue shouldn't crash the
      // CLI command that's just trying to summarize results.
      Console.error("Could not write JSON report to ${outputFile.absolutePath}: ${e.message}")
      null
    }
  }

  /**
   * JSON encoder for the test-results artifact. `encodeDefaults = true` matches the
   * gradle-launched [`GenerateTestResultsCliCommand`][xyz.block.trailblaze.report.GenerateTestResultsCliCommand]
   * output so downstream consumers see a stable schema regardless of which surface
   * generated the file.
   */
  private val jsonReportSerializer = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * Builds a [SessionResult] for a single session. Returns null if the session has no
   * resolvable [getSessionInfo] entry — typically a partially-written or empty session
   * directory. Intentionally does NOT throw on per-session parse failures so one bad
   * session can't fail report generation for the rest.
   */
  private fun buildSessionResult(
    logsRepo: LogsRepo,
    sessionId: SessionId,
    status: SessionStatus,
  ): SessionResult? = try {
    val logs = logsRepo.getLogsForSession(sessionId)
    val sessionInfo = logs.getSessionInfo() ?: return null

    val platform = sessionInfo.trailblazeDeviceInfo?.platform?.name?.lowercase() ?: "unknown"
    val outcome = mapStatusToOutcome(status)
    val title = sessionInfo.trailConfig?.title
      ?: sessionInfo.trailConfig?.id
      ?: sessionInfo.trailFilePath
        ?.removePrefix("trails/")
        ?.removeSuffix(TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX)
      ?: sessionInfo.testName?.takeIf { it.isNotBlank() }?.let { name ->
        sessionInfo.testClass?.let { cls -> "$cls:$name" } ?: name
      }
      ?: sessionInfo.testClass
      ?: sessionId.value

    val recordingInfo = SessionRecordingInfo.fromLogs(logs)
    val firstLog = logs.firstOrNull()
    val lastLog = logs.lastOrNull()
    val deviceLogExcerpt = if (outcome != Outcome.PASSED) {
      extractDeviceLogExcerpt(logsRepo, sessionId)
    } else null

    SessionResult(
      session_id = sessionId,
      title = title,
      test_key = sessionInfo.stableTestKey,
      platform = platform,
      execution_mode = determineExecutionMode(status, recordingInfo),
      trail_source = determineTrailSource(sessionInfo.trailConfig),
      device_classifier = sessionInfo.trailblazeDeviceInfo?.classifiers
        ?.joinToString("-") { it.classifier },
      outcome = outcome,
      failure_reason = extractJsonFailureReason(status),
      device_log_excerpt = deviceLogExcerpt,
      has_recorded_steps = sessionInfo.hasRecordedSteps,
      recording_available = recordingInfo.available,
      recording_skip_reason = recordingInfo.skipReason,
      duration_ms = sessionInfo.durationMs,
      llm_call_count = countLlmCalls(logs),
      llm_cost_usd = logs.computeUsageSummary()?.totalCostInUsDollars,
      started_at = firstLog?.timestamp?.toIso8601String(),
      started_at_epoch_ms = firstLog?.timestamp?.toEpochMilliseconds(),
      completed_at = lastLog?.timestamp?.toIso8601String(),
      completed_at_epoch_ms = lastLog?.timestamp?.toEpochMilliseconds(),
    )
  } catch (e: Exception) {
    Console.error("Warning: failed to build result for session ${sessionId.value}: ${e.message}")
    null
  }

  private fun determineExecutionMode(
    status: SessionStatus,
    recordingInfo: SessionRecordingInfo,
  ): ExecutionMode = when {
    status is SessionStatus.Ended.SucceededWithSelfHeal -> ExecutionMode.SELF_HEAL
    status is SessionStatus.Ended.FailedWithSelfHeal -> ExecutionMode.SELF_HEAL
    recordingInfo.usedSelfHeal -> ExecutionMode.SELF_HEAL
    recordingInfo.skipReason == RecordingSkipReason.DISABLED_BY_CONFIG -> ExecutionMode.RECORDING_SKIPPED
    recordingInfo.available -> ExecutionMode.RECORDING_ONLY
    !recordingInfo.available -> ExecutionMode.AI_ONLY
    else -> ExecutionMode.UNKNOWN
  }

  private fun determineTrailSource(trailConfig: TrailConfig?): String =
    trailConfig?.source?.type?.name ?: SOURCE_TYPE_GENERATED

  /**
   * Same data as [extractFailureReason] in the markdown path, but the markdown variant
   * is `private` and we can't widen its visibility without leaking the markdown helper
   * into JSON callers. Duplicating the small `when` keeps both paths private and
   * locked to their respective pipelines.
   */
  private fun extractJsonFailureReason(status: SessionStatus): String? = when (status) {
    is SessionStatus.Ended.Failed -> status.exceptionMessage
    is SessionStatus.Ended.FailedWithSelfHeal -> status.exceptionMessage
    is SessionStatus.Ended.Cancelled -> status.cancellationMessage
    is SessionStatus.Ended.TimeoutReached -> status.message
    is SessionStatus.Ended.MaxCallsLimitReached ->
      "Max LLM calls limit reached (${status.maxCalls}) for: ${status.objectivePrompt}"
    else -> null
  }

  /**
   * Empty metadata for daemon-CLI-driven reports. The CI gradle CLI populates
   * [CiRunMetadata] from CI provider / `GIT_*` env vars; the daemon-side surfaces
   * intentionally do not — local devs running `trailblaze report --format json` aren't
   * in CI, and silently inheriting a developer's stale env vars would produce
   * misleading metadata. Subclasses can attach provenance by overriding
   * [generateJsonReport].
   */
  private fun emptyMetadata(): CiRunMetadata = CiRunMetadata(
    target_app = "",
    build_type = "",
    devices = emptyList(),
  )

  private fun Instant.toIso8601String(): String {
    val localDateTime = this.toLocalDateTime(TimeZone.UTC)
    return "${localDateTime.date}T${localDateTime.time}Z"
  }

}
