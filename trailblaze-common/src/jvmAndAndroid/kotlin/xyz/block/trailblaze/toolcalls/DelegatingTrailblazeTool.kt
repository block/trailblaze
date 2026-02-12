package xyz.block.trailblaze.toolcalls

/**
 * Allows a [TrailblazeTool] to be converted into a list of [ExecutableTrailblazeTool]s.
 */
interface DelegatingTrailblazeTool : TrailblazeTool {
  fun toExecutableTrailblazeTools(executionContext: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool>
}
