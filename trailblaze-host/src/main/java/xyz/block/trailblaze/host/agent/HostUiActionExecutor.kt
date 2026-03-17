package xyz.block.trailblaze.host.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.ConfigTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Implementation of [UiActionExecutor] that uses a host [TrailblazeAgent] to execute UI actions.
 *
 * This executor bridges between the two-tier agent architecture (used by MultiAgentV3Runner)
 * and the existing host Maestro infrastructure. It converts tool name + JSON args into
 * [TrailblazeTool] instances and executes them via the agent's `runTrailblazeTools()`.
 *
 * @param agent The host Maestro TrailblazeAgent for device communication
 * @param screenStateProvider Provider for capturing current screen state
 * @param toolRepo Tool repository for deserialization
 * @param elementComparator Element comparator for tool execution
 *
 * @see UiActionExecutor
 * @see ExecutionResult
 */
class HostUiActionExecutor(
  private val agent: TrailblazeAgent,
  private val screenStateProvider: () -> ScreenState,
  private val toolRepo: TrailblazeToolRepo,
  private val elementComparator: TrailblazeElementComparator,
) : UiActionExecutor {

  override suspend fun execute(
    toolName: String,
    args: JsonObject,
    traceId: TraceId?,
  ): ExecutionResult {
    val startTime = System.currentTimeMillis()

    return try {
      val (tool, deserializationError) = mapToTrailblazeTool(toolName, args)
      if (tool == null) {
        return ExecutionResult.Failure(
          error = deserializationError ?: "Unknown tool: $toolName",
          recoverable = true, // Let the planner retry with a different tool/args
        )
      }

      // Intercept config tools (e.g. setActiveToolSets) that modify the agent's
      // available tool set. These operate on the TrailblazeToolRepo, not the device.
      if (tool is ConfigTrailblazeTool) {
        val configResult = tool.execute(toolRepo)
        Console.log("[HostUiActionExecutor] ConfigTool $toolName: $configResult")
        val durationMs = System.currentTimeMillis() - startTime
        return when (configResult) {
          is TrailblazeToolResult.Success -> ExecutionResult.Success(
            screenSummaryAfter = "Config tool executed",
            durationMs = durationMs,
          )
          is TrailblazeToolResult.Error -> ExecutionResult.Failure(
            error = configResult.errorMessage,
            recoverable = true,
          )
        }
      }

      val screenState = screenStateProvider()
      val result = agent.runTrailblazeTools(
        tools = listOf(tool),
        traceId = traceId,
        screenState = screenState,
        elementComparator = elementComparator,
        screenStateProvider = screenStateProvider,
      )

      val durationMs = System.currentTimeMillis() - startTime

      when (result.result) {
        is TrailblazeToolResult.Success -> {
          val screenSummary = try {
            val newScreenState = screenStateProvider()
            "Screen ${newScreenState.deviceWidth}x${newScreenState.deviceHeight} on ${newScreenState.trailblazeDevicePlatform.name}"
          } catch (e: Exception) {
            "Screen capture failed: ${e.message}"
          }
          ExecutionResult.Success(
            screenSummaryAfter = screenSummary,
            durationMs = durationMs,
          )
        }
        is TrailblazeToolResult.Error -> {
          ExecutionResult.Failure(
            error = (result.result as TrailblazeToolResult.Error).errorMessage,
            recoverable = true,
          )
        }
      }
    } catch (e: Exception) {
      ExecutionResult.Failure(
        error = "Execution failed: ${e.message}",
        recoverable = true,
      )
    }
  }

  override suspend fun captureScreenState(): ScreenState? {
    return try {
      screenStateProvider()
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Maps a tool name and JSON args to a [TrailblazeTool].
   *
   * Uses [TrailblazeToolRepo.toolCallToTrailblazeTool] which looks up the tool class
   * from registered tools and uses the class-specific serializer. This avoids the
   * polymorphic [TrailblazeTool] serializer which can silently fall back to
   * [OtherTrailblazeTool] when typed deserialization fails (e.g., type coercion
   * issues with Long/Int fields from string-typed LLM responses).
   *
   * Returns the tool on success, or a Pair(null, errorMessage) on failure.
   */
  private fun mapToTrailblazeTool(toolName: String, args: JsonObject): Pair<TrailblazeTool?, String?> {
    return try {
      val normalizedArgs = normalizeArgs(toolName, args)
      Pair(toolRepo.toolCallToTrailblazeTool(toolName, normalizedArgs.toString()), null)
    } catch (e: Exception) {
      Console.log("[HostUiActionExecutor] Failed to deserialize tool '$toolName' via toolRepo: ${e.message}")
      Pair(null, "Tool '$toolName' deserialization failed: ${e.message}")
    }
  }

  /**
   * Normalizes tool arguments to handle common LLM output variations.
   */
  private fun normalizeArgs(toolName: String, args: JsonObject): JsonObject {
    if (args.isEmpty()) return args

    val normalized = args.toMutableMap()

    when (toolName) {
      "launchApp" -> {
        if (!normalized.containsKey("appId")) {
          val packageName = normalized["packageName"]
          if (packageName is JsonPrimitive) {
            normalized["appId"] = packageName
          }
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
      "pressKey" -> {
        val keyCode = normalized["keyCode"]
        if (keyCode is JsonPrimitive) {
          normalized["keyCode"] = JsonPrimitive(keyCode.content.uppercase())
        }
      }
    }

    return JsonObject(normalized)
  }
}
