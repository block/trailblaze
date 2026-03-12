package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.DirectAgentConfig
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
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
 * Result of [TrailblazeMcpBridge.runDirectAgentBlocking].
 */
sealed interface RunDirectAgentBlockingResult {
  /** All objectives completed successfully. */
  data class Success(
    val completedObjectives: Int,
    val totalIterations: Int,
    val totalActions: Int,
  ) : RunDirectAgentBlockingResult

  /** Agent execution failed. */
  data class Failed(val message: String) : RunDirectAgentBlockingResult

  /** Method not implemented by this bridge. */
  data object NotImplemented : RunDirectAgentBlockingResult
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
   * @return A lambda that captures fresh screen state with optional scaling config,
   *         or null if no device is connected
   */
  fun getDirectScreenStateProvider(): ((ScreenshotScalingConfig) -> ScreenState)? = null

  /**
   * Executes a TrailblazeTool directly on the connected device.
   * This enables MCP clients to act as the agent, calling low-level device control tools.
   *
   * @param tool The TrailblazeTool to execute (e.g., TapOnPointTrailblazeTool, SwipeTrailblazeTool)
   * @return Result string describing the execution outcome
   */
  suspend fun executeTrailblazeTool(tool: TrailblazeTool): String

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
   * Gets screen state via RPC for on-device instrumentation mode.
   * This calls the GetScreenStateRequest endpoint on the on-device agent.
   *
   * Only applicable when isOnDeviceInstrumentation() returns true.
   *
   * @param includeScreenshot Whether to include screenshot bytes
   * @param filterViewHierarchy Whether to filter to interactable elements
   * @param screenshotScalingConfig Configuration for scaling/compressing screenshots on-device
   *                                before transfer. Scaling on-device saves bandwidth and tokens.
   * @return GetScreenStateResponse on success, null on failure or if not using on-device mode
   */
  suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean = true,
    filterViewHierarchy: Boolean = true,
    screenshotScalingConfig: ScreenshotScalingConfig = ScreenshotScalingConfig.DEFAULT,
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
   * @return The session ID (existing or newly created), or null if no device is selected
   */
  suspend fun ensureSessionAndGetId(): SessionId? = null

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
   * Runs DirectMcpAgent and blocks until all objectives complete.
   * This executes the Koog-based agent (either on host or on-device depending on driver type).
   *
   * For on-device drivers: Sends RunDirectAgentRequest via RPC
   * For host drivers: Runs DirectMcpAgent locally
   *
   * @param objectives List of natural language objectives to achieve
   * @param onProgress Callback for progress messages during execution
   * @param directAgentConfig Configuration for the DirectMcpAgent (iterations, screenshots, etc.)
   * @return Result describing success/failure
   */
  suspend fun runDirectAgentBlocking(
    objectives: List<String>,
    onProgress: (String) -> Unit = {},
    directAgentConfig: DirectAgentConfig = DirectAgentConfig(),
  ): RunDirectAgentBlockingResult = RunDirectAgentBlockingResult.NotImplemented

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
}
