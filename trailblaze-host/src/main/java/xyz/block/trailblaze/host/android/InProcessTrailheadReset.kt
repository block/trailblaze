package xyz.block.trailblaze.host.android

import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.device.AndroidShellBounds
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailYamlItem

/**
 * The part of a trailhead the in-process ANDROID_TEST driver cannot do on its own: clearing the
 * app's data. The instrumentation runs inside the app, so `pm clear` from
 * there kills the test. The host does it before the instrumentation starts, and the on-device
 * adapter then launches onto the cleared data.
 */
object InProcessTrailheadReset {

  /**
   * The app ids to clear before starting the instrumentation; empty when nothing needs it.
   *
   * A trailhead that opens with a `launchApp` REINSTALL of the target clears that app. When
   * [clearOnEveryTrailhead] is set (the target's `android_test.clear_data_before_trailhead`), any
   * trailhead clears every installed target app id, because that target's scripted trailhead
   * clears data on the other drivers and cannot do it in-process.
   *
   * A templated `appId` (`{{appId}}`) is resolved as the device will resolve it: the trail's
   * `config.memory`, then [memory] (the run's memory snapshot and `--memory` seeds, in that order).
   * One still unresolved clears the target only when exactly one of its app ids is installed, the
   * only app an in-process launch can reach. With several installed it clears none rather than guess:
   * the device then sees an uncleared start and refuses the REINSTALL instead of wiping the wrong app.
   */
  fun appIdsToClear(
    trailItems: List<TrailYamlItem>,
    targetAppIds: Set<String>,
    clearOnEveryTrailhead: Boolean = false,
    memory: Map<String, String> = emptyMap(),
  ): Set<String> {
    // Any trailhead counts for the target option, an empty recording included: it is a valid
    // no-op trailhead, and the option promises a clear before every one.
    val trailheadTools = trailheadTools(trailItems) ?: return emptySet()
    if (clearOnEveryTrailhead) return targetAppIds
    val launch = trailheadTools.firstOrNull() as? LaunchAppTrailblazeTool ?: return emptySet()
    if (launch.launchMode != LaunchAppTrailblazeTool.LaunchMode.REINSTALL) return emptySet()
    val appId = resolve(launch.appId, trailItems, memory)
    if (TEMPLATE_TOKEN.containsMatchIn(appId)) return targetAppIds.singleOrNull()?.let(::setOf).orEmpty()
    return setOfNotNull(appId.takeIf { it in targetAppIds })
  }

  private val TEMPLATE_TOKEN = Regex("""\{\{|\$\{""")

  private fun resolve(appId: String, trailItems: List<TrailYamlItem>, memory: Map<String, String>): String {
    if (!TEMPLATE_TOKEN.containsMatchIn(appId)) return appId
    val agentMemory = AgentMemory()
    val configMemory = trailItems.firstNotNullOfOrNull { (it as? TrailYamlItem.ConfigTrailItem)?.config?.memory }
    // Only the keys the appId names, each marked sensitive first: remember() logs a value it does
    // not know to be sensitive, and this memory never learns which of the run's keys are.
    (configMemory.orEmpty() + memory).filterKeys { it in appId }.forEach { (key, value) ->
      agentMemory.markSensitive(key)
      agentMemory.remember(key, value)
    }
    return agentMemory.interpolateVariables(appId)
  }

  /** The trailhead's recorded tools, empty when it records none; null when the trail has no trailhead. */
  private fun trailheadTools(trailItems: List<TrailYamlItem>): List<TrailblazeTool>? =
    trailItems.firstNotNullOfOrNull { item ->
      when (item) {
        is TrailYamlItem.TrailheadTrailItem -> item.trailhead.tools.orEmpty()
        is TrailYamlItem.PromptsTrailItem -> item.promptSteps
          .firstOrNull { it is DirectionStep && it.isTrailhead }
          ?.let { it.recording?.tools.orEmpty() }
        else -> null
      }
    }?.map { it.trailblazeTool }

  /**
   * Clears [appId] on [deviceId]; returns whether `pm clear` reported success. One attempt only: a
   * retry after a transport failure could land after the run was cancelled, and clear the data of
   * the run that replaced it.
   */
  /**
   * Shows the home screen, so a fresh in-process run starts from it. The runner leaves the app's
   * first launch to the first UI step and keeps another app it finds in front, as the trail opened
   * that one (Settings, over adb); an app left over from an earlier run must not be mistaken for it.
   * Best effort: a device that refuses the key keeps the run going as before.
   */
  fun goHome(deviceId: TrailblazeDeviceId) {
    try {
      AndroidHostAdbUtils.execAdbShellCommandChecked(
        deviceId,
        listOf("input", "keyevent", "KEYCODE_HOME"),
        AndroidShellBounds.HOST_SHELL_TIMEOUT_MS,
      )
    } catch (e: InterruptedException) {
      throw e
    } catch (e: Exception) {
      Console.log("Showing the home screen before the in-process run failed: ${e.message}")
    }
  }

  fun clear(deviceId: TrailblazeDeviceId, appId: String): Boolean {
    val output = try {
      AndroidHostAdbUtils.execAdbShellCommandChecked(
        deviceId,
        listOf("pm", "clear", appId),
        AndroidShellBounds.HOST_SHELL_TIMEOUT_MS,
      )
    } catch (e: InterruptedException) {
      throw e
    } catch (e: Exception) {
      Console.log("pm clear $appId failed: ${e.message}")
      return false
    }
    val cleared = output.trim().equals("Success", ignoreCase = true)
    if (!cleared) Console.log("pm clear $appId did not report success: '${output.trim()}'")
    return cleared
  }
}
