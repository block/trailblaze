package xyz.block.trailblaze.exception

import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.failureMessage

/**
 * The message is the tool's own error text, never the result data class's `toString()`. This
 * message is what every consumer downstream reads as the failure reason: the on-device runner
 * copies it onto `RunYamlResponse.errorMessage`, the host raises it out of `trailblaze tool`, and
 * the agent loop hands it back to the LLM as the tool result. A dump
 * (`ExceptionThrown(errorMessage=…, command=TapTrailblazeTool(ref=…), stackTrace=null, …)`) in any
 * of those places buries the readable sentence it already contains.
 *
 * [trailblazeToolResult] stays available for callers that want the structure.
 */
class TrailblazeToolExecutionException(
  val tool: TrailblazeTool,
  // Retained (not just folded into the message) so a catch site can rebuild the exception with a
  // different tool identity — the dispatch boundary swaps the memory-RESOLVED instance back to
  // the AUTHORED one before failure metadata reaches LLM-facing content.
  val trailblazeToolResult: TrailblazeToolResult.Error,
  cause: Throwable? = null,
) : TrailblazeException(message = trailblazeToolResult.failureMessage(), cause = cause) {
  constructor(message: String, tool: TrailblazeTool) : this(
    tool = tool,
    trailblazeToolResult = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = message,
      command = tool,
    ),
  )
}
