package xyz.block.trailblaze.agent

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.util.Console

/**
 * Interface for executing UI actions on a connected device.
 *
 * The UI action executor provides a clean abstraction over the device control layer,
 * allowing the two-tier agent architecture to execute recommended actions without
 * coupling to specific implementation details.
 *
 * ## Responsibilities
 *
 * - Execute UI tools (tap, swipe, input, etc.) by name
 * - Capture current screen state for analysis
 * - Return structured execution results
 *
 * ## Design
 *
 * This interface separates execution from analysis. The [ScreenAnalyzer] decides
 * what to do, and the [UiActionExecutor] does it. This allows:
 * - Different execution backends (Maestro, UIAutomator, XCTest)
 * - Mocking for testing
 * - Consistent result format across platforms
 *
 * ## Usage
 *
 * ```kotlin
 * val executor: UiActionExecutor = MaestroUiActionExecutor(agent)
 *
 * // Execute a recommended action
 * val result = executor.execute(
 *   toolName = "tapOnElementByNodeId",
 *   args = buildJsonObject { put("nodeId", "login_button") },
 *   traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
 * )
 *
 * when (result) {
 *   is ExecutionResult.Success -> Console.log("Action succeeded: ${result.screenSummaryAfter}")
 *   is ExecutionResult.Failure -> Console.log("Action failed: ${result.error}")
 * }
 * ```
 *
 * @see ExecutionResult The result of executing an action
 * @see ScreenAnalyzer The component that decides what action to take
 */
interface UiActionExecutor {

  /**
   * Executes a UI action on the connected device.
   *
   * @param toolName The name of the tool to execute (e.g., "tapOnElementByNodeId", "swipe")
   * @param args The tool arguments as a JSON object
   * @param traceId Optional trace ID for correlation in logs and metrics
   * @return Execution result indicating success or failure
   */
  suspend fun execute(
    toolName: String,
    args: JsonObject,
    traceId: TraceId?,
  ): ExecutionResult

  /**
   * Captures the current screen state from the connected device.
   *
   * This includes:
   * - Screenshot (if available)
   * - View hierarchy
   * - Device dimensions
   * - Platform information
   *
   * @return Current screen state, or null if capture failed (e.g., no device connected)
   */
  suspend fun captureScreenState(): ScreenState?
}
