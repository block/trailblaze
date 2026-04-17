package xyz.block.trailblaze.toolcalls

fun TrailblazeToolResult.isSuccess(): Boolean = this is TrailblazeToolResult.Success

/**
 * Runs each provided step in order. If any step returns an error, that error is returned immediately.
 * If all steps succeed, [TrailblazeToolResult.Success()] is returned.
 */
suspend inline fun runTrailblazeSteps(steps: List<suspend () -> TrailblazeToolResult>): TrailblazeToolResult {
  for (step in steps) {
    val result = step()
    if (!result.isSuccess()) return result
  }
  return TrailblazeToolResult.Success()
}

/** Alias for [runTrailblazeSteps] */
suspend inline fun runTrailblazeSteps(
  vararg steps: suspend () -> TrailblazeToolResult,
): TrailblazeToolResult = runTrailblazeSteps(steps.asList())

/**
 * A step with a human-readable name for transparency reporting.
 * Used with [runNamedTrailblazeSteps] to produce a summary of what a compound tool did.
 */
data class NamedStep(
  val name: String,
  val action: suspend () -> TrailblazeToolResult,
)

/**
 * Runs each named step in order, failing fast on error.
 *
 * Returns [TrailblazeToolResult.Success] with a message listing completed steps in arrow format:
 * ```
 * - Step name → sub-tool detail
 * - Step name (no sub-detail)
 * ```
 */
suspend fun runNamedTrailblazeSteps(vararg steps: NamedStep): TrailblazeToolResult {
  val lines = mutableListOf<String>()
  for (step in steps) {
    val result = step.action()
    if (!result.isSuccess()) return result
    val detail = (result as? TrailblazeToolResult.Success)
      ?.message?.trim()?.takeIf { it.isNotEmpty() }
    lines.add(if (detail != null) "- ${step.name} → $detail" else "- ${step.name}")
  }
  val message = lines.joinToString("\n").ifEmpty { null }
  return TrailblazeToolResult.Success(message = message)
}
