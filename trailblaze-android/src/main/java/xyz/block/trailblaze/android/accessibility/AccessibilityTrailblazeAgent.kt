package xyz.block.trailblaze.android.accessibility

import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.Command
import maestro.orchestra.LaunchAppCommand
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.android.maestro.MaestroPermissionTranslator
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Accessibility-native [MaestroTrailblazeAgent] that bypasses Maestro's Orchestra, Driver, and
 * screenshot pipeline entirely.
 *
 * When Maestro commands arrive (from existing `MapsToMaestroCommands` tools or trail files),
 * this agent:
 * 1. Converts them to [AccessibilityAction] via [MaestroCommandConverter]
 * 2. Dispatches through [AccessibilityDeviceManager] (pure accessibility service gestures)
 * 3. Uses event-based settle detection instead of screenshot diffing
 * 4. Logs via [TrailblazeLog.AccessibilityActionLog] for full tracing insight
 *
 * Extends [MaestroTrailblazeAgent] for plug-in compatibility with `AndroidTrailblazeRule` and
 * existing tool infrastructure. The Maestro dependency here is only at the command-type boundary
 * (the [Command] interface for serialization) — actual execution is 100% Maestro-free.
 *
 * ### Performance
 * By skipping Maestro's screenshot-before-tap, screenshot-after-tap, and pixel-diff comparison,
 * per-action overhead drops from ~500-700ms to ~100ms (event-based settle only).
 */
class AccessibilityTrailblazeAgent(
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
  deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  memory: AgentMemory = AgentMemory(),
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  memory = memory,
) {

  override val usesAccessibilityDriver: Boolean = true

  private val deviceManager =
    AccessibilityDeviceManager(
      deviceClassifiers = deviceClassifiers,
    )

  /**
   * Processes Maestro commands, routing each to the appropriate execution path:
   * - [LaunchAppCommand] → ADB shell commands (app lifecycle is test setup, not UI interaction)
   * - All other commands → accessibility actions via [MaestroCommandConverter]
   *
   * This is called by:
   * - `MapsToMaestroCommands.execute()` when existing tools run through this agent
   * - `MaestroTrailblazeAgent.runMaestroCommands()` for trail items containing Maestro commands
   */
  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    // Handle LaunchAppCommand via ADB shell commands, matching Maestro Orchestra behavior.
    // The accessibility driver replaces UI interactions (taps, swipes, text input), not test
    // setup operations like app lifecycle management and permission granting.
    val launchCommands = commands.filterIsInstance<LaunchAppCommand>()
    for (launch in launchCommands) {
      executeLaunchAppViaAdb(launch)
    }

    val uiCommands = commands.filter { it !is LaunchAppCommand && it !is ApplyConfigurationCommand }
    if (uiCommands.isEmpty()) return TrailblazeToolResult.Success()

    Console.log(
      "AccessibilityTrailblazeAgent: Converting ${uiCommands.size} Maestro command(s) to accessibility actions"
    )

    val actions = MaestroCommandConverter.convertAll(uiCommands)
    if (actions.isEmpty() && uiCommands.isNotEmpty()) {
      val skippedTypes = uiCommands.map { it::class.simpleName }.distinct().joinToString(", ")
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage =
          "All ${uiCommands.size} UI command(s) are unsupported by the accessibility driver " +
            "and produced no actions. Unsupported types: $skippedTypes"
      )
    }

    return AccessibilityTrailRunner.runActions(
      actions = actions,
      traceId = traceId,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
      deviceManager = deviceManager,
    )
  }

  /**
   * Handles [LaunchAppCommand] via ADB shell commands, replicating Maestro Orchestra's behavior:
   * stop app, clear data, grant permissions, then launch.
   *
   * This runs in the instrumentation process (has UiAutomation access for shell commands).
   * The actual app launch uses the accessibility service's [Context.startActivity].
   */
  private fun executeLaunchAppViaAdb(command: LaunchAppCommand) {
    val appId = command.appId
    Console.log("AccessibilityTrailblazeAgent: Launching $appId via ADB")

    if (command.stopApp == true) {
      AdbCommandUtil.forceStopApp(appId)
    }
    if (command.clearState == true) {
      AdbCommandUtil.clearPackageData(appId)
    }

    // Grant permissions — matches MaestroAndroidUiAutomatorDriver.setPermissions() behavior,
    // including the short-name → fully-qualified Android permission translation.
    val permissionsToGrant =
      command.permissions?.filterValues { it == "allow" }?.keys ?: emptySet()
    for (shortName in permissionsToGrant) {
      MaestroPermissionTranslator.translate(shortName).forEach { fqPermission ->
        try {
          AdbCommandUtil.grantPermission(appId, fqPermission)
        } catch (e: Exception) {
          Console.log("Failed to grant $fqPermission to $appId: ${e.message}")
        }
      }
    }

    // Launch via accessibility service (Context.startActivity with FLAG_ACTIVITY_CLEAR_TASK)
    TrailblazeAccessibilityService.launchApp(appId)
    TrailblazeAccessibilityService.waitForSettled(timeoutMs = 10_000L)
  }

  /**
   * Executes a tap using the rich [TrailblazeNodeSelector], bypassing Maestro commands entirely.
   *
   * Converts the selector into an [AccessibilityAction.TapOnElement] and dispatches through
   * the [AccessibilityDeviceManager], which uses [TrailblazeNodeSelectorResolver] for rich
   * matching.
   */
  override suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val action = AccessibilityAction.TapOnElement(
      nodeSelector = nodeSelector,
      longPress = longPress,
    )

    return AccessibilityTrailRunner.runActions(
      actions = listOf(action),
      traceId = traceId,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
      deviceManager = deviceManager,
    )
  }

  /**
   * Asserts that an element matching the [nodeSelector] is visible using the accessibility tree.
   */
  override suspend fun executeNodeSelectorAssertVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val action = AccessibilityAction.AssertVisible(
      nodeSelector = nodeSelector,
      timeoutMs = timeoutMs,
    )

    return AccessibilityTrailRunner.runActions(
      actions = listOf(action),
      traceId = traceId,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
      deviceManager = deviceManager,
    )
  }

  /**
   * Asserts that no element matching the [nodeSelector] is visible using the accessibility tree.
   */
  override suspend fun executeNodeSelectorAssertNotVisible(
    nodeSelector: TrailblazeNodeSelector,
    timeoutMs: Long,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val action = AccessibilityAction.AssertNotVisible(
      nodeSelector = nodeSelector,
      timeoutMs = timeoutMs,
    )

    return AccessibilityTrailRunner.runActions(
      actions = listOf(action),
      traceId = traceId,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
      deviceManager = deviceManager,
    )
  }

  /** Provides the screen state using the accessibility service (no Maestro driver). */
  fun getScreenState() = deviceManager.getScreenState()
}
