package xyz.block.trailblaze.ios.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * `xcrun simctl keychain <udid> reset`: empties an iOS simulator's keychain, for every app on it.
 *
 * A trailhead building block. `mobile_clearAppData` deletes an app's data container but not its
 * keychain items, so an app that keeps its session token in the keychain relaunches signed in;
 * run this alongside it for a signed-out start.
 */
@Serializable
@TrailblazeToolClass(
  name = "ios_resetKeychain",
  surfaceToLlm = false,
)
@LLMDescription(
  "Empties the iOS simulator's keychain for every app (`simctl keychain reset`). Pair with " +
    "`mobile_clearAppData` for a signed-out start.",
)
data object IosResetKeychainTrailblazeTool : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    if (deviceInfo.platform != TrailblazeDevicePlatform.IOS) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "ios_resetKeychain is only supported on iOS simulators (got platform: " +
          "${deviceInfo.platform}).",
        command = this,
      )
    }
    return try {
      withContext(Dispatchers.IO) {
        IosHostSimctlUtils.resetKeychain(deviceId = deviceInfo.trailblazeDeviceId.instanceId)
      }
      TrailblazeToolResult.Success(message = "Reset the simulator keychain.")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to reset the simulator keychain: ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
