package xyz.block.trailblaze

import maestro.orchestra.Command
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class AndroidMaestroTrailblazeAgent(
  /** Use this to handle custom Trailblaze tools that are not directly mapped to Maestro commands. */
  override val customTrailblazeToolHandler: (TrailblazeToolExecutionContext) -> TrailblazeToolResult? = { null },
) : MaestroTrailblazeAgent() {
  override fun executeMaestroCommands(commands: List<Command>, llmResponseId: String?): TrailblazeToolResult = MaestroUiAutomatorRunner.runCommands(
    commands = commands,
    llmResponseId = llmResponseId,
  )
}
