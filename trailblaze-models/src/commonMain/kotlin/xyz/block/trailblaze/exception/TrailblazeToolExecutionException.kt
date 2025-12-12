package xyz.block.trailblaze.exception

import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class TrailblazeToolExecutionException(
  val tool: TrailblazeTool,
  trailblazeToolResult: TrailblazeToolResult.Error,
) : TrailblazeException(message = trailblazeToolResult.toString()) {
  constructor(message: String, tool: TrailblazeTool) : this(
    tool = tool,
    trailblazeToolResult = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = message,
      command = tool,
    ),
  )
}
