package xyz.block.trailblaze.toolcalls

fun TrailblazeToolResult.isSuccess(): Boolean = this is TrailblazeToolResult.Success

/**
 * Runs each provided step in order. If any step returns an error, that error is returned immediately.
 * If all steps succeed, [TrailblazeToolResult.Success] is returned.
 */
suspend inline fun runTrailblazeSteps(steps: List<suspend () -> TrailblazeToolResult>): TrailblazeToolResult {
  for (step in steps) {
    val result = step()
    if (!result.isSuccess()) return result
  }
  return TrailblazeToolResult.Success
}

/** Alias for [runTrailblazeSteps] */
suspend inline fun runTrailblazeSteps(vararg steps: suspend () -> TrailblazeToolResult): TrailblazeToolResult = runTrailblazeSteps(steps.asList())
