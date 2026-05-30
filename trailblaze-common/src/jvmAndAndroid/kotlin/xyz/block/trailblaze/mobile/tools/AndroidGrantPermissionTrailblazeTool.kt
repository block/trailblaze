package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Grants a standard Android runtime permission to the specified app via `pm grant`.
 *
 * Wraps [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.grantRuntimePermission]
 * as a TS-callable framework tool. Use this for `protectionLevel="dangerous"` permissions
 * like `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, `ACCESS_FINE_LOCATION`, `CAMERA`, etc. —
 * the kind that normally prompts the user with a runtime dialog on first request. Granting
 * before launch suppresses the dialog entirely, so trails don't have to model the OS
 * overlay.
 *
 * **Distinction from [AndroidGrantAppOpsPermissionTrailblazeTool] (`android_grantAppOpsPermission`).**
 * Runtime permissions (this tool) gate user-facing dangerous APIs and are granted via
 * `pm grant`. AppOps cover a separate class of privileged operations (broad file access,
 * system alert windows, install-unknown-apps) controlled by `appops set` instead — see
 * the sibling tool's kdoc for that surface. The two mechanisms aren't interchangeable;
 * granting a `dangerous` permission with `appops` is a no-op and vice versa.
 *
 * **Failure tolerance.** The underlying `pm grant` writes a stderr line and exits non-zero
 * when the target package doesn't declare the permission in its manifest, when the
 * permission isn't a changeable runtime permission, or when the package isn't installed.
 * The executor tolerates that — logs the stderr but does not throw — so callers can grant
 * a conservative superset across multiple app variants without each variant having to
 * declare every entry. This tool propagates that contract: a `dangerous` permission that
 * the target doesn't declare is **not** a tool failure. Only an underlying I/O / executor-
 * level exception surfaces as an error.
 *
 * **Requires `adb shell` privilege** (UID 2000) — works through the host adb path or the
 * on-device instrumentation's UiAutomation. Does **not** require root.
 *
 * Android-only — the iOS permission model is interaction-driven (TCC prompts at first
 * access) rather than pre-grantable, so there's no cross-platform analog and the tool
 * surfaces a clear error on non-Android platforms.
 */
@Serializable
@TrailblazeToolClass(
  name = "android_grantPermission",
  surfaceToLlm = false,
)
@LLMDescription(
  "Grants a standard Android runtime (dangerous) permission to the specified app via " +
    "`pm grant`. Use BEFORE launching the app to suppress the OS permission dialog. For " +
    "AppOps-class permissions (MANAGE_EXTERNAL_STORAGE, SYSTEM_ALERT_WINDOW, etc.) use " +
    "`android_grantAppOpsPermission` instead.",
)
data class AndroidGrantPermissionTrailblazeTool(
  @param:LLMDescription("The Android package id of the target app (e.g. `com.example.app`).")
  val appId: String,
  @param:LLMDescription(
    "The fully-qualified Android permission to grant (e.g. `android.permission.CAMERA`, " +
      "`android.permission.BLUETOOTH_CONNECT`). Must be a `protectionLevel=\"dangerous\"` " +
      "runtime permission declared in the target's manifest — otherwise `pm grant` no-ops " +
      "and the tool still returns success (matches the executor's permissive-superset contract).",
  )
  val permission: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_grantPermission is only supported on Android devices " +
          "(got platform: ${toolExecutionContext.trailblazeDeviceInfo.platform}).",
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
        command = this,
      )
    return try {
      executor.grantRuntimePermission(appId = appId, permission = permission)
      TrailblazeToolResult.Success(
        message = "Granted runtime permission '$permission' to '$appId' (via pm grant).",
      )
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown isn't swallowed. Precedent:
      // `AdbShellTrailblazeTool.execute`, `ListInstalledAppsTrailblazeTool.execute`.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to grant runtime permission '$permission' to '$appId': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
