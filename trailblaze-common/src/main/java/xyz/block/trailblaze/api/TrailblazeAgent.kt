package xyz.block.trailblaze.api

import maestro.orchestra.Command
import xyz.block.trailblaze.agent.AgentTask
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

interface TrailblazeAgent {
  var currentTask: AgentTask

  fun setUpTask(instruction: String): AgentTask

  fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    llmResponseId: String? = null,
    screenState: ScreenState? = null,
  ): Pair<List<TrailblazeTool>, TrailblazeToolResult>

  fun runMaestroCommands(
    maestroCommands: List<Command>,
    llmResponseId: String? = null,
  ): TrailblazeToolResult
}
