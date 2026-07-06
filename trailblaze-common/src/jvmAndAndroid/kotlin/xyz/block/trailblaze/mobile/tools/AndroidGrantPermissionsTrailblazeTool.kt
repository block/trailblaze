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
 * Grants one or more standard Android runtime permissions to an app via `pm grant`, in a single
 * tool dispatch.
 *
 * Each permission is granted through [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.grantRuntimePermission],
 * in list order, honoring that executor's permissive-superset contract: a permission the target
 * doesn't declare writes a stderr line and is a no-op, not a failure — so a conservative superset
 * is safe. A scripted-tool caller pays one JS↔Kotlin dispatch for the whole set rather than one per
 * permission (a pre-launch grant of a fixed superset — e.g. Square's launch/sign-in flow — is a
 * single `ctx.tools.android_grantPermissions(...)`). A single grant is just a one-element list.
 *
 * Failure semantics: the executor swallows-and-logs the undeclared/unchangeable-permission case, so
 * those never abort the batch; only a genuine executor-level I/O exception surfaces, and it stops
 * the batch and surfaces as an error.
 *
 * Use this (`pm grant` runtime permissions) for `protectionLevel="dangerous"` permissions like
 * `CAMERA`, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`. For AppOps-class operations
 * (`MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, …) use
 * [AndroidGrantAppOpsPermissionTrailblazeTool] (`android_grantAppOpsPermission`) instead — the two
 * mechanisms aren't interchangeable. Requires `adb shell` privilege (non-root), via the host adb
 * path or on-device UiAutomation. Android-only (iOS permissions are interaction-driven, not
 * pre-grantable).
 */
@Serializable
@TrailblazeToolClass(
  name = "android_grantPermissions",
  surfaceToLlm = false,
)
@LLMDescription(
  "Grants one or more standard Android runtime (dangerous) permissions to an app via `pm grant`, " +
    "in a single call. Use BEFORE launching the app to suppress OS permission dialogs. For " +
    "AppOps-class permissions use `android_grantAppOpsPermission` instead.",
)
data class AndroidGrantPermissionsTrailblazeTool(
  @param:LLMDescription("The Android package id of the target app (e.g. `com.example.app`).")
  val appId: String,
  @param:LLMDescription(
    "The fully-qualified Android runtime permissions to grant (e.g. " +
      "`android.permission.CAMERA`, `android.permission.BLUETOOTH_CONNECT`). Each must be a " +
      "`protectionLevel=\"dangerous\"` permission declared in the target's manifest — an entry the " +
      "target doesn't declare is a tolerated no-op (matches the permissive-superset contract). An " +
      "empty list is a no-op.",
  )
  val permissions: List<String>,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (toolExecutionContext.trailblazeDeviceInfo.platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_grantPermissions is only supported on Android devices " +
          "(got platform: ${toolExecutionContext.trailblazeDeviceInfo.platform}).",
      )
    }
    val executor = toolExecutionContext.androidDeviceCommandExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "AndroidDeviceCommandExecutor is not provided",
        command = this,
      )
    return try {
      grantEach(permissions) { permission ->
        executor.grantRuntimePermission(appId = appId, permission = permission)
      }
      val message = if (permissions.isEmpty()) {
        "No runtime permissions to grant for '$appId' (empty list)."
      } else {
        "Granted ${permissions.size} runtime permission(s) to '$appId' (via pm grant): " +
          permissions.joinToString(", ")
      }
      TrailblazeToolResult.Success(message = message)
    } catch (e: CancellationException) {
      // Propagate cancellation so structured-concurrency teardown isn't swallowed. Matches the
      // singular AndroidGrantPermissionTrailblazeTool.execute.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to grant runtime permissions to '$appId': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  internal companion object {
    /**
     * Applies [grant] to each permission in order. Pure over the injected side effect, so the
     * fan-out contract (every permission granted, in list order) is unit-testable without a
     * device — an exception from [grant] propagates and stops the batch, matching the per-call
     * loop this tool replaces.
     */
    internal fun grantEach(permissions: List<String>, grant: (String) -> Unit) {
      permissions.forEach(grant)
    }
  }
}
