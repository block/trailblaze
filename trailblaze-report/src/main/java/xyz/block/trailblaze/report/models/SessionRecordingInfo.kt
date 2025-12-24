package xyz.block.trailblaze.report.models

import xyz.block.trailblaze.logs.client.TrailblazeLog

/**
 * Small helper class that is used to determine recording availability and skip reasons from logs.
 * Used in [xyz.block.trailblaze.report.GenerateTestResultsCliCommand]
 */
internal data class SessionRecordingInfo(
  val available: Boolean,
  val skipReason: RecordingSkipReason? = null,
  val usedAiFallback: Boolean = false,
) {
  companion object {
    fun fromLogs(logs: List<TrailblazeLog>): SessionRecordingInfo {
      val fallbackLogs = logs.filterIsInstance<TrailblazeLog.AttemptAiFallbackLog>()
      return if (fallbackLogs.isNotEmpty()) {
        SessionRecordingInfo(
          available = true,
          skipReason = RecordingSkipReason.EXECUTION_FAILED,
          usedAiFallback = true,
        )
      } else {
        val llmRequestCount = logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>()
        if (llmRequestCount.isEmpty()) {
          SessionRecordingInfo(
            available = true,
            skipReason = null,
            usedAiFallback = false,
          )
        } else {
          SessionRecordingInfo(
            available = false,
            skipReason = RecordingSkipReason.NOT_FOUND,
            usedAiFallback = false,
          )
        }
      }
    }
  }
}