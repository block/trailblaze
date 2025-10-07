package xyz.block.trailblaze.ui.tabs.session.models

import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.TraceId

/**
 * Represents either a single log or a group of logs with the same traceId
 */
sealed interface GroupedLog {
  val timestamp: Instant

  /**
   * A single log that doesn't have an traceId or is the only log with that traceId
   */
  data class Single(val log: TrailblazeLog) : GroupedLog {
    override val timestamp: Instant = log.timestamp
  }

  /**
   * A group of logs that share the same traceId
   */
  data class Group(val traceId: TraceId, val logs: List<TrailblazeLog>) : GroupedLog {
    override val timestamp: Instant = logs.minOf { it.timestamp }
  }
}