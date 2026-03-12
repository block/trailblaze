package xyz.block.trailblaze.mcp.integration

import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.AgentToolTransport
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.mcp.ScreenshotFormat
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity

/**
 * Type-safe extension functions for [McpTestClient].
 *
 * These extensions wrap the generic `callTool` method with strongly-typed parameters
 * that use the actual enum types from the Trailblaze MCP models.
 */

// =============================================================================
// Device Management Tools
// =============================================================================

/**
 * Lists all connected devices.
 */
suspend fun McpTestClient.listConnectedDevices(): McpTestClient.ToolResult =
  callTool(ToolNames.LIST_CONNECTED_DEVICES, emptyMap())

/**
 * Connects to a device by its ID.
 */
/**
 * Connects to a device by its ID.
 *
 * @param instanceId The device instance ID (e.g., "emulator-5554" or simulator UUID)
 * @param platform The device platform ("android" or "ios")
 */
suspend fun McpTestClient.connectToDevice(
  instanceId: String,
  platform: String,
): McpTestClient.ToolResult =
  callTool(
    ToolNames.CONNECT_TO_DEVICE,
    mapOf(
      ToolParams.TRAILBLAZE_DEVICE_ID to mapOf(
        "instanceId" to instanceId,
        "trailblazeDevicePlatform" to platform.uppercase(),
      ),
    ),
  )

/**
 * Takes a screenshot of the current screen.
 * Returns base64-encoded PNG image data.
 */
suspend fun McpTestClient.getScreenshot(): McpTestClient.ToolResult =
  callTool(ToolNames.GET_SCREENSHOT, emptyMap())

/**
 * Gets the current screen state (view hierarchy + optional screenshot).
 */
suspend fun McpTestClient.getScreenState(
  includeScreenshot: Boolean = true,
  verbosity: ViewHierarchyVerbosity? = null,
): McpTestClient.ToolResult =
  callTool(
    ToolNames.GET_SCREEN_STATE,
    buildMap {
      put(ToolParams.INCLUDE_SCREENSHOT, includeScreenshot)
      verbosity?.let { put(ToolParams.VERBOSITY, it.name) }
    },
  )

/**
 * Gets the view hierarchy with the specified verbosity.
 */
suspend fun McpTestClient.viewHierarchy(
  verbosity: ViewHierarchyVerbosity = ViewHierarchyVerbosity.MINIMAL
): McpTestClient.ToolResult =
  callTool(ToolNames.VIEW_HIERARCHY, mapOf(ToolParams.VERBOSITY to verbosity.name))

// =============================================================================
// Session Configuration Tools
// =============================================================================

/**
 * Sets the MCP operating mode.
 */
suspend fun McpTestClient.setMode(mode: TrailblazeMcpMode): McpTestClient.ToolResult =
  callTool(ToolNames.SET_MODE, mapOf(ToolParams.MODE to mode.name))

/**
 * Sets the screenshot format for tool responses.
 */
suspend fun McpTestClient.setScreenshotFormat(format: ScreenshotFormat): McpTestClient.ToolResult =
  callTool(ToolNames.SET_SCREENSHOT_FORMAT, mapOf(ToolParams.FORMAT to format.name))

/**
 * Sets the view hierarchy verbosity level.
 */
suspend fun McpTestClient.setViewHierarchyVerbosity(verbosity: ViewHierarchyVerbosity): McpTestClient.ToolResult =
  callTool(ToolNames.SET_VIEW_HIERARCHY_VERBOSITY, mapOf(ToolParams.VERBOSITY to verbosity.name))

/**
 * Sets the LLM call strategy for agent operations.
 */
suspend fun McpTestClient.setLlmCallStrategy(strategy: LlmCallStrategy): McpTestClient.ToolResult =
  callTool(ToolNames.SET_LLM_CALL_STRATEGY, mapOf(ToolParams.STRATEGY to strategy.name))

/**
 * Sets the agent tool transport mode.
 */
suspend fun McpTestClient.setAgentToolTransport(transport: AgentToolTransport): McpTestClient.ToolResult =
  callTool(ToolNames.SET_AGENT_TOOL_TRANSPORT, mapOf(ToolParams.TRANSPORT to transport.name))

/**
 * Sets the agent implementation to use.
 */
suspend fun McpTestClient.setAgentImplementation(implementation: AgentImplementation): McpTestClient.ToolResult =
  callTool(ToolNames.SET_AGENT_IMPLEMENTATION, mapOf(ToolParams.IMPLEMENTATION to implementation.name))

/**
 * Sets maximum iterations per objective for DirectMcpAgent.
 */
suspend fun McpTestClient.setMaxIterations(iterations: Int): McpTestClient.ToolResult =
  callTool(ToolNames.CONFIGURE_SESSION, mapOf(ToolParams.MAX_ITERATIONS_PER_OBJECTIVE to iterations))

/**
 * Gets the current session configuration.
 */
suspend fun McpTestClient.getSessionConfig(): McpTestClient.ToolResult =
  callTool(ToolNames.GET_SESSION_CONFIG, emptyMap())

// =============================================================================
// Agent Execution Tools
// =============================================================================

/**
 * Runs natural language prompts through the Trailblaze agent.
 */
suspend fun McpTestClient.runPrompt(steps: List<String>): McpTestClient.ToolResult =
  callTool(ToolNames.RUN_PROMPT, mapOf(ToolParams.STEPS to steps))

// =============================================================================
// Two-Tier Agent Tools (Inner Agent via MCP)
// =============================================================================

/**
 * Gets screen analysis from the inner agent (InnerLoopScreenAnalyzer).
 *
 * This is used when an external MCP client acts as the outer agent (strategist)
 * and wants the inner agent to analyze the current screen and recommend an action.
 *
 * @param objective The user's objective (what they want to accomplish)
 * @param progressSummary Optional summary of progress so far
 * @param hint Optional hint from previous attempt (e.g., why last action failed)
 * @param attemptNumber Which attempt this is (1-indexed)
 */
suspend fun McpTestClient.getScreenAnalysis(
  objective: String,
  progressSummary: String? = null,
  hint: String? = null,
  attemptNumber: Int = 1,
): McpTestClient.ToolResult =
  callTool(
    ToolNames.GET_SCREEN_ANALYSIS,
    buildMap {
      put(ToolParams.OBJECTIVE, objective)
      progressSummary?.let { put(ToolParams.PROGRESS_SUMMARY, it) }
      hint?.let { put(ToolParams.HINT, it) }
      put(ToolParams.ATTEMPT_NUMBER, attemptNumber)
    },
  )

/**
 * Executes a UI action recommended by the inner agent.
 *
 * This is used when an external MCP client acts as the outer agent (strategist)
 * and wants to execute an action that was recommended by getScreenAnalysis.
 *
 * @param toolName The tool to execute (e.g., "tapOnElementByNodeId", "inputText")
 * @param args The arguments for the tool as a JSON-compatible map
 */
suspend fun McpTestClient.executeUiAction(
  toolName: String,
  args: Map<String, Any?>,
): McpTestClient.ToolResult =
  callTool(
    ToolNames.EXECUTE_UI_ACTION,
    mapOf(
      ToolParams.TOOL_NAME to toolName,
      ToolParams.ARGS to args,
    ),
  )

// =============================================================================
// Tool Names (Constants)
// =============================================================================

/**
 * Constants for MCP tool names.
 * Use these instead of hardcoded strings for type safety and refactoring support.
 */
object ToolNames {
  // Device Management
  const val LIST_CONNECTED_DEVICES = "listConnectedDevices"
  const val CONNECT_TO_DEVICE = "connectToDevice"
  const val GET_SCREENSHOT = "getScreenshot"
  const val GET_SCREEN_STATE = "getScreenState"
  const val VIEW_HIERARCHY = "viewHierarchy"

  // Session Configuration
  const val SET_MODE = "setMode"
  const val SET_SCREENSHOT_FORMAT = "setScreenshotFormat"
  const val SET_VIEW_HIERARCHY_VERBOSITY = "setViewHierarchyVerbosity"
  const val SET_LLM_CALL_STRATEGY = "setLlmCallStrategy"
  const val SET_AGENT_TOOL_TRANSPORT = "setAgentToolTransport"
  const val SET_AGENT_IMPLEMENTATION = "setAgentImplementation"
  const val CONFIGURE_SESSION = "configureSession"
  const val GET_SESSION_CONFIG = "getSessionConfig"

  // Agent Execution (Single-tier)
  const val RUN_PROMPT = "runPrompt"

  // Two-Tier Agent Tools (Inner Agent)
  const val GET_SCREEN_ANALYSIS = "getScreenAnalysis"
  const val EXECUTE_UI_ACTION = "executeUiAction"
}

/**
 * Constants for tool parameter names.
 */
object ToolParams {
  const val TRAILBLAZE_DEVICE_ID = "trailblazeDeviceId"
  const val VERBOSITY = "verbosity"
  const val INCLUDE_SCREENSHOT = "includeScreenshot"
  const val MODE = "mode"
  const val FORMAT = "format"
  const val STRATEGY = "strategy"
  const val TRANSPORT = "transport"
  const val IMPLEMENTATION = "implementation"
  const val STEPS = "steps"
  const val MAX_ITERATIONS_PER_OBJECTIVE = "maxIterationsPerObjective"

  // Two-Tier Agent params
  const val OBJECTIVE = "objective"
  const val PROGRESS_SUMMARY = "progressSummary"
  const val HINT = "hint"
  const val ATTEMPT_NUMBER = "attemptNumber"
  const val TOOL_NAME = "toolName"
  const val ARGS = "args"
}
