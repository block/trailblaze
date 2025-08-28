package xyz.block.trailblaze.host

import maestro.orchestra.Command
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File

interface MaestroHostRunner {

  val screenStateProvider: () -> ScreenState

  fun runMaestroYaml(yaml: String): TrailblazeToolResult

  fun runFlowFile(flowFile: File): TrailblazeToolResult

  fun runMaestroCommand(vararg commands: Command): TrailblazeToolResult

  fun runMaestroCommands(commands: List<Command>, llmResponseId: String?): TrailblazeToolResult
}
