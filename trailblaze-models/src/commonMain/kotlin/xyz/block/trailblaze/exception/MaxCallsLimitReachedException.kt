package xyz.block.trailblaze.exception

/**
 * Exception thrown when an objective reaches the maximum number of LLM calls allowed.
 * This is a distinct exception type to allow special handling at the session level.
 */
class MaxCallsLimitReachedException(
  val maxCalls: Int,
  val objectivePrompt: String,
  message: String = "Max LLM calls limit of $maxCalls reached for objective: $objectivePrompt",
) : TrailblazeException(message)
