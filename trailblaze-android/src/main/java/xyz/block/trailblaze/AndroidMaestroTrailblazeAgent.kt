package xyz.block.trailblaze

import maestro.orchestra.Command
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Android on-device Maestro agent for executing commands via UiAutomator.
 * Uses stateless logger with explicit session management.
 */
class AndroidMaestroTrailblazeAgent(
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider
) {
  override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult = MaestroUiAutomatorRunner.runCommands(
    commands = commands,
    traceId = traceId,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )
}
