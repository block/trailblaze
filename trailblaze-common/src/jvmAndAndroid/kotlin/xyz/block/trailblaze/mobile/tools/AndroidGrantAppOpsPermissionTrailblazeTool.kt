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
 * Grants an Android AppOps (Application Operations) permission to the specified app via
 * `appops set <appId> <op> allow`.
 *
 * Wraps [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.grantAppOpsPermission] as
 * a TS-callable framework tool. AppOps is the platform mechanism that gates a separate
 * class of privileged operations — broader / more sensitive than typical runtime permissions
 * — that pre-dates the modern runtime-permission model and is still the only way to grant
 * a handful of capabilities programmatically.
 *
 * **What AppOps covers (the operations a test might need to grant ahead of time):**
 *  - `MANAGE_EXTERNAL_STORAGE` — broad file access on API 30+ (replaces the legacy
 *    `READ/WRITE_EXTERNAL_STORAGE` runtime perms for unscoped storage access).
 *  - `REQUEST_INSTALL_PACKAGES` — install APKs from unknown sources.
 *  - `SYSTEM_ALERT_WINDOW` — draw overlays on top of other apps.
 *  - `WRITE_SETTINGS` — modify system settings.
 *  - `ACCESS_NOTIFICATIONS` — read notifications.
 *  - `PICTURE_IN_PICTURE` — picture-in-picture mode.
 *
 * **Distinction from [AndroidGrantPermissionsTrailblazeTool] (`android_grantPermissions`).**
 * `pm grant` (the other tool) grants `protectionLevel="dangerous"` runtime permissions
 * (CAMERA, BLUETOOTH_CONNECT, POST_NOTIFICATIONS, etc.) — the kind that show OS dialogs
 * to the user. `appops set` (this tool) grants the privileged-operation class above.
 * The two mechanisms are not interchangeable: granting `MANAGE_EXTERNAL_STORAGE` via
 * `pm grant` is a no-op, and granting `CAMERA` via `appops set` is a no-op. Pick the right
 * verb for the permission you're granting.
 *
 * **Mode is always `allow`.** The executor's contract grants — there's no "deny" or
 * "default" mode exposed here because the only test-relevant operation is "make this
 * permission silently active before launch." If a future use case needs the inverse,
 * extend with a `mode: AppOpsMode` arg; today, every caller wants `allow`.
 *
 * **Requires `adb shell` privilege** (UID 2000). Does **not** require root.
 *
 * Android-only — iOS has no analog (TCC is interaction-driven).
 */
@Serializable
@TrailblazeToolClass(
  name = "android_grantAppOpsPermission",
  surfaceToLlm = false,
)
@LLMDescription(
  "Grants an Android AppOps permission to the specified app via `appops set <appId> <op> " +
    "allow`. Use for the privileged-operation class — MANAGE_EXTERNAL_STORAGE, " +
    "SYSTEM_ALERT_WINDOW, REQUEST_INSTALL_PACKAGES, WRITE_SETTINGS, ACCESS_NOTIFICATIONS, " +
    "PICTURE_IN_PICTURE. For standard `dangerous` runtime permissions (CAMERA, " +
    "BLUETOOTH_CONNECT, etc.) use `android_grantPermissions` instead — the two mechanisms " +
    "aren't interchangeable.",
)
data class AndroidGrantAppOpsPermissionTrailblazeTool(
  @param:LLMDescription("The Android package id of the target app (e.g. `com.example.app`).")
  val appId: String,
  @param:LLMDescription(
    "The AppOps operation name to grant (e.g. `MANAGE_EXTERNAL_STORAGE`, " +
      "`SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`). The op is the bare AppOps name, " +
      "NOT the `android.permission.*` form — `appops set` rejects the prefixed form. See " +
      "`android.app.AppOpsManager` for the full op enumeration.",
  )
  val permission: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_grantAppOpsPermission is only supported on Android devices " +
          "(got platform: ${toolExecutionContext.trailblazeDeviceInfo.platform}).",
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
        command = this,
      )
    return try {
      executor.grantAppOpsPermission(appId = appId, permission = permission)
      TrailblazeToolResult.Success(
        message = "Granted AppOps permission '$permission' to '$appId' (mode=allow).",
      )
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown isn't swallowed. Precedent:
      // `AdbShellTrailblazeTool.execute`, `ListInstalledAppsTrailblazeTool.execute`.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to grant AppOps permission '$permission' to '$appId': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
