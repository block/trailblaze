package xyz.block.trailblaze.revyl.tools

import xyz.block.trailblaze.host.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Interface for tools that execute against a Revyl cloud device via [RevylCliClient].
 *
 * Analogous to PlaywrightExecutableTool, but uses natural language targets
 * resolved by Revyl's AI grounding instead of element IDs or ARIA descriptors.
 * Each tool returns resolved x,y coordinates so consumers like Trailblaze UI
 * can render click overlays.
 *
 * The default [execute] implementation throws an error directing callers to use
 * [RevylToolAgent], which calls [executeWithRevyl] directly.
 */
interface RevylExecutableTool : ExecutableTrailblazeTool {

  /**
   * Executes this tool against the given Revyl CLI client.
   *
   * @param client The CLI client with an active device session.
   * @param context The tool execution context with session, logging, and memory.
   * @return The result of tool execution, including coordinates when applicable.
   */
  suspend fun executeWithRevyl(
    client: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    error("RevylExecutableTool must be executed via RevylToolAgent")
  }
}
