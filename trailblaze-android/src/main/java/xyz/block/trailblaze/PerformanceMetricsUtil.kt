package xyz.block.trailblaze

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.measureTime
import xyz.block.trailblaze.util.Console

object PerformanceMetricsUtil {

  inline fun measureTimeAndPrint(description: String, block: () -> Unit): Duration {
    val time = TimeSource.Monotonic.measureTime(block)
    Console.log("PerformanceMetric: ${time.inWholeMilliseconds}ms to [$description]")
    return time
  }
}
