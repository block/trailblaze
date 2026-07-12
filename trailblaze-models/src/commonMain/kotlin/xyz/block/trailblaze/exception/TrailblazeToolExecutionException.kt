package xyz.block.trailblaze.exception

import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class TrailblazeToolExecutionException(
  val tool: TrailblazeTool,
  // Retained (not just folded into the message) so a catch site can rebuild the exception with a
  // different tool identity — the dispatch boundary swaps the memory-RESOLVED instance back to
  // the AUTHORED one before failure metadata reaches LLM-facing content.
  val trailblazeToolResult: TrailblazeToolResult.Error,
  cause: Throwable? = null,
) : TrailblazeException(message = trailblazeToolResult.toString(), cause = cause) {
  constructor(message: String, tool: TrailblazeTool) : this(
    tool = tool,
    trailblazeToolResult = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = message,
      command = tool,
    ),
  )
}
