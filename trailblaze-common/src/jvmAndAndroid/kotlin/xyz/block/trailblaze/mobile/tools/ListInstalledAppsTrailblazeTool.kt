package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Typed result payload for [ListInstalledAppsTrailblazeTool]. Declaring the shape as a
 * `@Serializable` class lets kotlinx.serialization handle the `List<String>` → JSON-array
 * translation under the hood, instead of the tool reaching into the JSON DSL element-by-element.
 * Lives at top-level of the file (not nested in the `data object`) because kotlinx.serialization
 * prefers its serializable types not be inner members of another serializable type.
 */
@Serializable
data class ListInstalledAppsResult(val appIds: List<String>)

/**
 * Returns the list of installed app ids on the current device as a JSON object.
 *
 * Delegates to [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.listInstalledApps], which
 * has both a host-JVM actual (adb `pm list packages`) and an on-device Android actual
 * (`PackageManager`), so the same tool works whether the agent is running on a laptop driving an
 * emulator or inside the Android app itself.
 *
 * iOS is intentionally unsupported for now — there is no on-device iOS agent surface yet. Once
 * one exists, iOS should route through an analogous executor rather than a host-only call here.
 */
@Serializable
@TrailblazeToolClass(
  name = "mobile_listInstalledApps",
  isForLlm = false,
)
@LLMDescription("Returns a JSON object with the list of installed app ids on the current mobile device.")
data object ListInstalledAppsTrailblazeTool : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    return try {
      when (deviceInfo.platform) {
        TrailblazeDevicePlatform.ANDROID -> {
          val executor = toolExecutionContext.androidDeviceCommandExecutor
            ?: return TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "AndroidDeviceCommandExecutor is not provided",
            )
          val ids = executor.listInstalledApps().sorted()
          val payload = TrailblazeJsonInstance.encodeToString(ListInstalledAppsResult(appIds = ids))
          TrailblazeToolResult.Success(message = payload)
        }

        TrailblazeDevicePlatform.IOS -> TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "mobile_listInstalledApps is not yet supported on iOS.",
        )

        TrailblazeDevicePlatform.WEB -> TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "mobile_listInstalledApps is not supported for web devices.",
        )

        TrailblazeDevicePlatform.DESKTOP -> TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "mobile_listInstalledApps is not supported for the Compose desktop driver.",
        )
      }
    } catch (e: CancellationException) {
      // Re-throw so structured-concurrency teardown (agent abort, driver disconnect, session
      // shutdown) isn't silently converted into a normal tool error. Precedent:
      // RunCommandTrailblazeTool.execute catches this same trap explicitly.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to list installed apps: ${e.message}",
        command = this@ListInstalledAppsTrailblazeTool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
