package xyz.block.trailblaze.ui.utils

import xyz.block.trailblaze.logs.client.TrailblazeLog

object DisplayUtils {

  fun getLogTypeDisplayName(log: TrailblazeLog): String {
    return when (log) {
      is TrailblazeLog.TrailblazeLlmRequestLog -> "LLM Request"
      is TrailblazeLog.MaestroDriverLog -> "Maestro Driver"
      is TrailblazeLog.TrailblazeToolLog -> "Trailblaze Command"
      is TrailblazeLog.DelegatingTrailblazeToolLog -> "Delegating Trailblaze Tool"
      is TrailblazeLog.MaestroCommandLog -> "Maestro Command"
      is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> "Agent Task Status"
      is TrailblazeLog.TrailblazeSessionStatusChangeLog -> "Session Status"
      is TrailblazeLog.ObjectiveStartLog -> "Objective Start"
      is TrailblazeLog.ObjectiveCompleteLog -> "Objective Complete"
    }
  }
}