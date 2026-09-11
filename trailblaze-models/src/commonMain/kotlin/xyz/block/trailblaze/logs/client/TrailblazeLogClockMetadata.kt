package xyz.block.trailblaze.logs.client

import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain

/**
 * Copies this log with updated clock state — [TrailblazeLog.timestamp] and the two fields that
 * describe it, [TrailblazeLog.clock] / [TrailblazeLog.hostReceivedAt] — preserving everything
 * else. The sealed hierarchy has no polymorphic `copy`, so this is the one place that enumerates
 * every subclass — a new log type fails to compile here until it's added, which is the point: an
 * unstamped log type would silently lose its clock domain.
 *
 * Defaults keep the current values, so callers update one field at a time:
 * - the emitter stamps `withClockMetadata(clock = DEVICE)` at emission;
 * - host ingestion stamps `withClockMetadata(hostReceivedAt = now)` on device-clock logs;
 * - [normalizedToHostClock] re-stamps `timestamp` together with the `clock` that now describes it.
 *
 * The [timestamp] parameter is for moving a stamp between clock domains, never for retiming an
 * event: change it only alongside the [clock] that says which domain it is now in.
 */
@Suppress("DEPRECATION")
fun TrailblazeLog.withClockMetadata(
  timestamp: Instant = this.timestamp,
  clock: TrailblazeClockDomain? = this.clock,
  hostReceivedAt: Instant? = this.hostReceivedAt,
): TrailblazeLog = when (this) {
  is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.TrailblazeSessionStatusChangeLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.TrailblazeLlmRequestLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.MaestroCommandLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.AccessibilityActionLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.AgentDriverLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.DelegatingTrailblazeToolLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.TrailblazeToolLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.ObjectiveStartLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.ObjectiveCompleteLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.SelfHealInvokedLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.TrailblazeSnapshotLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.McpAgentRunLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.McpAgentIterationLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.McpSamplingLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.McpAgentToolLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.McpToolCallRequestLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.McpToolCallResponseLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.McpAskLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
  is TrailblazeLog.TrailblazeProgressLog -> copy(timestamp = timestamp, clock = clock, hostReceivedAt = hostReceivedAt)
}
