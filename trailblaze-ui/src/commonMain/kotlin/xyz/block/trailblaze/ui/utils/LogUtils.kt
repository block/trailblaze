package xyz.block.trailblaze.ui.utils

import xyz.block.trailblaze.logs.client.TrailblazeLog

object LogUtils {


  fun logIndentLevel(log: TrailblazeLog): Int = when (log) {
    is TrailblazeLog.MaestroCommandLog -> 1
    is TrailblazeLog.AgentDriverLog -> 2
    is TrailblazeLog.AccessibilityActionLog -> 1
    is TrailblazeLog.TrailblazeLlmRequestLog -> 1
    is TrailblazeLog.TrailblazeToolLog -> 1
    is TrailblazeLog.DelegatingTrailblazeToolLog -> 2
    else -> 0
  }
}