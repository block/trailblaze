package xyz.block.trailblaze.ui.utils

import xyz.block.trailblaze.logs.client.TrailblazeLog

object DisplayUtils {

  fun getLogTypeDisplayName(log: TrailblazeLog): String {
    return when (log) {
      is TrailblazeLog.TrailblazeLlmRequestLog -> if (log.llmRequestLabel != null) "LLM: ${log.llmRequestLabel}" else "LLM Request"
      is TrailblazeLog.AgentDriverLog -> "Driver Action"
      is TrailblazeLog.TrailblazeToolLog -> "Trailblaze Command"
      is TrailblazeLog.DelegatingTrailblazeToolLog -> "Delegating Trailblaze Tool"
      is TrailblazeLog.MaestroCommandLog -> "Maestro Command"
      is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> "Agent Task Status"
      is TrailblazeLog.TrailblazeSessionStatusChangeLog -> "Session Status"
      is TrailblazeLog.ObjectiveStartLog -> "Objective Start"
      is TrailblazeLog.ObjectiveCompleteLog -> "Objective Complete"
      is TrailblazeLog.PrerequisiteStartLog -> "Prerequisite Start"
      is TrailblazeLog.PrerequisiteCompleteLog -> "Prerequisite Complete"
      is TrailblazeLog.AttemptAiFallbackLog -> "Attempt AI Fallback"
      is TrailblazeLog.TrailblazeSnapshotLog -> "Snapshot"
      is TrailblazeLog.AccessibilityActionLog -> "Accessibility Action"
      is TrailblazeLog.McpAgentRunLog -> "MCP Agent Run"
      is TrailblazeLog.McpAgentIterationLog -> "MCP Agent Iteration"
      is TrailblazeLog.McpSamplingLog -> "MCP Sampling"
      is TrailblazeLog.McpAgentToolLog -> "MCP Tool"
      is TrailblazeLog.McpToolCallRequestLog -> "MCP Tool Request"
      is TrailblazeLog.McpToolCallResponseLog -> "MCP Tool Response"
      is TrailblazeLog.McpAskLog -> "Ask"
      is TrailblazeLog.TrailblazeProgressLog -> "Progress: ${log.eventType}"
    }
  }
}
