package xyz.block.trailblaze.revyl.tools

import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Base class for tools that execute against a Revyl cloud device via [RevylCliClient].
 *
 * Analogous to PlaywrightExecutableTool, but uses natural language targets
 * resolved by Revyl's AI grounding instead of element IDs or ARIA descriptors.
 * Each tool returns resolved x,y coordinates so consumers like Trailblaze UI
 * can render click overlays.
 *
 * Subclasses implement [executeWithRevyl]; the default [execute] throws to
 * direct callers through [RevylToolAgent] which calls [executeWithRevyl] directly.
 */
abstract class RevylExecutableTool : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  /**
   * Executes this tool against the given Revyl CLI client.
   *
   * @param revylClient The CLI client with an active device session.
   * @param context The tool execution context with session, logging, and memory.
   * @return The result of tool execution, including coordinates when applicable.
   */
  abstract suspend fun executeWithRevyl(
    revylClient: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    error("RevylExecutableTool must be executed via RevylToolAgent")
  }
}
