package xyz.block.trailblaze.android.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Sets the device clipboard content.
 *
 * Uses [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.setClipboard] which delegates to
 * ClipboardManager on Android, or `adb shell` commands on JVM.
 */
@Serializable
@TrailblazeToolClass(
  name = "setClipboard",
)
@LLMDescription("Sets the device clipboard to the specified text.")
data class SetClipboardTrailblazeTool(
  @param:LLMDescription("The text to place on the clipboard.")
  val text: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val devicePlatform = toolExecutionContext.trailblazeDeviceInfo.platform
    if (devicePlatform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown("This tool is only supported on Android devices, not on $devicePlatform.")
    }
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      try {
        executor.setClipboard(text)
        val preview = text.replace("\n", "\\n")
        TrailblazeToolResult.Success(
          message = "Set clipboard text (${text.length} characters). Preview: '$preview'",
        )
      } catch (e: Exception) {
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed to set clipboard: ${e.message}",
          command = this@SetClipboardTrailblazeTool,
          stackTrace = e.stackTraceToString(),
        )
      }
    }
  }
}
