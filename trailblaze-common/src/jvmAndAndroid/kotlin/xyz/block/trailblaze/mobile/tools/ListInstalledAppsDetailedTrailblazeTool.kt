package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.InstalledApp
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * Typed result payload for [ListInstalledAppsDetailedTrailblazeTool]. A list of the full
 * [InstalledApp] record. Lives at top-level (not nested in the `data class`) because
 * kotlinx.serialization prefers its serializable types not be inner members of another serializable
 * type.
 *
 * Returned via [TrailblazeToolResult.Success.structuredContent] — the TS SDK's
 * `client.tools.mobile_listInstalledAppsDetailed(...)` proxy unwraps it as the typed `result` per
 * `TrailblazeToolMap.mobile_listInstalledAppsDetailed.result` (declared in `built-in-tools.ts`), no
 * `JSON.parse` required. The tool leaves [TrailblazeToolResult.Success.message] unset —
 * `AgentMessages.toContentString` falls back to rendering `structuredContent` as compact JSON when
 * `message` is absent, so any non-TS caller (this tool is `surfaceToLlm = false` today, but the
 * fallback isn't specific to that) sees the same data without a second hand-maintained field.
 */
@Serializable
data class ListInstalledAppsDetailedResult(val apps: List<InstalledApp>)

/**
 * Applies the [ListInstalledAppsDetailedTrailblazeTool.includeSystemApps] filter and sorts by id —
 * the tool's one piece of result-shaping behavior. Extracted to file scope so it's unit-testable
 * without a device or an execution context. When `includeSystemApps` is `false`, OS/system apps
 * (`isSystemApp == true`) are dropped; user/third-party apps are kept. Result is always sorted by
 * [InstalledApp.appId] for a deterministic, diffable inventory.
 */
internal fun filterInstalledApps(apps: List<InstalledApp>, includeSystemApps: Boolean): List<InstalledApp> =
  (if (includeSystemApps) apps else apps.filterNot { it.isSystemApp }).sortedBy { it.appId }

/**
 * The framework's **deep primitive** for installed-app inventory: returns the full [InstalledApp]
 * record per app (`{"apps":[{"appId":…,"isSystemApp":…,"label":…,"version":…,"buildNumber":…,"installPath":…}, …]}`,
 * sorted by id). The lean [ListInstalledAppsTrailblazeTool] (`mobile_listInstalledApps`) is just a
 * projection of this down to app ids; other tools delegate here the same way.
 *
 * Deliberately **not surfaced to the LLM** ([TrailblazeToolClass.surfaceToLlm] `= false`): the
 * agent's catalog keeps the lean `mobile_listInstalledApps`, while this richer primitive stays
 * reachable to scripted / composing tools via `client.callTool("mobile_listInstalledAppsDetailed", …)`.
 * Flip `surfaceToLlm` if an agent should be able to read app metadata directly.
 *
 * [includeSystemApps] (default `true`) lists everything; `false` drops OS / system apps, returning
 * only user-/third-party-installed apps.
 *
 * Per-platform coverage (intrinsic to each data source):
 * - **iOS**: `xcrun simctl listapps` reports every field in one call (host-only — off-Mac the
 *   inventory is a benign empty list).
 * - **Android on-device** (`PackageManager`): every field populated.
 * - **Android host/adb**: a single `dumpsys package packages` call fills `isSystemApp`, `version`,
 *   `buildNumber`, and `installPath`. Only `label` is `null` here — resolving the human display name
 *   needs `aapt dump badging` on a pulled APK (or the on-device driver). Drive the on-device Android
 *   path when you need the label.
 *
 * To filter by anything other than system/user (name, version, …), filter the returned `apps` list
 * — the record is the deep capability; trimming happens on top.
 */
@Serializable
@TrailblazeToolClass(
  name = "mobile_listInstalledAppsDetailed",
  surfaceToLlm = false,
)
@LLMDescription(
  "Returns a JSON object ({\"apps\":[{\"appId\":…,\"isSystemApp\":…,\"label\":…,\"version\":…,\"buildNumber\":…,\"installPath\":…}, …]}) " +
    "listing the apps installed on the current mobile device with full per-app metadata, sorted by id. The " +
    "deep counterpart to mobile_listInstalledApps (which returns ids only). Pass includeSystemApps=false to " +
    "return only user-installed apps. Note: on the Android host/adb path only label is empty (null); " +
    "isSystemApp/version/buildNumber/installPath are populated there too, and the label is populated on the " +
    "Android on-device driver and on iOS.",
)
data class ListInstalledAppsDetailedTrailblazeTool(
  @param:LLMDescription(
    "When true (default), includes OS/system apps as well as user-installed apps. Set false to " +
      "return only user/third-party apps and skip the ~30 system apps on a fresh device.",
  )
  val includeSystemApps: Boolean = true,
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    return try {
      val apps: List<InstalledApp> = when (deviceInfo.platform) {
        TrailblazeDevicePlatform.ANDROID -> {
          val executor = toolExecutionContext.androidDeviceCommandExecutor
            ?: return TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "AndroidDeviceCommandExecutor is not provided",
            )
          executor.listInstalledAppsDetailed(includeLabelsAndVersions = true)
        }

        TrailblazeDevicePlatform.IOS -> IosHostSimctlUtils.listInstalledAppsDetailed(
          deviceId = deviceInfo.trailblazeDeviceId.instanceId,
        )

        TrailblazeDevicePlatform.WEB -> return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "mobile_listInstalledAppsDetailed is not supported for web devices.",
        )

        TrailblazeDevicePlatform.DESKTOP -> return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "mobile_listInstalledAppsDetailed is not supported for the Compose desktop driver.",
        )
      }

      val filtered = filterInstalledApps(apps, includeSystemApps)
      TrailblazeToolResult.Success(
        structuredContent = TrailblazeJsonInstance.encodeToJsonElement(
          ListInstalledAppsDetailedResult.serializer(),
          ListInstalledAppsDetailedResult(apps = filtered),
        ),
      )
    } catch (e: CancellationException) {
      // Re-throw so structured-concurrency teardown isn't converted into a normal tool error —
      // same trap ListInstalledAppsTrailblazeTool.execute catches explicitly.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to list installed apps with detail: ${e.message}",
        command = this@ListInstalledAppsDetailedTrailblazeTool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
