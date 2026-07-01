package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * Typed result payload for [ListInstalledAppsTrailblazeTool]. Declaring the shape as a
 * `@Serializable` class lets kotlinx.serialization handle the `List<String>` → JSON-array
 * translation under the hood, instead of the tool reaching into the JSON DSL element-by-element.
 * Lives at top-level of the file (not nested in the `data object`) because kotlinx.serialization
 * prefers its serializable types not be inner members of another serializable type.
 *
 * Returned via [TrailblazeToolResult.Success.structuredContent] — the TS SDK's
 * `client.tools.mobile_listInstalledApps(...)` proxy unwraps it as the typed `result` per
 * `TrailblazeToolMap.mobile_listInstalledApps.result` (declared in `built-in-tools.ts`), no
 * `JSON.parse` required. The tool leaves [TrailblazeToolResult.Success.message] unset —
 * `AgentMessages.toContentString` (the LLM/CLI/direct-YAML rendering path) falls back to
 * rendering `structuredContent` as compact JSON when `message` is absent, so LLM/CLI callers see
 * the same app-id data as TS callers without this tool duplicating it into a second field by hand.
 */
@Serializable
data class ListInstalledAppsResult(val appIds: List<String>)

/** Builds the [TrailblazeToolResult.Success] for a resolved app-id list. */
private fun List<String>.toSuccessResult(): TrailblazeToolResult.Success = TrailblazeToolResult.Success(
  structuredContent = TrailblazeJsonInstance.encodeToJsonElement(
    ListInstalledAppsResult.serializer(),
    ListInstalledAppsResult(appIds = this),
  ),
)

/**
 * Returns the list of installed app ids on the current device as a JSON object
 * (`{"appIds":[...]}`, sorted) — the lean, common-case companion to the richer
 * [ListInstalledAppsDetailedTrailblazeTool] (`mobile_listInstalledAppsDetailed`). Reach for this when
 * a flat id list is enough (e.g. discovering which apps are installed before `launchApp`, or
 * asserting an app is / isn't present); reach for the detailed tool when you need each app's display
 * name, system-vs-user classification, version, etc.
 *
 * Both are thin projections over the same framework primitive
 * ([xyz.block.trailblaze.device.AndroidDeviceCommandExecutor] +
 * [IosHostSimctlUtils]); this one just keeps the app ids.
 *
 * - **Android**: delegates to [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.listInstalledApps],
 *   which has both a host-JVM actual (adb `pm list packages`) and an on-device Android actual
 *   (`PackageManager`), so the same tool works whether the agent is running on a laptop driving an
 *   emulator or inside the Android app itself.
 * - **iOS**: uses `xcrun simctl listapps <udid>` via [IosHostSimctlUtils.listInstalledAppIds]
 *   (host-only — iOS only runs from a Mac host; off-Mac it returns an empty inventory).
 */
@Serializable
@TrailblazeToolClass(
  name = "mobile_listInstalledApps",
)
@LLMDescription("Returns a JSON object ({\"appIds\":[...]}) listing the app ids/bundle ids installed on the current mobile device. Works on Android and iOS.")
data object ListInstalledAppsTrailblazeTool : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

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
          ids.toSuccessResult()
        }

        TrailblazeDevicePlatform.IOS -> {
          val ids = IosHostSimctlUtils.listInstalledAppIds(
            deviceId = deviceInfo.trailblazeDeviceId.instanceId,
          ).sorted()
          ids.toSuccessResult()
        }

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
