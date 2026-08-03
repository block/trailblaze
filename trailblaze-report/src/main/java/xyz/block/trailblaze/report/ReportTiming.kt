package xyz.block.trailblaze.report

import kotlin.time.TimeMark
import kotlin.time.TimeSource
import xyz.block.trailblaze.util.Console

/**
 * Minimal per-stage wall-clock timing for report generation. Every stage emits one
 * `[report-timing] <stage>: <ms>ms` line via [Console.log] so report wall-clock can be
 * attributed to parsing vs sprite extraction vs image compression vs the bun subprocess.
 * Durations come from [TimeSource.Monotonic], so they can't be skewed by wall-clock adjustments.
 */
object ReportTiming {

  /** Run [block], logging `[report-timing] <name>: <ms>ms` when it completes (even on throw). */
  inline fun <T> stage(name: String, block: () -> T): T {
    val start = TimeSource.Monotonic.markNow()
    try {
      return block()
    } finally {
      log(name, start)
    }
  }

  /**
   * Log `[report-timing] <name>: <ms>ms` for a span started at [start] - for spans where wrapping
   * the code in [stage] would reindent it.
   */
  fun log(name: String, start: TimeMark) {
    Console.log("[report-timing] $name: ${start.elapsedNow().inWholeMilliseconds}ms")
  }
}
