package xyz.block.trailblaze.segment

/**
 * One observed transition between two waypoints, distilled from a session log.
 *
 * A segment says: at session step [SegmentObservation.fromStep] the screen matched [from];
 * at session step [SegmentObservation.toStep] it matched [to]; in between the agent
 * issued the actions described by [triggers].
 *
 * Each trigger is a human-readable summary string — `tap ref=z639` for tap actions,
 * `inputText "hello"` for text input, etc. The string form deliberately unifies two
 * underlying sources: legacy [xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog]
 * entries (older direct-tool sessions) and modern
 * [xyz.block.trailblaze.logs.client.TrailblazeLog.McpSamplingLog] completions (today's
 * `blaze` flow, where the action is encoded in the LLM's structured response). A future
 * aggregator can use the string as a dedup key without reaching back into the source log type.
 *
 * Self-loops (`from == to`) are filtered out by [SessionSegmentExtractor] — when a tap
 * fails and the screen state doesn't change, that's a retry, not a transition. The
 * aggregator that merges observations across many sessions can later choose to surface
 * those if they turn out to be useful.
 */
data class TrailSegment(
  val from: String,
  val to: String,
  val triggers: List<String>,
  val observation: SegmentObservation,
)

/**
 * Where a [TrailSegment] was observed: a single session, the request-log step indices
 * (1-based) at the boundaries of the transition, and the wall-clock duration between
 * them. Multiple [SegmentObservation]s for the same `(from, to)` get aggregated by a
 * downstream layer that v1 doesn't yet build.
 */
data class SegmentObservation(
  val sessionDir: String,
  val fromStep: Int,
  val toStep: Int,
  val durationMs: Long,
)
