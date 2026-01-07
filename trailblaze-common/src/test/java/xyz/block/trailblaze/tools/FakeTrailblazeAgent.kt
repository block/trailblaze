package xyz.block.trailblaze.tools

import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

class FakeTrailblazeAgent(
  trailblazeLogger: TrailblazeLogger = TrailblazeLogger.create(),
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
    TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "fake-instance-id",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    )
  },
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
) {
  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = TrailblazeToolResult.Success

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
  ): TrailblazeAgent.RunTrailblazeToolsResult {
    error("FakeTrailblazeAgent does not have an implementation for runTrailblazeTools()")
  }
}
