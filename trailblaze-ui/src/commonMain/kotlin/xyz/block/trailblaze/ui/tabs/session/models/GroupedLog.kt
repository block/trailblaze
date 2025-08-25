package xyz.block.trailblaze.ui.tabs.session.models

import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeLog

/**
 * Represents either a single log or a group of logs with the same llmResponseId
 */
sealed interface GroupedLog {
  val timestamp: Instant

  /**
   * A single log that doesn't have an llmResponseId or is the only log with that llmResponseId
   */
  data class Single(val log: TrailblazeLog) : GroupedLog {
    override val timestamp: Instant = log.timestamp
  }

  /**
   * A group of logs that share the same llmResponseId
   */
  data class Group(val llmResponseId: String, val logs: List<TrailblazeLog>) : GroupedLog {
    override val timestamp: Instant = logs.minOf { it.timestamp }
  }
}