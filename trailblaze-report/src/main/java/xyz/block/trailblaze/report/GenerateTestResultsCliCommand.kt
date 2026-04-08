package xyz.block.trailblaze.report

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.llm.LlmLogCostEnricher
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.recordings.TrailRecordings
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
import xyz.block.trailblaze.yaml.TrailConfig
import java.io.File
import kotlin.io.path.Path

/**
 * CLI command to generate test results YAML/JSON from a logs directory.
 *
 * This command reads session logs from a directory and generates a structured
 * artifact containing test results for reporting and analytics.
 *
 * Usage:
 * ```
 * generate-test-results ./logs
 * generate-test-results ./logs --output results.yaml
 * generate-test-results ./logs --format json
 * generate-test-results ./logs --format yaml --output results.yml
 * ```
 *
 * Environment variables (optional, used for metadata):
 * - TRAILBLAZE_TARGET_APP
 * - TRAILBLAZE_BUILD_TYPE
 * - TRAILBLAZE_DEVICES
 * - BUILDKITE_BUILD_URL / CI_BUILD_URL
 * - BUILDKITE_BUILD_NUMBER / CI_BUILD_NUMBER
 * - BUILDKITE_COMMIT / GIT_COMMIT
 * - BUILDKITE_BRANCH / GIT_BRANCH
 */
open class GenerateTestResultsCliCommand : CliktCommand(name = "generate-test-results") {

  private val logsDirArg by argument(
    name = "logs-dir",
    help = "Directory containing Trailblaze log files",
  ).path(
    mustExist = true,
    canBeFile = false,
    mustBeReadable = true
  ).default(Path("./logs"))

  private val verbose by option(
    help = "Show detailed information for each test",
  ).flag(default = true)

  private val outputArg by argument(
    name = "output",
    help = "Output file path for the report",
  ).file(
    mustExist = false,
    canBeFile = true,
    canBeDir = false,
  ).optional()

  private val outputFormat by option(
    help = "Output format for the report (json or yaml)",
  ).enum<OutputFormat>()
    .default(OutputFormat.JSON)

  private val shouldDeduplicateRetries by option(
    "--dedup",
    help = "Collapse retried tests into a single result, keeping the best outcome. " +
      "Use in CI when retries are configured. Omit for local/live reports to see all runs.",
  ).flag(default = false)

  private val logsDir: File get() = logsDirArg.toFile()

  private enum class OutputFormat(val extension: String) {
    YAML("yaml"),
    JSON("json"),
  }

  private val yamlSerializer = Yaml(
    configuration = YamlConfiguration(
      encodeDefaults = true,
      strictMode = false,
    ),
  )

  /** JSON serializer that encodes default values (for complete report output) */
  private val jsonSerializer = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * The generated report, available after [run] completes.
   * Subclasses can access this to perform additional processing (e.g., uploading to CDP).
   */
  protected var generatedReport: CiSummaryReport? = null
    private set

  override fun run() {
    val costEnricher = LlmLogCostEnricher { modelId -> BuiltInLlmModelRegistry.find(modelId) }
    val logsRepo = LogsRepo(
      logsDir = logsDir,
      watchFileSystem = false,
      costEnricher = costEnricher::enrich,
    )
    val sessionIds = logsRepo.getSessionIds()

    if (sessionIds.isEmpty()) {
      logsRepo.close()
      Console.log("⚠️  No sessions found in: ${logsDir.absolutePath}")
      return
    }

    // Collect session results and tool usage data
    val metadata = buildMetadataFromEnvironment()
    val sessionResults = mutableListOf<SessionResult>()
    val errors = mutableListOf<String>()
    val allSessionToolUsage = mutableListOf<SessionToolUsage>()

    for (sessionId in sessionIds) {
      try {
        val sessionInfo = logsRepo.getSessionInfo(sessionId)
        val logs = logsRepo.getLogsForSession(sessionId)
        if (sessionInfo == null) {
          errors.add("$sessionId: Could not load session info")
          continue
        }

        val platform = sessionInfo.trailblazeDeviceInfo?.platform?.name?.lowercase() ?: "unknown"
        val outcome = mapStatusToOutcome(sessionInfo.latestStatus)
        val title = sessionInfo.trailConfig?.title
          ?: sessionInfo.trailConfig?.id
          ?: sessionInfo.trailFilePath?.removePrefix("trails/")?.removeSuffix(TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX)
          ?: sessionInfo.testName?.takeIf { it.isNotBlank() }?.let { name ->
            sessionInfo.testClass?.let { cls -> "$cls:$name" } ?: name
          }
          ?: sessionInfo.testClass
          ?: sessionId.value
        val sessionRecordingInfo = SessionRecordingInfo.fromLogs(logs)
        // Get timestamps
        val firstLog = logs.firstOrNull()
        val lastLog = logs.lastOrNull()

        // Collect tool usage from TrailblazeToolLog entries
        allSessionToolUsage.add(extractToolUsage(title, outcome, logs))

        sessionResults.add(
          SessionResult(
            session_id = sessionId,
            title = title,
            platform = platform,
            execution_mode = determineExecutionMode(
              status = sessionInfo.latestStatus,
              sessionRecordingInfo = sessionRecordingInfo
            ),
            trail_source = determineTrailSource(sessionInfo.trailConfig),
            device_classifier = sessionInfo.trailblazeDeviceInfo?.classifiers
              ?.joinToString("-") { it.classifier },
            outcome = outcome,
            failure_reason = extractFailureReason(sessionInfo.latestStatus),
            has_recorded_steps = sessionInfo.hasRecordedSteps,
            recording_available = sessionRecordingInfo.available,
            recording_skip_reason = sessionRecordingInfo.skipReason,
            duration_ms = sessionInfo.durationMs,
            llm_call_count = countLlmCalls(logs),
            llm_cost_usd = logs.computeUsageSummary()?.totalCostInUsDollars,
            started_at = firstLog?.timestamp?.toIso8601String(),
            started_at_epoch_ms = firstLog?.timestamp?.toEpochMilliseconds(),
            completed_at = lastLog?.timestamp?.toIso8601String(),
            completed_at_epoch_ms = lastLog?.timestamp?.toEpochMilliseconds(),
          )
        )
      } catch (e: Exception) {
        errors.add("$sessionId: ${e.message}")
      }
    }

    // Only deduplicate when explicitly requested (e.g. CI final reports).
    // Local and live HTML reports keep all runs visible.
    val finalResults = if (shouldDeduplicateRetries) {
      deduplicateRetries(sessionResults)
    } else {
      sessionResults
    }

    // Print summary to console
    printSummary(finalResults, errors)

    // Print detailed results if requested
    if (verbose) {
      printDetailedResults(finalResults)
      printFailedTests(finalResults)
    }

    val summaryReport = CiSummaryReport(
      metadata = metadata,
      results = finalResults,
    )

    val output = outputArg ?: File(logsDir, "trailblaze_test_report.${outputFormat.extension}")
    val content = when (outputFormat) {
      OutputFormat.JSON -> jsonSerializer.encodeToString(value = summaryReport)
      OutputFormat.YAML -> yamlSerializer.encodeToString(value = summaryReport)
    }

    output.writeText(content)
    Console.log("")
    Console.log("📄 Summary written to: ${output.absolutePath}")

    // Generate tool usage report
    if (allSessionToolUsage.isNotEmpty()) {
      val markdown = generateToolUsageReport(allSessionToolUsage)
      val toolUsageFile = File(logsDir, "trailblaze_tool_usage.html")
      toolUsageFile.writeText(wrapMarkdownInHtml(markdown))
      Console.log("🔧 Tool usage report written to: ${toolUsageFile.absolutePath}")
    }

    // Store the generated report for subclasses to access
    generatedReport = summaryReport

    // Clean up file watchers to allow JVM to exit
    logsRepo.close()
  }

  private fun printFailedTests(results: List<SessionResult>) {
    val failedTests = results.filter { it.outcome == Outcome.FAILED }

    val output = buildString {
      if (failedTests.isEmpty()) {
        appendLine()
        appendLine("✅ No failed tests!")
        appendLine()
      } else {
        appendLine()
        appendLine("❌ FAILED TESTS (${failedTests.size})")
        appendLine("────────────────────────────────────────────────────────────")

        failedTests.forEach { result ->
          val platformIcon = getPlatformIcon(result.platform)

          appendLine()
          appendLine("❌ ${result.title}")
          appendLine("   Platform: $platformIcon ${result.platform}")
          appendLine("   Duration: ${formatDuration(result.duration_ms)}")
          if (result.failure_reason != null) {
            appendLine("   Reason: ${result.failure_reason}")
          }
          appendLine("   Session: ${result.session_id}")
        }
        appendLine()
      }
    }
    Console.log(output)
  }

  private fun buildMetadataFromEnvironment(): CiRunMetadata {
    fun getEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    fun getEnvList(name: String): List<String> = getEnv(name)?.split(",")?.map {
      it.trim()
    }?.filter {
      it.isNotEmpty()
    } ?: emptyList()

    return CiRunMetadata(
      target_app = getEnv("TRAILBLAZE_TARGET_APP") ?: "",
      build_type = getEnv("TRAILBLAZE_BUILD_TYPE") ?: "",
      devices = getEnvList("TRAILBLAZE_DEVICES"),
      android_build_url = getEnv("ANDROID_BUILD_URL"),
      ios_build_url = getEnv("IOS_BUILD_URL"),
      retry_count = getEnv("TRAILBLAZE_TEST_RETRY_COUNT")?.toIntOrNull() ?: 0,
      ai_enabled = getEnv("TRAILBLAZE_AI_ENABLED")?.toBoolean() ?: true,
      ai_fallback_enabled = getEnv("TRAILBLAZE_AI_FALLBACK_ENABLED")?.toBoolean() ?: true,
      parallel_execution = getEnv("TRAILBLAZE_PARALLEL_EXECUTION")?.toBoolean() ?: false,
      ci_build_url = getEnv("BUILDKITE_BUILD_URL") ?: getEnv("CI_BUILD_URL"),
      ci_build_number = getEnv("BUILDKITE_BUILD_NUMBER") ?: getEnv("CI_BUILD_NUMBER"),
      ci_build_source = getEnv("BUILDKITE_SOURCE") ?: getEnv("CI_BUILD_SOURCE"),
      ci_build_message = getEnv("BUILDKITE_MESSAGE") ?: getEnv("CI_BUILD_MESSAGE"),
      ci_build_label = getEnv("BUILDKITE_LABEL") ?: getEnv("CI_BUILD_LABEL"),
      git_commit = getEnv("BUILDKITE_COMMIT") ?: getEnv("GIT_COMMIT"),
      git_branch = getEnv("BUILDKITE_BRANCH") ?: getEnv("GIT_BRANCH"),
    )
  }

  private fun mapStatusToOutcome(status: SessionStatus): Outcome = when (status) {
    is SessionStatus.Ended.Succeeded -> Outcome.PASSED
    is SessionStatus.Ended.SucceededWithFallback -> Outcome.PASSED
    is SessionStatus.Ended.Failed -> Outcome.FAILED
    is SessionStatus.Ended.FailedWithFallback -> Outcome.FAILED
    is SessionStatus.Ended.Cancelled -> Outcome.CANCELLED
    is SessionStatus.Ended.TimeoutReached -> Outcome.TIMEOUT
    is SessionStatus.Ended.MaxCallsLimitReached -> Outcome.MAX_CALLS_REACHED
    is SessionStatus.Started -> Outcome.ERROR
    is SessionStatus.Unknown -> Outcome.ERROR
  }

  private fun determineExecutionMode(status: SessionStatus, sessionRecordingInfo: SessionRecordingInfo): ExecutionMode {
    return when {
      status is SessionStatus.Ended.SucceededWithFallback -> ExecutionMode.AI_FALLBACK
      status is SessionStatus.Ended.FailedWithFallback -> ExecutionMode.AI_FALLBACK
      sessionRecordingInfo.usedAiFallback -> ExecutionMode.AI_FALLBACK
      sessionRecordingInfo.skipReason == RecordingSkipReason.DISABLED_BY_CONFIG -> ExecutionMode.RECORDING_SKIPPED
      sessionRecordingInfo.available -> ExecutionMode.RECORDING_ONLY
      !sessionRecordingInfo.available -> ExecutionMode.AI_ONLY
      else -> ExecutionMode.UNKNOWN
    }
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

  /**
   * Deduplicates retried tests by keeping one result per unique title.
   *
   * When a test is retried, multiple sessions share the same title. This groups
   * them by title, picks the best result (preferring PASSED, then latest attempt),
   * and annotates the kept result with retry metadata (attempt number, total attempts,
   * replaced session IDs).
   */
  private fun deduplicateRetries(results: List<SessionResult>): List<SessionResult> {
    return results
      .groupBy { it.title }
      .values
      .map { attempts ->
        if (attempts.size == 1) return@map attempts.single()

        // Sort by start time so attempt numbering is chronological
        val sorted = attempts.sortedBy { it.started_at_epoch_ms ?: 0L }

        // Prefer the latest PASSED result; if none passed, take the last attempt
        val best = sorted.lastOrNull { it.outcome == Outcome.PASSED } ?: sorted.last()
        val bestIndex = sorted.indexOf(best)
        val replaced = sorted.filter { it.session_id != best.session_id }

        best.copy(
          attempt = bestIndex + 1,
          total_attempts = sorted.size,
          replaced_session_ids = replaced.map { it.session_id },
          replaced_failure_reasons = replaced.mapNotNull { it.failure_reason },
        )
      }
  }

  /**
   * Determines source type from trail config's Source
   * This will assume generated if trail config or source is null
   */
  private fun determineTrailSource(trailConfig: TrailConfig?): String {
    return trailConfig?.source?.type?.name ?: SOURCE_TYPE_GENERATED
  }

  private fun countLlmCalls(logs: List<TrailblazeLog>): Int {
    return logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>().size
  }

  // -- Tool usage report --

  private data class ToolCallStats(
    var calls: Int = 0,
    var successes: Int = 0,
    var failures: Int = 0,
  )

  private data class SessionToolUsage(
    val title: String,
    val outcome: Outcome,
    /** Tool name -> call stats from executed TrailblazeToolLog entries */
    val executedTools: Map<String, ToolCallStats>,
    /** Tool name -> call count from LLM request actions (what the LLM requested) */
    val llmRequestedTools: Map<String, Int>,
  )

  private fun extractToolUsage(
    title: String,
    outcome: Outcome,
    logs: List<TrailblazeLog>,
  ): SessionToolUsage {
    val executedTools = mutableMapOf<String, ToolCallStats>()
    for (log in logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()) {
      val stats = executedTools.getOrPut(log.toolName) { ToolCallStats() }
      stats.calls++
      if (log.successful) stats.successes++ else stats.failures++
    }

    val llmRequestedTools = mutableMapOf<String, Int>()
    for (log in logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>()) {
      for (action in log.actions) {
        llmRequestedTools[action.name] = (llmRequestedTools[action.name] ?: 0) + 1
      }
    }

    return SessionToolUsage(
      title = title,
      outcome = outcome,
      executedTools = executedTools,
      llmRequestedTools = llmRequestedTools,
    )
  }

  private fun generateToolUsageReport(sessions: List<SessionToolUsage>): String = buildString {
    appendLine("# Trailblaze Tool Usage Report")
    appendLine()

    // --- Aggregate stats ---
    val aggregateExecuted = mutableMapOf<String, ToolCallStats>()
    val aggregateLlmRequested = mutableMapOf<String, Int>()
    for (session in sessions) {
      for ((tool, stats) in session.executedTools) {
        val agg = aggregateExecuted.getOrPut(tool) { ToolCallStats() }
        agg.calls += stats.calls
        agg.successes += stats.successes
        agg.failures += stats.failures
      }
      for ((tool, count) in session.llmRequestedTools) {
        aggregateLlmRequested[tool] = (aggregateLlmRequested[tool] ?: 0) + count
      }
    }

    appendLine("## Summary")
    appendLine()
    appendLine("- **Sessions:** ${sessions.size}")
    appendLine("- **Unique tools executed:** ${aggregateExecuted.size}")
    appendLine("- **Unique tools requested by LLM:** ${aggregateLlmRequested.size}")
    appendLine("- **Total tool executions:** ${aggregateExecuted.values.sumOf { it.calls }}")
    appendLine("- **Total LLM tool requests:** ${aggregateLlmRequested.values.sum()}")
    appendLine()

    // --- Table: tool execution frequency ---
    appendLine("## Tool Execution Frequency")
    appendLine()
    appendLine("Tools actually executed (from recordings and AI), sorted by call count:")
    appendLine()
    appendLine("| Tool | Calls | Success | Failed | Success Rate | Sessions |")
    appendLine("|------|------:|--------:|-------:|-------------:|---------:|")
    for ((tool, stats) in aggregateExecuted.entries.sortedByDescending { it.value.calls }) {
      val rate = if (stats.calls > 0) "%.0f%%".format(stats.successes.toDouble() / stats.calls * 100) else "-"
      val sessionCount = sessions.count { tool in it.executedTools }
      appendLine("| $tool | ${stats.calls} | ${stats.successes} | ${stats.failures} | $rate | $sessionCount |")
    }
    appendLine()

    // --- Test × Tool matrix ---
    val allToolNames = (aggregateExecuted.keys + aggregateLlmRequested.keys).sorted()
    if (allToolNames.isNotEmpty()) {
      appendLine("## Test × Tool Usage Matrix")
      appendLine()
      appendLine("Tool call counts per test (executed + LLM-requested). Outcome and total shown per row.")
      appendLine()

      // Header row: Test | Outcome | tool1 | tool2 | ... | Total
      append("| Test | Outcome |")
      for (tool in allToolNames) append(" $tool |")
      appendLine(" Total |")

      // Separator
      append("|------|---------|")
      for (tool in allToolNames) append("------:|")
      appendLine("------:|")

      // Data rows
      for (session in sessions.sortedBy { it.title }) {
        val outcomeTag = when (session.outcome) {
          Outcome.PASSED -> "PASS"
          Outcome.FAILED -> "FAIL"
          Outcome.MAX_CALLS_REACHED -> "MAX_CALLS"
          Outcome.TIMEOUT -> "TIMEOUT"
          Outcome.ERROR -> "ERROR"
          Outcome.CANCELLED -> "CANCELLED"
          Outcome.SKIPPED -> "SKIPPED"
        }
        var rowTotal = 0
        append("| ${session.title} | $outcomeTag |")
        for (tool in allToolNames) {
          val execCount = session.executedTools[tool]?.calls ?: 0
          val llmCount = session.llmRequestedTools[tool] ?: 0
          val count = maxOf(execCount, llmCount)
          rowTotal += count
          append(" ${if (count > 0) count.toString() else "-"} |")
        }
        appendLine(" $rowTotal |")
      }
      appendLine()
    }
  }

  private fun wrapMarkdownInHtml(markdown: String): String {
    // Escape the markdown for embedding in a JS template literal
    val escaped = markdown
      .replace("\\", "\\\\")
      .replace("`", "\\`")
      .replace("\${", "\\\${")
    return """
      |<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |<meta charset="UTF-8">
      |<meta name="viewport" content="width=device-width, initial-scale=1.0">
      |<title>Trailblaze Tool Usage Report</title>
      |<script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
      |<style>
      |  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; max-width: 1200px; margin: 0 auto; padding: 20px; color: #24292f; }
      |  table { border-collapse: collapse; width: 100%; margin: 16px 0; }
      |  th, td { border: 1px solid #d0d7de; padding: 8px 12px; text-align: left; }
      |  th { background: #f6f8fa; font-weight: 600; }
      |  tr:nth-child(even) { background: #f6f8fa; }
      |  td:nth-child(n+2) { text-align: right; }
      |  code { background: #eff1f3; padding: 2px 6px; border-radius: 4px; font-size: 0.9em; }
      |  h1 { border-bottom: 1px solid #d0d7de; padding-bottom: 8px; }
      |  h2 { border-bottom: 1px solid #d0d7de; padding-bottom: 6px; margin-top: 32px; }
      |  h3 { margin-top: 24px; }
      |  ul { padding-left: 24px; }
      |  details { margin: 8px 0; }
      |  summary { cursor: pointer; font-weight: 600; }
      |</style>
      |</head>
      |<body>
      |<div id="content"></div>
      |<script>
      |  const md = `$escaped`;
      |  document.getElementById('content').innerHTML = marked.parse(md);
      |</script>
      |</body>
      |</html>
    """.trimMargin()
  }

  private fun printDetailedResults(results: List<SessionResult>) {
    val output = buildString {
      appendLine()
      appendLine("📋 DETAILED RESULTS")
      appendLine("────────────────────────────────────────────────────────────")

      results.sortedWith(compareBy({ it.outcome != Outcome.FAILED }, { it.platform }, { it.title }))
        .forEach { result ->
          val outcomeIcon = when (result.outcome) {
            Outcome.PASSED -> "✅"
            Outcome.FAILED -> "❌"
            Outcome.SKIPPED -> "⏭️"
            Outcome.ERROR -> "💥"
            Outcome.CANCELLED -> "🚫"
            Outcome.TIMEOUT -> "⏱️"
            Outcome.MAX_CALLS_REACHED -> "📞"
          }
          val platformIcon = getPlatformIcon(result.platform)

          appendLine()
          appendLine("$outcomeIcon ${result.title}")
          appendLine("   Platform: $platformIcon ${result.platform}")
          appendLine("   Duration: ${formatDuration(result.duration_ms)}")
          if (result.failure_reason != null) {
            val reason = result.failure_reason.take(100)
            appendLine("   Failure: $reason${if (result.failure_reason.length > 100) "..." else ""}")
          }
          appendLine("   Session: ${result.session_id}")
        }
      appendLine()
    }
    Console.log(output)
  }

  private fun formatDuration(ms: Long): String {
    return when {
      ms < 1000 -> "${ms}ms"
      ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
      else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }
  }

  private fun passBar(count: Int, total: Int, icon: String): String {
    if (total == 0) return ""
    val barLength = 20
    val filled = (count.toDouble() / total * barLength).toInt()
    return icon.repeat(filled.coerceIn(0, barLength))
  }

  private fun printSummary(results: List<SessionResult>, errors: List<String>) {
    val passed = results.count { it.outcome == Outcome.PASSED }
    val failed = results.count { it.outcome == Outcome.FAILED }
    val skipped = results.count { it.outcome == Outcome.SKIPPED }
    val cancelled = results.count { it.outcome == Outcome.CANCELLED }
    val timeout = results.count { it.outcome == Outcome.TIMEOUT }
    val maxCallsReached = results.count { it.outcome == Outcome.MAX_CALLS_REACHED }
    val total = results.size
    val passRate = if (total > 0) (passed.toDouble() / total) * 100 else 0.0
    val totalFailed = failed + timeout + maxCallsReached

    val output = buildString {
      appendLine()
      appendLine("╔════════════════════════════════════════════════════════════╗")
      appendLine("║              TRAILBLAZE TEST SUMMARY                       ║")
      appendLine("╚════════════════════════════════════════════════════════════╝")
      appendLine()
      appendLine("📁 Source: ${logsDir.absolutePath}")
      appendLine()

      // Overall results
      val passIcon = if (totalFailed == 0) "✅" else "❌"
      appendLine("$passIcon RESULTS")
      appendLine("   ├── Total:   $total")
      appendLine("   ├── Passed:  $passed ${passBar(passed, total, "🟢")}")
      appendLine("   ├── Failed:  $failed ${passBar(failed, total, "🔴")}")
      if (timeout > 0) appendLine("   ├── Timeout: $timeout ${passBar(timeout, total, "🟠")}")
      if (maxCallsReached > 0) appendLine("   ├── Max Calls: $maxCallsReached ${passBar(maxCallsReached, total, "🟠")}")
      if (cancelled > 0) appendLine("   ├── Cancelled: $cancelled ${passBar(cancelled, total, "⚪")}")
      appendLine("   ├── Skipped: $skipped ${passBar(skipped, total, "⚪")}")
      appendLine("   └── Pass Rate: ${"%.1f".format(passRate)}%")
      appendLine()

      // Platform breakdown
      val byPlatform = results.groupBy { it.platform }
      if (byPlatform.size > 1) {
        appendLine("📱 BY PLATFORM")
        byPlatform.forEach { (platform, platformResults) ->
          val platformIcon = getPlatformIcon(platform)
          val platformPassed = platformResults.count { it.outcome == Outcome.PASSED }
          val platformTotal = platformResults.size
          val platformRate = if (platformTotal > 0) (platformPassed.toDouble() / platformTotal) * 100 else 0.0
          appendLine("   ├── $platformIcon $platform: $platformPassed/$platformTotal (${"%.0f".format(platformRate)}%)")
        }
        appendLine()
      }

      // Errors
      if (errors.isNotEmpty()) {
        appendLine("⚠️  PROCESSING ERRORS (${errors.size})")
        errors.take(5).forEach { error ->
          appendLine("   └── $error")
        }
        if (errors.size > 5) {
          appendLine("   └── ... and ${errors.size - 5} more")
        }
        appendLine()
      }

      appendLine("════════════════════════════════════════════════════════════")
    }
    Console.log(output)
  }
}

private fun Instant.toIso8601String(): String {
  val localDateTime = this.toLocalDateTime(TimeZone.UTC)
  return "${localDateTime.date}T${localDateTime.time}Z"
}

/**
 * Returns an emoji icon for the given platform string.
 */
private fun getPlatformIcon(platform: String): String = when {
  platform.contains("android", ignoreCase = true) -> "🤖"
  platform.contains("ios", ignoreCase = true) -> "🍎"
  platform.contains("terminal", ignoreCase = true) -> "💳"
  platform.contains("web", ignoreCase = true) -> "🌐"
  else -> "📱"
}

fun main(args: Array<String>) = GenerateTestResultsCliCommand().main(args)
