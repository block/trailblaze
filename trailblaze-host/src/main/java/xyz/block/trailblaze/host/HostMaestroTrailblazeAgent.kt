package xyz.block.trailblaze.host

import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class HostMaestroTrailblazeAgent(
  private val maestroHostRunner: MaestroHostRunner,
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
) : MaestroTrailblazeAgent(trailblazeLogger, trailblazeDeviceInfoProvider) {

  val connectedDevice: TrailblazeConnectedDevice by lazy {
    (maestroHostRunner as MaestroHostRunnerImpl).connectedDevice
  }

  override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult =
    maestroHostRunner.runMaestroCommands(
      commands = commands,
      traceId = traceId,
    )
}
