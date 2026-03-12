package xyz.block.trailblaze.mcp.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.utils.ScreenStateCaptureUtil
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console

/**
 * Implementation of [UiActionExecutor] that uses [TrailblazeMcpBridge] to execute UI actions.
 *
 * This executor bridges between the two-tier agent architecture and the existing
 * Trailblaze infrastructure. It converts tool name + JSON args into TrailblazeTool
 * instances and executes them via the MCP bridge.
 *
 * ## Usage
 *
 * ```kotlin
 * val executor = BridgeUiActionExecutor(mcpBridge)
 *
 * val result = executor.execute(
 *   toolName = "tapOnElementByNodeId",
 *   args = buildJsonObject { put("nodeId", "login_button") },
 *   traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
 * )
 * ```
 *
 * @param mcpBridge The MCP bridge for device communication
 *
 * @see UiActionExecutor
 * @see ExecutionResult
 */
class BridgeUiActionExecutor(
  private val mcpBridge: TrailblazeMcpBridge,
) : UiActionExecutor {

  /**
   * Executes a UI action on the connected device.
   *
   * Converts the tool name and arguments to a [TrailblazeTool] and executes
   * it via the MCP bridge. Success/failure is determined by whether the
   * bridge throws an exception.
   *
   * @param toolName The name of the tool to execute (e.g., "tapOnElementByNodeId")
   * @param args The tool arguments as a JSON object
   * @param traceId Optional trace ID for logging correlation
   * @return Execution result indicating success or failure
   */
  override suspend fun execute(
    toolName: String,
    args: JsonObject,
    traceId: TraceId?,
  ): ExecutionResult {
    val startTime = System.currentTimeMillis()

    return try {
      // Validate launchApp before executing
      if (toolName == "launchApp") {
        val validationError = validateLaunchApp(args)
        if (validationError != null) {
          return ExecutionResult.Failure(
            error = validationError,
            recoverable = false,
          )
        }
      }

      // Convert tool name + args to TrailblazeTool
      val tool = mapToTrailblazeTool(toolName, args)
        ?: return ExecutionResult.Failure(
          error = "Unknown tool: $toolName",
          recoverable = false,
        )

      // Execute via MCP bridge - returns String result
      // Throws exception on failure
      val bridgeResult = mcpBridge.executeTrailblazeTool(tool)
      Console.log("[BridgeUiActionExecutor] Executed $toolName: $bridgeResult")

      val durationMs = System.currentTimeMillis() - startTime

      // If no exception was thrown, execution was successful
      // Capture screen state after action for summary
      val screenSummary = try {
        val screenState = captureScreenState()
        screenState?.let { describeScreen(it) } ?: "Screen state not available"
      } catch (e: Exception) {
        "Screen capture failed: ${e.message}"
      }

      ExecutionResult.Success(
        screenSummaryAfter = screenSummary,
        durationMs = durationMs,
      )
    } catch (e: Exception) {
      Console.log("[BridgeUiActionExecutor] Exception executing $toolName: ${e.message}")
      ExecutionResult.Failure(
        error = "Execution failed: ${e.message}",
        recoverable = true, // Most errors are recoverable
      )
    }
  }

  /**
   * Captures the current screen state from the connected device.
   *
   * @return Current screen state, or null if capture failed
   */
  override suspend fun captureScreenState(): ScreenState? {
    return try {
      ScreenStateCaptureUtil.captureScreenState(mcpBridge)
    } catch (e: Exception) {
      Console.log("[BridgeUiActionExecutor] Failed to capture screen state: ${e.message}")
      null
    }
  }

  /**
   * Maps a tool name and JSON args to a TrailblazeTool using the existing JSON
   * deserialization infrastructure.
   *
   * Uses "toolName" field which is recognized by OtherTrailblazeToolSerializer.
   */
  private fun mapToTrailblazeTool(toolName: String, args: JsonObject): TrailblazeTool? {
    return try {
      val normalizedArgs = normalizeArgs(toolName, args)
      // OtherTrailblazeToolSerializer looks for "toolName" to match tool classes
      val toolJson = buildJsonObject {
        put("toolName", toolName)
        normalizedArgs.entries.forEach { (key, value) ->
          put(key, value)
        }
      }

      val tool = TrailblazeJsonInstance.decodeFromString<TrailblazeTool>(toolJson.toString())

      // Guard against silent fallback to OtherTrailblazeTool — the polymorphic serializer
      // catches typed deserialization failures (e.g., Long/Int type coercion from LLM string
      // responses) and falls back to OtherTrailblazeTool with the correct toolName but
      // potentially wrong/empty params. Reject this so the error surfaces instead of
      // executing the wrong action.
      if (tool is OtherTrailblazeTool) {
        Console.log("[BridgeUiActionExecutor] Warning: tool '$toolName' deserialized as OtherTrailblazeTool — typed serializer likely failed. Args: $normalizedArgs")
        return null
      }

      tool
    } catch (e: Exception) {
      Console.log("[BridgeUiActionExecutor] Failed to deserialize tool '$toolName': ${e.message}")
      null
    }
  }

  /**
   * Normalizes tool arguments to handle common LLM output variations.
   *
   * Handles the following normalizations:
   * - **launchApp**: Maps `packageName` → `appId`, forces `RESUME` mode for iOS system apps
   * - **swipe/scrollUntilTextIsVisible**: Uppercases `direction` enum values
   *
   * This allows the inner LLM agent to output slightly variant forms
   * without failing at deserialization time.
   *
   * @param toolName The tool being executed
   * @param args The raw arguments from the LLM
   * @return Normalized arguments ready for tool deserialization
   */
  private fun normalizeArgs(toolName: String, args: JsonObject): JsonObject {
    if (args.isEmpty()) return args

    val normalized = args.toMutableMap()

    when (toolName) {
      "launchApp" -> {
        // Normalize packageName -> appId
        if (!normalized.containsKey("appId")) {
          val packageName = normalized["packageName"]
          if (packageName is JsonPrimitive) {
            normalized["appId"] = packageName
          }
        }

        // For iOS system apps (com.apple.*), force RESUME mode instead of REINSTALL
        // System apps cannot be uninstalled, which causes REINSTALL to fail
        val appId = (normalized["appId"] as? JsonPrimitive)?.contentOrNull
        val launchMode = (normalized["launchMode"] as? JsonPrimitive)?.contentOrNull
        if (appId != null && isIosSystemApp(appId) && (launchMode == null || launchMode == "REINSTALL")) {
          normalized["launchMode"] = JsonPrimitive("RESUME")
          Console.log("[BridgeUiActionExecutor] System app '$appId' detected - using RESUME instead of REINSTALL")
        }
      }
      "swipe",
      "scrollUntilTextIsVisible",
      -> {
        val direction = normalized["direction"]
        if (direction is JsonPrimitive) {
          normalized["direction"] = JsonPrimitive(direction.content.uppercase())
        }
      }
    }

    return JsonObject(normalized)
  }

  /**
   * Checks if an app ID is an iOS system app.
   * System apps cannot be uninstalled/reinstalled.
   */
  private fun isIosSystemApp(appId: String): Boolean {
    return appId.startsWith("com.apple.")
  }

  /**
   * Validates a launchApp request before execution.
   *
   * Checks:
   * 1. App ID is provided
   * 2. App is installed on the device (for non-system apps)
   *
   * @return Error message if validation fails, null if valid
   */
  private suspend fun validateLaunchApp(args: JsonObject): String? {
    val appId = (args["appId"] as? JsonPrimitive)?.contentOrNull
      ?: (args["packageName"] as? JsonPrimitive)?.contentOrNull
      ?: return "launchApp requires 'appId' or 'packageName'"

    // Skip validation for system apps (they're always "installed")
    if (isIosSystemApp(appId)) {
      return null
    }

    // Check if app is installed
    return try {
      val installedApps = mcpBridge.getInstalledAppIds()
      if (!installedApps.contains(appId)) {
        // Include full app list in error so LLM can retry with correct app
        val sortedApps = installedApps.sorted()

        buildString {
          appendLine("App '$appId' is not installed on the device.")
          appendLine()
          appendLine("Installed apps (${sortedApps.size} total):")
          sortedApps.forEach { app ->
            appendLine("  - $app")
          }
          appendLine()
          append("Please retry with one of the installed app IDs listed above.")
        }
      } else {
        null // Valid
      }
    } catch (e: Exception) {
      // If we can't check, let it through (validation is best-effort)
      Console.log("[BridgeUiActionExecutor] Warning: Could not validate app installation: ${e.message}")
      null
    }
  }

  /**
   * Creates a brief description of the screen state for logging.
   */
  private fun describeScreen(screenState: ScreenState): String {
    return "Screen ${screenState.deviceWidth}x${screenState.deviceHeight} on ${screenState.trailblazeDevicePlatform.name}"
  }
}
