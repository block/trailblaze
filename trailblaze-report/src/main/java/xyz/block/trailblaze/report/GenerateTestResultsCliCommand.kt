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
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.models.CiRunMetadata
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.report.models.ExecutionMode
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.models.RecordingSkipReason
import xyz.block.trailblaze.report.models.SessionRecordingInfo
import xyz.block.trailblaze.report.models.SessionResult
import xyz.block.trailblaze.report.utils.LogsRepo
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
 * generate-test-results ./logs --json   # shorthand for --format json
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
class GenerateTestResultsCliCommand : CliktCommand(name = "generate-test-results") {

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

  override fun run() {
    val logsRepo = LogsRepo(
      logsDir = logsDir,
      watchFileSystem = false
    )
    val sessionIds = logsRepo.getSessionIds()

    if (sessionIds.isEmpty()) {
      logsRepo.close()
      println("⚠️  No sessions found in: ${logsDir.absolutePath}")
      return
    }

    // Collect session results
    val metadata = buildMetadataFromEnvironment()
    val sessionResults = mutableListOf<SessionResult>()
    val errors = mutableListOf<String>()

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
        val title = sessionInfo.trailConfig?.title ?: sessionInfo.testName ?: sessionId.value
        val sessionRecordingInfo = SessionRecordingInfo.fromLogs(logs)
        // Get timestamps
        val firstLog = logs.firstOrNull()
        val lastLog = logs.lastOrNull()

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

    // Print summary to console
    printSummary(sessionResults, errors)

    // Print detailed results if requested
    if (verbose) {
      printDetailedResults(sessionResults)
      printFailedTests(sessionResults)
    }

    val summaryReport = CiSummaryReport(
      metadata = metadata,
      results = sessionResults,
    )

    val output = outputArg ?: File(logsDir, "trailblaze_test_report.${outputFormat.extension}")
    val content = when (outputFormat) {
      OutputFormat.JSON -> jsonSerializer.encodeToString(value = summaryReport)
      OutputFormat.YAML -> yamlSerializer.encodeToString(value = summaryReport)
    }

    output.writeText(content)
    println()
    println("📄 Summary written to: ${output.absolutePath}")

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
    print(output)
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
   * Determines source type from trail config's Source
   * This will assume generated if trail config or source is null
   */
  private fun determineTrailSource(trailConfig: TrailConfig?): String? {
    return trailConfig?.source?.type?.name
  }

  private fun countLlmCalls(logs: List<TrailblazeLog>): Int {
    return logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>().size
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
    print(output)
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
    print(output)
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
  platform.contains("squid", ignoreCase = true) -> "🦑"
  platform.contains("web", ignoreCase = true) -> "🌐"
  else -> "📱"
}

fun main(args: Array<String>) = GenerateTestResultsCliCommand().main(args)
