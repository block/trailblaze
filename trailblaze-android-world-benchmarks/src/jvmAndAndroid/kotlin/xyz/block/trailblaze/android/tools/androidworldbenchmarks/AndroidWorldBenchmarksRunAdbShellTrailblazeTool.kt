package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_runAdbShell",
  isForLlm = false,
)
@LLMDescription("Use this to run adb shell commands on the device.")
data class AndroidWorldBenchmarksRunAdbShellTrailblazeTool(
  @param:LLMDescription("List of commands to run sequentially on the device.")
  val adbShellCommands: List<String>,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      adbShellCommands.forEach { adbShellCommand ->
        try {
          executor.executeShellCommand(adbShellCommand)
        } catch (e: Exception) {
          return@withAndroidDeviceCommandExecutor TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "Failed to execute command: $adbShellCommand",
            command = this@AndroidWorldBenchmarksRunAdbShellTrailblazeTool,
            stackTrace = e.stackTraceToString()
          )
        }
      }
      val previewCommands = adbShellCommands
        .joinToString(separator = " | ")
      TrailblazeToolResult.Success(
        message = "Executed ${adbShellCommands.size} adb shell command(s). " +
          "Command preview: $previewCommands",
      )
    }
  }
}
