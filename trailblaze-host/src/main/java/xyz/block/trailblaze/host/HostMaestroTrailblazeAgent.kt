package xyz.block.trailblaze.host

import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class HostMaestroTrailblazeAgent(
  val maestroHostRunner: MaestroHostRunner,
  trailblazeLogger: TrailblazeLogger,
) : MaestroTrailblazeAgent(trailblazeLogger) {

  override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult = maestroHostRunner.runMaestroCommands(
    commands = commands,
    traceId = traceId,
  )
}
