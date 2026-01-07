package xyz.block.trailblaze

import maestro.orchestra.Command
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class AndroidMaestroTrailblazeAgent(
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
) : MaestroTrailblazeAgent(trailblazeLogger, trailblazeDeviceInfoProvider) {
  override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult = MaestroUiAutomatorRunner.runCommands(
    commands = commands,
    traceId = traceId,
    trailblazeLogger = trailblazeLogger,
  )
}
