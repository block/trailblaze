package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * Result of [TrailblazeMcpBridge.runYamlBlocking].
 */
sealed interface RunYamlBlockingResult {
  /** All objectives completed successfully. */
  data class Success(val completedObjectives: Int) : RunYamlBlockingResult

  /** One or more objectives timed out. */
  data class Timeout(val timedOutObjective: String) : RunYamlBlockingResult

  /** Execution failed with an error. */
  data class Error(val message: String) : RunYamlBlockingResult

  /** Method not implemented by this bridge. */
  data object NotImplemented : RunYamlBlockingResult
}

/**
 * Bridges functions between the Trailblaze Device Manager and the MCP Server
 */
interface TrailblazeMcpBridge {
  suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary
  suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary>
  suspend fun getInstalledAppIds(): Set<String>
  fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget>
  /**
   * Runs Trailblaze YAML on the connected device.
   *
   * @return The session ID value for monitoring progress, or null if unavailable.
   */
  suspend fun runYaml(
    yaml: String,
    startNewSession: Boolean,
    agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,
  ): String

  /**
   * Allows us to see the "connected device" from the viewpoint of the MCP server.
   */
  fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId?

  suspend fun getCurrentScreenState(): ScreenState?

  /**
   * Returns a direct screen state provider for the currently selected device.
   * This captures screen state directly from the Maestro driver without creating sessions.
   * More reliable than getCurrentScreenState() for MCP use cases.
   *
   * @param skipScreenshot When true, skip screenshot capture and settling for maximum speed.
   * @return A lambda that captures fresh screen state with optional scaling config,
   *         or null if no device is connected
   */
  fun getDirectScreenStateProvider(skipScreenshot: Boolean = false): ((ScreenshotScalingConfig) -> ScreenState)? = null

  /**
   * Executes a TrailblazeTool directly on the connected device.
   * This enables MCP clients to act as the agent, calling low-level device control tools.
   *
   * @param tool The TrailblazeTool to execute (e.g., TapOnPointTrailblazeTool, SwipeTrailblazeTool)
   * @param blocking When true, suspends until the tool execution completes on the device.
   *   When false (default), the HOST/Maestro path is fire-and-forget and may return before
   *   the action finishes. Use blocking=true when you need to capture screen state after
   *   the action (e.g., agent-driven CLI flows).
   * @return Result string describing the execution outcome
   */
  suspend fun executeTrailblazeTool(tool: TrailblazeTool, blocking: Boolean = false): String

  /**
   * Ends the current session on the selected device.
   * Clears the session state and writes a session end log.
   *
   * @return true if a session was ended, false if no session was active
   */
  suspend fun endSession(): Boolean

  /**
   * Checks if the currently selected device is using on-device instrumentation mode.
   * This is useful for determining which screen capture method to use.
   *
   * @return true if device uses on-device instrumentation, false otherwise
   */
  fun isOnDeviceInstrumentation(): Boolean = false

  /**
   * Gets the driver type for the currently selected device.
   *
   * @return The driver type, or null if no device is selected
   */
  fun getDriverType(): TrailblazeDriverType? = null

  /**
   * Returns the tool classes the inner agent should use for the currently connected driver.
   *
   * When non-null, [StepToolSet] uses these classes instead of the default
   * [ToolSetCategoryMapping.getToolClasses] call. This allows driver-specific tool sets
   * (e.g., Compose tools instead of native Android tools) to be injected without the
   * server module needing a direct dependency on driver-specific modules.
   *
   * @return Driver-specific tool classes, or null to fall back to the default tool set
   */
  fun getInnerAgentToolClasses(): Set<KClass<out TrailblazeTool>>? = null

  /**
   * Returns the status of the device driver connection for the given device.
   *
   * @return null if no connection is in progress or the device is ready,
   *         or a human-readable status string like "Device driver initializing (12s elapsed)"
   */
  fun getDriverConnectionStatus(deviceId: TrailblazeDeviceId? = null): String? = null

  /**
   * Gets screen state via RPC for on-device instrumentation mode.
   * This calls the GetScreenStateRequest endpoint on the on-device agent.
   *
   * Only applicable when isOnDeviceInstrumentation() returns true.
   *
   * @param includeScreenshot Whether to include screenshot bytes
   * @param screenshotScalingConfig Configuration for scaling/compressing screenshots on-device
   *                                before transfer. Scaling on-device saves bandwidth and tokens.
   * @param includeAnnotatedScreenshot Whether to render and include the set-of-mark annotated
   *                                   screenshot. Defaults to true for backward compatibility;
   *                                   non-LLM callers should explicitly pass false to save CPU,
   *                                   memory, and bandwidth.
   * @param includeAllElements Whether the on-device agent should skip its accessibility
   *                           importance filter and return every node. Defaults to false to
   *                           keep the default response small; set true for `--all` /
   *                           [SnapshotDetail.ALL_ELEMENTS] callers.
   * @return GetScreenStateResponse on success, null on failure or if not using on-device mode
   */
  suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean = true,
    screenshotScalingConfig: ScreenshotScalingConfig = ScreenshotScalingConfig.DEFAULT,
    includeAnnotatedScreenshot: Boolean = true,
    includeAllElements: Boolean = false,
  ): GetScreenStateResponse? = null

  /**
   * Gets the active Trailblaze session ID for the currently selected device.
   * This is used for subscribing to session logs for progress notifications.
   *
   * @return The active session ID, or null if no session is active
   */
  fun getActiveSessionId(): SessionId? = null

  /**
   * Ensures a session exists for the currently selected device and returns its ID.
   * If no session exists, creates one. This is used to get a session ID for
   * progress notification monitoring BEFORE running YAML.
   *
   * @param testName Optional display name for the session (shown in the Trailblaze report)
   * @return The session ID (existing or newly created), or null if no device is selected
   */
  suspend fun ensureSessionAndGetId(testName: String? = null): SessionId? = null

  /**
   * Runs YAML and blocks until all objectives complete.
   * This is the MCP-specific way of running YAML - it waits for completion
   * rather than fire-and-forget like the desktop UI.
   *
   * @param yaml The YAML content to execute
   * @param objectives List of prompt strings to wait for (matches ObjectiveCompleteLog.promptStep.prompt)
   * @param onProgress Callback for progress messages during execution
   * @param timeoutPerObjective Maximum time to wait for each objective
   * @param agentImplementation Which agent to use for execution (default TRAILBLAZE_RUNNER)
   * @return Result describing success/failure
   */
  suspend fun runYamlBlocking(
    yaml: String,
    objectives: List<String>,
    onProgress: (String) -> Unit = {},
    timeoutPerObjective: Duration = Duration.parse("5m"),
    agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,
  ): RunYamlBlockingResult = RunYamlBlockingResult.NotImplemented

  /**
   * Cancels any running automation on the specified device.
   * This forcefully stops the automation by closing the driver and cancelling coroutines.
   * Used when an MCP client disconnects to stop orphaned automation runs.
   *
   * @param deviceId The device to cancel automation on
   */
  fun cancelAutomation(deviceId: TrailblazeDeviceId) {}

  /**
   * Switches the current target app to the one matching the given app target ID.
   *
   * @param appTargetId The ID of the app target to switch to (e.g., "myApp", "otherApp")
   * @return The display name of the newly selected app target, or null if not found
   */
  fun selectAppTarget(appTargetId: String): String?

  /**
   * Returns the ID of the currently selected target app, or null if none is selected.
   */
  fun getCurrentAppTargetId(): String?

  /**
   * Returns the configured driver type for the given platform from the app settings.
   * This is the driver type selected in the desktop app UI or via `trailblaze config`.
   *
   * @param platform The platform to look up the configured driver type for
   * @return The configured driver type, or null if no driver is configured for this platform
   */
  fun getConfiguredDriverType(platform: TrailblazeDevicePlatform): TrailblazeDriverType? = null

  /**
   * Releases the persistent device connection and cached state for a specific device.
   * Called during MCP session cleanup to free resources without ending the Trailblaze session.
   *
   * @param deviceId The device whose persistent connection should be closed
   */
  fun releasePersistentDeviceConnection(deviceId: TrailblazeDeviceId) {}

  /**
   * Sets the bridge's active device selection without validation or connection setup.
   * Used by the MCP tool handler to restore per-session device context before each tool call,
   * ensuring HTTP multi-session doesn't cross-wire device operations.
   *
   * @param deviceId The device to set as active for the current tool call
   */
  fun selectDeviceForSession(deviceId: TrailblazeDeviceId) {}

  // ─────────────────────────────────────────────────────────────────────────────
  // Configuration access (for config MCP tool)
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Sets the configured driver type for a platform in the persisted app config.
   *
   * @return null on success, or an error message
   */
  fun setConfiguredDriverType(
    platform: TrailblazeDevicePlatform,
    driverType: TrailblazeDriverType,
  ): String? = "Not implemented"

  /**
   * Returns the current LLM provider and model IDs from the persisted app config.
   *
   * @return Pair of (providerId, modelId), or null if not available
   */
  fun getLlmConfig(): Pair<String, String>? = null

  /**
   * Updates the LLM provider and/or model in the persisted app config.
   * Pass null for either parameter to keep the current value.
   *
   * @return null on success, or an error message
   */
  fun setLlmConfig(provider: String?, model: String?): String? = "Not implemented"

  /**
   * Returns the current agent implementation from the persisted app config.
   */
  fun getAgentImplementation(): AgentImplementation? = null

  /**
   * Updates the agent implementation in the persisted app config.
   *
   * @return null on success, or an error message
   */
  fun setAgentImplementation(implementation: AgentImplementation): String? = "Not implemented"

  /**
   * Returns the built-in tool classes for the inner agent based on the currently connected device.
   *
   * Returns an empty set by default — [TrailblazeMcpServer] falls back to the standard Maestro
   * tool set when this is empty. Overridden by `TrailblazeMcpBridgeImpl` to resolve driver-scoped
   * tools from the YAML toolset catalog (e.g., `web_core`/`web_verification` for Playwright) when
   * a non-mobile device is connected.
   */
  fun getInnerAgentBuiltInToolClasses(): Set<KClass<out TrailblazeTool>> = emptySet()
}
