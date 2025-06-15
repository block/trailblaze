package xyz.block.trailblaze

import maestro.orchestra.Command
import xyz.block.trailblaze.android.AndroidMaestroYaml
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class AndroidMaestroTrailblazeAgent : MaestroTrailblazeAgent() {
  override fun executeMaestroCommands(commands: List<Command>, llmResponseId: String?): TrailblazeToolResult = MaestroUiAutomatorRunner.runCommands(
    commands = commands,
    llmResponseId = llmResponseId,
  )

  override fun runMaestroYaml(
    yaml: String,
    llmResponseId: String?,
  ): TrailblazeToolResult {
    val commands = AndroidMaestroYaml.parseYaml(yaml)
    return executeMaestroCommands(
      commands = commands,
      llmResponseId = llmResponseId,
    )
  }
}
