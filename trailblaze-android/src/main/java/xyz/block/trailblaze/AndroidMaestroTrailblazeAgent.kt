package xyz.block.trailblaze

import maestro.orchestra.Command
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class AndroidMaestroTrailblazeAgent : MaestroTrailblazeAgent() {
  override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult = MaestroUiAutomatorRunner.runCommands(
    commands = commands,
    traceId = traceId,
  )
}
