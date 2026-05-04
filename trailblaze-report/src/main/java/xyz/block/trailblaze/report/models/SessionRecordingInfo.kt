package xyz.block.trailblaze.report.models

import xyz.block.trailblaze.logs.client.TrailblazeLog

/**
 * Small helper class that is used to determine recording availability and skip reasons from logs.
 * Used in [xyz.block.trailblaze.report.GenerateTestResultsCliCommand]
 */
data class SessionRecordingInfo(
  val available: Boolean,
  val skipReason: RecordingSkipReason? = null,
  val usedSelfHeal: Boolean = false,
) {
  companion object {
    fun fromLogs(logs: List<TrailblazeLog>): SessionRecordingInfo {
      val selfHealLogs = logs.filterIsInstance<TrailblazeLog.SelfHealInvokedLog>()
      return if (selfHealLogs.isNotEmpty()) {
        SessionRecordingInfo(
          available = true,
          skipReason = RecordingSkipReason.EXECUTION_FAILED,
          usedSelfHeal = true,
        )
      } else {
        val llmRequestCount = logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>()
        if (llmRequestCount.isEmpty()) {
          SessionRecordingInfo(
            available = true,
            skipReason = null,
            usedSelfHeal = false,
          )
        } else {
          SessionRecordingInfo(
            available = false,
            skipReason = RecordingSkipReason.NOT_FOUND,
            usedSelfHeal = false,
          )
        }
      }
    }
  }
}