package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.BroadcastIntent
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Sends a broadcast intent to the connected Android device.
 *
 * Building block for Android-specific automation tools whose only effect is to emit an
 * `am broadcast` (or equivalent on-device call). Higher-level tools can delegate to this
 * one in YAML rather than reimplementing broadcast plumbing in Kotlin.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_sendBroadcast",
  isForLlm = false,
)
@LLMDescription("Sends a broadcast intent to the connected Android device.")
data class AndroidSendBroadcastTrailblazeTool(
  val action: String,
  val componentPackage: String,
  val componentClass: String,
  @Serializable(with = BroadcastExtrasMapSerializer::class)
  val extras: Map<String, BroadcastExtra> = emptyMap(),
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_sendBroadcast is only supported on Android devices.",
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
      )
    return try {
      executor.sendBroadcast(
        BroadcastIntent(
          action = action,
          componentPackage = componentPackage,
          componentClass = componentClass,
          extras = extras.mapValues { it.value.toTypedValue() },
        ),
      )
      TrailblazeToolResult.Success(
        message = "Sent broadcast action='$action' to $componentPackage/$componentClass",
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to send broadcast action='$action': ${e.message}",
        command = this@AndroidSendBroadcastTrailblazeTool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
