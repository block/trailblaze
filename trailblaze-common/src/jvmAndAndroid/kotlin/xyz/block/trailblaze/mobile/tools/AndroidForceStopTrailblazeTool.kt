package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.androidPackageNameViolation
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * `am force-stop <appId>`: kills the app's process and drops its tasks. Data and permissions are
 * untouched, so a later `openApp` is a cold start into the same signed-in state.
 *
 * A trailhead building block, not an agent tool: a trailhead that wants a cold start composes
 * `android_forceStop` then `openApp`, so the restart is written down in the trailhead rather than
 * implied by a launch mode.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_forceStop",
  surfaceToLlm = false,
)
@LLMDescription(
  "Force-stops an Android app (`am force-stop`), leaving its data and permissions as they are. " +
    "Follow with `openApp` for a cold start.",
)
data class AndroidForceStopTrailblazeTool(
  @param:LLMDescription("The Android package id of the app to stop (e.g. `com.example.app`).")
  val appId: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_forceStop is only supported on Android devices " +
          "(got platform: ${toolExecutionContext.trailblazeDeviceInfo.platform}). On iOS, use ios_terminate.",
        command = this,
      )
    }
    // The id reaches a device shell, and the host transport joins arguments without quoting.
    androidPackageNameViolation(appId)?.let { violation ->
      return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = violation, command = this)
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
        command = this,
      )
    return try {
      executor.forceStopApp(appId)
      TrailblazeToolResult.Success(message = "Force-stopped '$appId'.")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to force-stop '$appId': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
