package xyz.block.trailblaze.host

import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class HostMaestroTrailblazeAgent(
  val maestroHostRunner: MaestroHostRunner,
) : MaestroTrailblazeAgent() {

  override suspend fun executeMaestroCommands(commands: List<Command>, llmResponseId: String?): TrailblazeToolResult = maestroHostRunner.runMaestroCommands(
    commands = commands,
    llmResponseId = llmResponseId,
  )
}
