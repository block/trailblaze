package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.client.TrailblazeLog

@Serializable
data class LogsSummary(
  val count: Int,
  val statusMessage: String,
  val sessions: List<SessionSummary> = emptyList(),
) {
  companion object {
    fun fromLogs(sessionMap: Map<String, List<TrailblazeLog>>, isStandaloneFileReport: Boolean): LogsSummary = LogsSummary(
      count = sessionMap.size,
      statusMessage = "",
      sessions = sessionMap.mapNotNull { (sessionId, logs) ->
        if (logs.isNotEmpty()) {
          SessionSummary.fromLogs(
            sessionId = sessionId,
            logs = logs,
            isStandaloneFileReport = isStandaloneFileReport,
          )
        } else {
          null
        }
      },
    )
  }
}
