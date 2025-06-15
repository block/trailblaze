package xyz.block.trailblaze.tools

import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class FakeTrailblazeAgent : MaestroTrailblazeAgent() {
  override fun runMaestroYaml(
    yaml: String,
    llmResponseId: String?,
  ): TrailblazeToolResult {
    TODO("Not yet implemented")
  }

  override fun executeMaestroCommands(
    commands: List<Command>,
    llmResponseId: String?,
  ): TrailblazeToolResult = TrailblazeToolResult.Success

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    llmResponseId: String?,
    screenState: ScreenState?,
  ): Pair<List<TrailblazeTool>, TrailblazeToolResult> {
    TODO("Not yet implemented")
  }
}
