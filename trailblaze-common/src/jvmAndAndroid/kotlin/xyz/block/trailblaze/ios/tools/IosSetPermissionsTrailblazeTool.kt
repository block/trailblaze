package xyz.block.trailblaze.ios.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import maestro.orchestra.SetPermissionsCommand
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * Sets an app's iOS simulator permissions in one call, the iOS counterpart of
 * `android_grantPermissions`. In order: optional [grantAll], then [reset], [revoke], and [grant] privacy services, each via
 * `xcrun simctl privacy <udid> <action> <service> <appId>` with simctl's own service names passed
 * through unchanged; then [notifications]. Camera changes use the driver because simctl cannot
 * grant camera access. Camera also needs an explicit [notifications] policy, unless [grantAll]
 * establishes Allow first: Maestro replaces its stored notifications map with each camera command.
 *
 * Only what is listed changes: the Maestro-backed `launchApp` grants every service on every launch
 * (Maestro's `all: allow` default), so a trail can't tell which permissions it depends on. Clearing
 * app data doesn't reset these grants, so a trailhead that must not inherit an earlier trail's
 * grants resets `all` first. `revoke` `all` is no substitute: it leaves a grant already recorded for
 * a named service in place.
 * [grantAll] explicitly preserves Maestro's `all: allow` behavior, including services only
 * applesimutils can grant on `ios-host` and the notifications alert answer. In contrast,
 * `grant: [all]` only grants services supported by simctl.
 *
 * Notifications are different in kind. iOS has no permission to set ahead of time for them (no
 * simctl service, no Info.plist key), so [notifications] has the driver tap Allow or Don't Allow on
 * the app's "Would Like to Send You Notifications" alert whenever a screen read sees it, for the
 * rest of the session: `AxeDeviceManager` on `ios-axe`, Maestro's XCUITest runner on `ios-host`.
 * `unset` stops answering the alert; omitting [notifications] keeps the current answer unchanged.
 *
 * simctl terminates a running app when a grant changes, so call this before `openApp`. Simulator only.
 */
@Serializable
@TrailblazeToolClass(
  name = "ios_setPermissions",
  surfaceToLlm = false,
)
@LLMDescription(
  "Sets an app's iOS simulator permissions: optionally grants all through the driver, then resets, revokes, and grants `simctl privacy` services, " +
    "and has the driver answer the notifications alert whenever it appears. Only what is listed " +
    "changes. The `camera` service requires `IOS_HOST` and an explicit `notifications` policy unless `grantAll` is true. Call BEFORE `openApp` — simctl terminates a running app when a grant changes.",
)
data class IosSetPermissionsTrailblazeTool(
  @param:LLMDescription("The iOS bundle id of the target app (e.g. `com.example.app`).")
  val appId: String,
  @param:LLMDescription(
    "simctl privacy services to reset to undecided, so the app asks again (`xcrun simctl privacy` " +
      "lists them), or `camera` via the driver. `all` drops every grant an earlier trail left. Applied after `grantAll`.",
  )
  val reset: List<String> = emptyList(),
  @param:LLMDescription(
    "simctl privacy services to revoke, so the app sees them denied and shows no dialog, e.g. " +
      "`microphone`, `location`, `photos`, or `camera` via the driver. Applied after `reset`.",
  )
  val revoke: List<String> = emptyList(),
  @param:LLMDescription(
    "simctl privacy services to grant, so their dialogs never appear, e.g. `location`, `photos`, " +
      "`contacts`, or `camera` via the driver. Applied after `revoke`.",
  )
  val grant: List<String> = emptyList(),
  @param:LLMDescription(
    "How the driver answers the app's notifications alert whenever it appears: `allow` or `deny`. " +
      "`unset` stops answering it. Omit to keep the current answer unchanged.",
  )
  val notifications: String? = null,
  @param:LLMDescription(
    "Grant all permissions through the driver using Maestro's `all: allow` behavior, including " +
      "the notifications alert answer and applesimutils services on `ios-host`. Applied before " +
      "`reset`, `revoke`, `grant` and `notifications`, so explicit changes override it. Defaults " +
      "to false. Unlike this option, `grant: [all]` only covers simctl privacy services.",
  )
  val grantAll: Boolean = false,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    if (deviceInfo.platform != TrailblazeDevicePlatform.IOS) {
      return error(
        "ios_setPermissions is only supported on iOS simulators (got platform: ${deviceInfo.platform}). " +
          "On Android, use android_grantPermissions (android.permission.POST_NOTIFICATIONS for " +
          "notifications); clearing app data revokes runtime permissions.",
      )
    }
    // Checked before anything changes, so a bad value can't leave the permissions half set.
    val allowNotifications = when (notifications) {
      null, "unset" -> null
      "allow" -> true
      "deny" -> false
      else -> return error("ios_setPermissions: notifications must be `allow`, `deny` or `unset`, got `$notifications`.")
    }
    val agent = toolExecutionContext.maestroTrailblazeAgent
    val changesCamera = listOf(reset, revoke, grant).any { "camera" in it }
    if (changesCamera && deviceInfo.trailblazeDriverType == TrailblazeDriverType.IOS_AXE) {
      return error("ios_setPermissions: camera permission changes require IOS_HOST; IOS_AXE does not support them.")
    }
    if ((grantAll || notifications != null || changesCamera) && agent == null) {
      return error("ios_setPermissions needs a Maestro-command driver for grantAll, camera or the notifications alert.")
    }
    if (changesCamera && !grantAll && notifications == null) {
      return error("ios_setPermissions: camera changes require an explicit notifications policy unless grantAll is true.")
    }

    if (grantAll && agent != null) {
      val result = agent.runMaestroCommands(
        maestroCommands = listOf(SetPermissionsCommand(appId = appId, permissions = mapOf("all" to "allow"))),
        traceId = toolExecutionContext.traceId,
      )
      if (!result.isSuccess()) return result
    }

    val deviceId = deviceInfo.trailblazeDeviceId.instanceId
    try {
      val failure = withContext(Dispatchers.IO) {
        for ((action, services) in listOf("reset" to reset, "revoke" to revoke, "grant" to grant)) {
          for (service in services) {
            if (service == "camera" && agent != null) {
              val permission = when (action) {
                "reset" -> "unset"
                "revoke" -> "deny"
                else -> "allow"
              }
              val result = agent.runMaestroCommands(
                maestroCommands = listOf(SetPermissionsCommand(appId, mapOf(
                  "camera" to permission,
                  "notifications" to if (grantAll) "allow" else checkNotNull(notifications),
                ))),
                traceId = toolExecutionContext.traceId,
              )
              if (!result.isSuccess()) return@withContext result
            } else {
              IosHostSimctlUtils.setPrivacy(deviceId, appId, action, service)
            }
          }
        }
        null
      }
      if (failure != null) return failure
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to set privacy services for '$appId': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }

    if (notifications != null && agent != null) {
      // ios-axe answers the alert itself. ios-host's only alert tapper is Maestro's XCUITest runner,
      // which takes the answer as a setPermissions command.
      val result = agent.answerNotificationsAlert(appId, allowNotifications, toolExecutionContext.traceId)
        ?: agent.runMaestroCommands(
          maestroCommands = listOf(SetPermissionsCommand(appId = appId, permissions = mapOf("notifications" to notifications))),
          traceId = toolExecutionContext.traceId,
        )
      if (!result.isSuccess()) return result
    }
    return TrailblazeToolResult.Success(message = summary())
  }

  private fun summary(): String {
    val changes = listOfNotNull(
      if (grantAll) "granted all permissions through the driver" else null,
      reset.takeIf { it.isNotEmpty() }?.let { "reset ${it.joinToString(", ")}" },
      revoke.takeIf { it.isNotEmpty() }?.let { "revoked ${it.joinToString(", ")}" },
      grant.takeIf { it.isNotEmpty() }?.let { "granted ${it.joinToString(", ")}" },
      notifications?.let {
        if (it == "unset") "notifications alert will no longer be answered"
        else "notifications alert will be answered: ${if (it == "allow") "Allow" else "Don't Allow"}"
      },
    )
    return if (changes.isEmpty()) "No permissions to set for '$appId'." else "'$appId': ${changes.joinToString("; ")}."
  }

  private fun error(message: String) = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = message, command = this)
}
