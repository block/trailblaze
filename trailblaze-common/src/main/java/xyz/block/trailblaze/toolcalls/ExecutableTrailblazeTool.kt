package xyz.block.trailblaze.toolcalls

/**
 * A marker interface for all Trailblaze tools that can be executed directly.
 */
interface ExecutableTrailblazeTool : TrailblazeTool {
  fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult
}
