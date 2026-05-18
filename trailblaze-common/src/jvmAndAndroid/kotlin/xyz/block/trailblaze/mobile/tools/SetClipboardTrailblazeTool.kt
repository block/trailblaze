package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * Sets the device clipboard content. Cross-platform mobile primitive.
 *
 * - **Android**: delegates to [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.setClipboard],
 *   which has both an on-device actual (`ClipboardManager`) and a host-JVM actual
 *   (`am broadcast -a clipper.set`). Reaches the device through the same multi-platform
 *   executor surface every other Android tool uses, so the tool composes correctly on
 *   both host-dispatched and on-device-dispatched scripted-tool paths.
 * - **iOS**: uses `xcrun simctl pasteboard set <udid>` via [IosHostSimctlUtils.setPasteboard]
 *   (host-only — iOS only runs from a Mac host).
 */
@Serializable
@TrailblazeToolClass(
  name = "mobile_setClipboard",
)
@LLMDescription("Sets the device clipboard to the specified text.")
data class SetClipboardTrailblazeTool(
  @param:LLMDescription("The text to place on the clipboard.")
  val text: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    return try {
      when (deviceInfo.platform) {
        TrailblazeDevicePlatform.ANDROID -> {
          val executor = toolExecutionContext.androidDeviceCommandExecutor
            ?: return TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "AndroidDeviceCommandExecutor is not provided",
            )
          executor.setClipboard(text)
        }

        TrailblazeDevicePlatform.IOS -> {
          IosHostSimctlUtils.setPasteboard(
            deviceId = deviceInfo.trailblazeDeviceId.instanceId,
            text = text,
          )
        }

        TrailblazeDevicePlatform.WEB ->
          return TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "mobile_setClipboard is not supported for web devices.",
          )

        TrailblazeDevicePlatform.DESKTOP ->
          return TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "mobile_setClipboard is not supported for the Compose desktop driver.",
          )
      }
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
