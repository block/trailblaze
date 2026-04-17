package xyz.block.trailblaze.cli

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionInfo
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.report.ReportTemplateResolver
import xyz.block.trailblaze.report.WasmReport
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils

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
        is SessionStatus.Ended.SucceededWithFallback -> passed++
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
      val outcome: String,
      val durationMs: Long,
      val llmCalls: Int,
      val costUsd: Double?,
      val failureReason: String?,
      val llmModel: String?,
      val platform: String?,
    )

    val sessions = mutableListOf<SessionData>()
    for (sessionId in sessionIds) {
      val logs = logsRepo.getLogsForSession(sessionId)
      val sessionInfo = logs.getSessionInfo() ?: continue
      val status = statuses[sessionId] ?: SessionStatus.Unknown

      val usageSummary = logs.computeUsageSummary()

      sessions.add(
        SessionData(
          title = sessionInfo.displayName,
          outcome = mapStatusToOutcomeLabel(status),
          durationMs = sessionInfo.durationMs,
          llmCalls = countLlmCalls(logs),
          costUsd = usageSummary?.totalCostInUsDollars,
          failureReason = extractFailureReason(status),
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
    val passed = sessions.count { it.outcome == "PASSED" }
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
        val icon = if (session.outcome == "PASSED") "PASSED" else "FAILED"
        appendLine("### ${session.title} — $icon")
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
   * The base implementation checks for `trailblaze-ui/` (open-source standalone repo).
   * Internal builds override this to also check monorepo-specific paths.
   */
  protected open fun findTrailblazeUiDir(gitRoot: File?): File? {
    if (gitRoot == null) return null
    val standalonePath = File(gitRoot, "trailblaze-ui")
    if (standalonePath.exists()) return standalonePath
    return null
  }

  private fun mapStatusToOutcomeLabel(status: SessionStatus): String = when (status) {
    is SessionStatus.Ended.Succeeded -> "PASSED"
    is SessionStatus.Ended.SucceededWithFallback -> "PASSED"
    is SessionStatus.Ended.Failed -> "FAILED"
    is SessionStatus.Ended.FailedWithFallback -> "FAILED"
    is SessionStatus.Ended.Cancelled -> "CANCELLED"
    is SessionStatus.Ended.TimeoutReached -> "TIMEOUT"
    is SessionStatus.Ended.MaxCallsLimitReached -> "MAX_CALLS_REACHED"
    is SessionStatus.Started -> "ERROR"
    is SessionStatus.Unknown -> "ERROR"
  }

  private fun extractFailureReason(status: SessionStatus): String? = when (status) {
    is SessionStatus.Ended.Failed -> status.exceptionMessage
    is SessionStatus.Ended.FailedWithFallback -> status.exceptionMessage
    is SessionStatus.Ended.Cancelled -> status.cancellationMessage
    is SessionStatus.Ended.TimeoutReached -> status.message
    is SessionStatus.Ended.MaxCallsLimitReached ->
      "Max LLM calls limit reached (${status.maxCalls}) for: ${status.objectivePrompt}"
    else -> null
  }

  private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
  }

  private fun countLlmCalls(logs: List<TrailblazeLog>): Int {
    return logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>().size
  }

}
