package xyz.block.trailblaze.host

import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File

interface MaestroHostRunner {

  val screenStateProvider: () -> ScreenState

  fun runMaestroYaml(yaml: String): TrailblazeToolResult

  fun runFlowFile(flowFile: File): TrailblazeToolResult

  fun runMaestroCommand(vararg commands: Command): TrailblazeToolResult

  fun runMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult

  /**
   * Detach any experimental stream-sourced screenshot feed (iOS baguette stream — see
   * [StreamScreenshotMode]) so it doesn't outlive the session. No-op unless
   * `TRAILBLAZE_IOS_STREAM_SCREENSHOT` engaged.
   */
  fun closeStreamScreenshotSource() {}
}
