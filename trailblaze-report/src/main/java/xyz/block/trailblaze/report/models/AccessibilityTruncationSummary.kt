package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.CaptureCoverage
import xyz.block.trailblaze.logs.client.TrailblazeLog

/**
 * Per-session aggregate of the Android on-device "truncated accessibility tree" signal that the
 * on-device gate (`TrailblazeAccessibilityService.awaitTreeStable` →
 * `HierarchyCoverageAssessor`) computes for every screen-state capture. Surfaces the signal as
 * machine-readable data in `trailblaze_test_report.json` so dashboards, LLMs, and CI graders can
 * detect tree-truncation trends without parsing `[capture-coverage]` lines out of logcat.
 *
 * Counts every Android capture log entry that carries a populated coverage assessment — so
 * `captures_total` is the assessor's denominator, not the literal screen-capture count.
 * Captures where the detector couldn't form an opinion (gate disabled via
 * `TRAILBLAZE_DISABLE_SETTLE_TREE_STABILITY=1`, unreadable window root, unknown screen
 * dimensions on a degraded device) carry `captureCoverage == null` on the log and are
 * excluded here, so the ratio reflects "of measurable captures, this fraction was flagged"
 * rather than artificially diluting truncation into ungated baseline noise.
 *
 * Examples are capped at [EXAMPLE_LIMIT] so the JSON stays readable on long-tail sessions
 * where every capture is flagged.
 *
 * The detector is a heuristic — see [CaptureCoverage]. Treat a high ratio here as "could be" /
 * "often indicates" an app-side accessibility problem, not a hard verdict.
 *
 * Null on non-Android sessions and on Android sessions that emitted no captures with the
 * coverage field populated (legacy logs predating the field, or every capture missed the
 * gate's window).
 */
@Serializable
data class AccessibilityTruncationSummary(
  /** Captures whose on-device assessment flagged the tree as truncated. */
  val captures_truncated: Int,
  /** Captures with a coverage assessment populated (the denominator). */
  val captures_total: Int,
  /** Up to [EXAMPLE_LIMIT] examples, one per flagged capture, in session-log order. */
  val examples: List<TruncationExample>,
) {
  @Serializable
  data class TruncationExample(
    /** Milliseconds since epoch — same units as `started_at_epoch_ms` on the parent session. */
    val timestamp_ms: Long,
    /** Detector verdict text — same wording as the `[capture-coverage]` log line. */
    val reason: String,
    /** Fraction of screen width spanned by content nodes, in `[0,1]`. */
    val horizontal_coverage: Double,
    /** Fraction of screen height spanned by content nodes, in `[0,1]`. */
    val vertical_coverage: Double,
  )

  companion object {
    const val EXAMPLE_LIMIT = 5

    /**
     * Walks [logs] for capture entries (`AgentDriverLog`, `TrailblazeSnapshotLog`) carrying a
     * non-null [CaptureCoverage] and rolls them into a summary. Returns null when no entry
     * carries the field — that's either a non-Android session or a pre-field run, and we'd
     * rather omit the JSON object than emit `"captures_total": 0` and have a consumer dashboard
     * compute a ratio over zero.
     */
    fun fromLogs(logs: List<TrailblazeLog>): AccessibilityTruncationSummary? {
      var total = 0
      var truncated = 0
      val examples = mutableListOf<TruncationExample>()
      for (log in logs) {
        val (coverage, timestamp) = when (log) {
          is TrailblazeLog.AgentDriverLog -> log.captureCoverage to log.timestamp
          is TrailblazeLog.TrailblazeSnapshotLog -> log.captureCoverage to log.timestamp
          else -> continue
        }
        coverage ?: continue
        total++
        if (coverage.looksTruncated) {
          truncated++
          if (examples.size < EXAMPLE_LIMIT) {
            examples.add(
              TruncationExample(
                timestamp_ms = timestamp.toEpochMilliseconds(),
                reason = coverage.reason,
                horizontal_coverage = coverage.horizontalCoverage,
                vertical_coverage = coverage.verticalCoverage,
              )
            )
          }
        }
      }
      if (total == 0) return null
      return AccessibilityTruncationSummary(
        captures_truncated = truncated,
        captures_total = total,
        examples = examples,
      )
    }
  }
}
