package xyz.block.trailblaze.mcp.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Sends real-time progress notifications to MCP clients during automation.
 *
 * This class bridges Trailblaze's LogsRepo event system with MCP progress notifications,
 * allowing clients to see what's happening during TRAILBLAZE_AS_AGENT mode execution.
 *
 * ## Architecture
 *
 * ```
 * MCP Client                    McpProgressNotifier                  LogsRepo
 *     |                                   |                             |
 *     |<-- notification: "Starting..." ---|<-- ObjectiveStartLog -------|
 *     |<-- notification: "Capturing..." --|<-- AgentDriverLog ----------|
 *     |<-- notification: "Reasoning..." --|<-- TrailblazeLlmRequestLog -|
 *     |<-- notification: "Executing..." --|<-- TrailblazeToolLog -------|
 *     |<-- notification: "Complete" ------|<-- ObjectiveCompleteLog ----|
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val notifier = McpProgressNotifier(sessionContext, logsRepo, notifierScope)
 *
 * // Start monitoring a session
 * notifier.startMonitoring(trailblazeSessionId)
 *
 * // Automation runs, progress notifications are sent automatically
 *
 * // Stop monitoring when done
 * notifier.stopMonitoring()
 * ```
 */
class McpProgressNotifier(
  private val sessionContext: TrailblazeMcpSessionContext,
  private val logsRepo: LogsRepo,
  private val scope: CoroutineScope,
) {
  private var monitoringJob: Job? = null
  private var progressIndex = java.util.concurrent.atomic.AtomicInteger(0)

  /**
   * Starts monitoring a Trailblaze session's logs and forwarding them as MCP progress notifications.
   *
   * @param trailblazeSessionId The Trailblaze session ID to monitor (from LogsRepo)
   */
  fun startMonitoring(trailblazeSessionId: SessionId) {
    stopMonitoring() // Cancel any existing monitoring
    progressIndex.set(0)
    Console.log("[McpProgressNotifier] Starting monitoring for session: $trailblazeSessionId")

    val logsFlow: Flow<List<TrailblazeLog>> = logsRepo.getSessionLogsFlow(trailblazeSessionId)

    monitoringJob = scope.launch {
      var lastLogCount = 0
      Console.log("[McpProgressNotifier] Started log collection coroutine")

      logsFlow.collect { logs ->
        // Only process new logs since last collection
        val newLogs = logs.drop(lastLogCount)
        if (newLogs.isNotEmpty()) {
          Console.log("[McpProgressNotifier] Received ${newLogs.size} new log(s), total: ${logs.size}")
        }
        lastLogCount = logs.size

        newLogs.forEach { log ->
          Console.log("[McpProgressNotifier] Processing log: ${log::class.simpleName}")
          val notification = mapLogToNotification(log)
          if (notification != null) {
            sendNotification(notification)
          }
        }
      }
    }
  }

  /**
   * Stops monitoring and cancels the subscription.
   */
  fun stopMonitoring() {
    monitoringJob?.cancel()
    monitoringJob = null
  }

  /**
   * Maps a TrailblazeLog to a progress notification message.
   * Returns null for log types we don't want to surface to MCP clients.
   */
  private fun mapLogToNotification(log: TrailblazeLog): ProgressNotificationData? = when (log) {
    is TrailblazeLog.ObjectiveStartLog -> {
      val prompt = log.promptStep.prompt ?: "Unknown objective"
      ProgressNotificationData(
        message = "Starting: $prompt",
        category = "objective",
      )
    }

    is TrailblazeLog.ObjectiveCompleteLog -> {
      val prompt = log.promptStep.prompt ?: "Unknown objective"
      val status = log.objectiveResult::class.simpleName ?: "Unknown"
      ProgressNotificationData(
        message = "Completed: $prompt (status: $status)",
        category = "objective",
      )
    }

    is TrailblazeLog.AgentDriverLog -> {
      val action = log.action.type.name.lowercase().replace("_", " ")
      ProgressNotificationData(
        message = "Screen captured (${log.deviceWidth}x${log.deviceHeight}) - $action",
        category = "screen",
      )
    }

    is TrailblazeLog.TrailblazeLlmRequestLog -> {
      val actionCount = log.actions.size
      val model = log.trailblazeLlmModel.modelId
      ProgressNotificationData(
        message = "LLM reasoning: found $actionCount action(s) to try (model: $model)",
        category = "llm",
      )
    }

    is TrailblazeLog.TrailblazeToolLog -> {
      val status = if (log.successful) "OK" else "FAILED"
      ProgressNotificationData(
        message = "Tool: ${log.toolName} [$status]",
        category = "tool",
      )
    }

    is TrailblazeLog.MaestroCommandLog -> {
      val status = if (log.successful) "success" else "failed"
      val resultDescription = when (val result = log.trailblazeToolResult) {
        is TrailblazeToolResult.Success -> "Success"
        is TrailblazeToolResult.Error -> result.errorMessage
      }
      ProgressNotificationData(
        message = "Maestro command: $status - $resultDescription",
        category = "command",
      )
    }

    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> {
      ProgressNotificationData(
        message = "Session status: ${log.sessionStatus::class.simpleName}",
        category = "session",
      )
    }

    is TrailblazeLog.DelegatingTrailblazeToolLog -> {
      ProgressNotificationData(
        message = "Delegating tool: ${log.toolName}",
        category = "tool",
      )
    }

    is TrailblazeLog.SelfHealInvokedLog -> {
      ProgressNotificationData(
        message = "Invoking self-heal for: ${log.promptStep.prompt}",
        category = "self_heal",
      )
    }

    is TrailblazeLog.McpAskLog -> {
      val answer = log.answer?.take(100) ?: log.errorMessage ?: "no answer"
      ProgressNotificationData(
        message = "Ask: ${log.question} → $answer",
        category = "ask",
      )
    }

    // Log types we don't surface as progress notifications (yet)
    is TrailblazeLog.AccessibilityActionLog,
    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog,
    is TrailblazeLog.TrailblazeSnapshotLog,
    is TrailblazeLog.McpAgentRunLog,
    is TrailblazeLog.McpAgentIterationLog,
    is TrailblazeLog.McpSamplingLog,
    is TrailblazeLog.McpAgentToolLog,
    is TrailblazeLog.McpToolCallRequestLog,
    is TrailblazeLog.McpToolCallResponseLog,
    is TrailblazeLog.TrailblazeProgressLog,
    -> null
  }

  private fun sendNotification(data: ProgressNotificationData) {
    sessionContext.sendIndeterminateProgressMessage(
      progress = progressIndex.getAndIncrement(),
      message = "[${data.category}] ${data.message}",
    )
  }
}

/**
 * Data class for progress notification content.
 */
data class ProgressNotificationData(
  val message: String,
  val category: String,
)
