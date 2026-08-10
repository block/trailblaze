package xyz.block.trailblaze.android.accessibility

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.Command
import maestro.orchestra.LaunchAppCommand
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.android.maestro.MaestroPermissionTranslator
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.commands.allDriverMatches
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TapRouteOverride
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
  resolvedTarget: ResolvedTarget? = null,
  appId: String? = null,
  // Threaded to the base so an `OtherTrailblazeTool` (e.g. a toolset-delivered scripted tool like
  // `openUrl`) resolves through this repo's dynamic-tool registrations before driver dispatch.
  // Without it, the on-device launcher registers the scripted tool into the session repo but the
  // dispatching agent resolves against a null repo → "Unknown tool" at execution; framework tools
  // composed by name via `invokeFrameworkTool` (e.g. a launch step authored as a TypeScript tool)
  // crash with "toolRepo not wired".
  //
  // **Required and non-null on purpose.** This agent is duplicate-constructed by more than one
  // JUnit rule — [AndroidTrailblazeRule] here, plus a downstream subclass — which each
  // must hand-sync this param. A `= null` default let one rule silently omit it: a downstream rule
  // wired `resolvedTarget`/`appId` but missed the repo, a latent gap until a launch step composed a
  // framework tool by name and crashed at runtime (#3920). These rules always build a session repo,
  // so the param is non-null with no default: omitting it — or passing `null` to re-introduce that
  // gap — is a compile error. A caller with no tools to register passes an empty `TrailblazeToolRepo`,
  // never `null`. (The base keeps a nullable default for the host/test agents that legitimately run
  // without a repo.)
  trailblazeToolRepo: xyz.block.trailblaze.toolcalls.TrailblazeToolRepo,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  memory = memory,
  resolvedTarget = resolvedTarget,
  appId = appId,
  trailblazeToolRepo = trailblazeToolRepo,
) {

  override val usesAccessibilityDriver: Boolean = true

  private val deviceManager =
    AccessibilityDeviceManager(
      deviceClassifiers = deviceClassifiers,
      templateContext = resolvedTarget?.let {
        xyz.block.trailblaze.api.TargetTemplateContext(appId = appId, appIds = it.appIds)
      },
    )

  /**
   * The base implementation dispatches one command at a time, which would let earlier commands
   * of a batch execute before a later unsupported one fails conversion — and every tool call
   * site ([xyz.block.trailblaze.toolcalls.MapsToMaestroCommands.execute],
   * `MaestroTrailblazeTool`, etc.) lands here, not on [executeMaestroCommands]. Validate the
   * whole batch up front (conversion is pure), then keep the base per-command dispatch: unlike
   * the iOS twin's delegate-the-whole-batch approach, this preserves recorded ordering —
   * [executeMaestroCommands] hoists launch commands within a batch, which would reorder a
   * recording that launches an app after UI commands.
   */
  override suspend fun runMaestroCommands(
    maestroCommands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    convertBatchOrError(maestroCommands)?.let { return it }
    return super.runMaestroCommands(maestroCommands, traceId)
  }

  /**
   * Processes Maestro commands, routing each to the appropriate execution path:
   * - [LaunchAppCommand] → ADB shell commands (app lifecycle is test setup, not UI interaction)
   * - All other commands → accessibility actions via [MaestroCommandConverter]
   *
   * Reached through [runMaestroCommands] above (whole-batch validation, then the base
   * per-command loop lands here one command at a time).
   */
  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    // Convert the batch's UI commands before ANY dispatch — including app launches — so an
    // unconvertible command fails loudly before earlier commands execute side effects. A
    // partially-run batch reporting Success is the bug this closes. Mirrors the iOS twin
    // (IosDriverTrailblazeAgent.executeMaestroCommands).
    val actions: List<AccessibilityAction>
    try {
      actions = MaestroCommandConverter.convertAll(uiCommandsOf(commands))
    } catch (e: TrailblazeException) {
      return conversionError(e)
    }

    // Handle LaunchAppCommand via ADB shell commands, matching Maestro Orchestra behavior.
    // The accessibility driver replaces UI interactions (taps, swipes, text input), not test
    // setup operations like app lifecycle management and permission granting.
    val launchCommands = commands.filterIsInstance<LaunchAppCommand>()
    for (launch in launchCommands) {
      executeLaunchAppViaAdb(launch)
    }

    if (actions.isEmpty()) return TrailblazeToolResult.Success()

    Console.log(
      "AccessibilityTrailblazeAgent: Converted ${actions.size} accessibility action(s) from Maestro commands"
    )

    return AccessibilityTrailRunner.runActions(
      actions = actions,
      traceId = traceId,
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
      deviceManager = deviceManager,
    )
  }

  /** Commands the converter services — launches and config are handled outside the converter. */
  private fun uiCommandsOf(commands: List<Command>): List<Command> =
    commands.filter { it !is LaunchAppCommand && it !is ApplyConfigurationCommand }

  /**
   * Converts the batch's UI commands purely as validation, returning the loud failure result
   * when any command is unsupported and null when the whole batch converts. Conversion is pure,
   * so re-running it per command at dispatch time costs nothing observable.
   */
  private fun convertBatchOrError(commands: List<Command>): TrailblazeToolResult.Error? = try {
    MaestroCommandConverter.convertAll(uiCommandsOf(commands))
    null
  } catch (e: TrailblazeException) {
    conversionError(e)
  }

  private fun conversionError(e: TrailblazeException): TrailblazeToolResult.Error.ExceptionThrown =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = e.message ?: "Unsupported Maestro command on the accessibility driver",
      // Distinguishes a converter bug from a genuinely-unsupported command in session logs.
      stackTrace = e.stackTraceToString(),
    )

  /**
   * Handles [LaunchAppCommand] via ADB shell commands, replicating Maestro Orchestra's behavior:
   * stop app, clear data, grant permissions, then launch.
   *
   * This runs in the instrumentation process (has UiAutomation access for shell commands). The
   * actual app launch uses the accessibility service's [Context.startActivity]. All of it is
   * blocking (shell execs, plus — when the inprocess-idle re-attach engages — a socket PING poll of
   * up to [InProcessIdleLaunchReattacher.awaitAttachedAfterLaunch]'s bound), so it runs on
   * [Dispatchers.IO] to keep those seconds off the caller's (bounded) coroutine dispatcher — same
   * treatment [waitForTreeChange] gives its blocking device call.
   */
  private suspend fun executeLaunchAppViaAdb(command: LaunchAppCommand) = withContext(Dispatchers.IO) {
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

    // A stop/clear above just killed any attached in-process idle with the app's process; when the
    // settle-race is opted in and the target's idle detector package is installed, re-attach it now —
    // `am instrument` restarts the target's process, so it must run BEFORE the launch below
    // (which then keeps the instrumented cold start foregrounded, off the background
    // proc-start ANR path). Best-effort: a failed re-attach only means heuristic-speed settles.
    val inProcessIdleAttachStarted = InProcessIdleLaunchReattacher.attachBeforeLaunch(appId)

    // Launch via accessibility service (Context.startActivity with FLAG_ACTIVITY_CLEAR_TASK)
    TrailblazeAccessibilityService.launchApp(appId)
    if (inProcessIdleAttachStarted) {
      InProcessIdleLaunchReattacher.awaitAttachedAfterLaunch(appId)
    }
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
    tapRoute: TapRouteOverride?,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val action = AccessibilityAction.TapOnElement(
      nodeSelector = nodeSelector,
      longPress = longPress,
      tapRoute = tapRoute,
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
    timeoutMs: Long?,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val action = AccessibilityAction.AssertVisible(
      nodeSelector = nodeSelector,
      timeoutMs = timeoutMs ?: DEFAULT_ACCESSIBILITY_TIMEOUT_MS,
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
    timeoutMs: Long?,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    // A not-visible assertion treats "no match" as success. This agent resolves only
    // AndroidAccessibility selectors against the live accessibility tree, so a selector
    // carrying any other driver branch (e.g. androidMaestro) would never match — and would
    // falsely "pass" as not-visible for the wrong reason. We check the WHOLE selector tree
    // (not just the top-level driverMatch) so a non-accessibility branch nested under a
    // hierarchy/spatial relation (e.g. `below:`) is caught too. Return null so the caller
    // (AssertNotVisibleBySelectorTrailblazeTool) falls back to the driver-agnostic Maestro
    // lowering, which matches against the live UI. The visible counterpart needs no such
    // guard: there a no-match is a failure, not a pass, so it can't hide a regression.
    if (nodeSelector.allDriverMatches().any { it !is DriverNodeMatch.AndroidAccessibility }) {
      Console.log(
        "AccessibilityTrailblazeAgent: assertNotVisible selector carries a non-accessibility " +
          "driver branch it cannot resolve; returning null to fall back to Maestro lowering",
      )
      return null
    }
    val action = AccessibilityAction.AssertNotVisible(
      nodeSelector = nodeSelector,
      timeoutMs = timeoutMs ?: DEFAULT_ACCESSIBILITY_TIMEOUT_MS,
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
   * Waits for the accessibility tree to change relative to the baseline captured at entry,
   * then settles. Captures the baseline event timestamp here (call entry) so it reflects the
   * moment the step started rather than the moment the service loop begins polling.
   *
   * If the UI is already settled at entry (the reaction already happened and went quiet before
   * this ran), succeeds immediately regardless of [requireChange]. [requireChange] only governs
   * the timeout case: true → error, false → success.
   */
  override suspend fun waitForTreeChange(
    timeoutMs: Long,
    quietWindowMs: Long,
    requireChange: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    val baselineEventTs = TrailblazeAccessibilityService.lastUiEventTimestampMs
    val outcome = withContext(Dispatchers.IO) {
      deviceManager.waitForChange(
        baselineEventTs = baselineEventTs,
        quietWindowMs = quietWindowMs,
        timeoutMs = timeoutMs,
      )
    }
    return when (outcome) {
      TrailblazeAccessibilityService.WaitForChangeOutcome.ALREADY_SETTLED ->
        TrailblazeToolResult.Success(message = "UI was already settled at entry")
      TrailblazeAccessibilityService.WaitForChangeOutcome.CHANGED_AND_SETTLED ->
        TrailblazeToolResult.Success(message = "UI changed and settled")
      TrailblazeAccessibilityService.WaitForChangeOutcome.TIMED_OUT ->
        if (requireChange) {
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "UI did not change and settle within ${timeoutMs}ms",
          )
        } else {
          TrailblazeToolResult.Success(
            message = "No settled UI change within ${timeoutMs}ms; treated as a settle wait",
          )
        }
    }
  }

  /** Provides the screen state using the accessibility service (no Maestro driver). */
  fun getScreenState() = deviceManager.getScreenState()

  companion object {
    /**
     * Driver-default wait when the caller passes `timeoutMs = null`. Matches the prior
     * hardcoded default so trails that never set the field keep their existing behavior.
     */
    private const val DEFAULT_ACCESSIBILITY_TIMEOUT_MS = 5_000L
  }
}
