package xyz.block.trailblaze.mcp.executor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Result of executing a tool via [McpToolExecutor].
 */
@Serializable
sealed interface ToolExecutionResult {
  /** Tool executed successfully. */
  @Serializable
  data class Success(
    val output: String,
    val toolName: String,
  ) : ToolExecutionResult

  /** Tool execution failed. */
  @Serializable
  data class Failure(
    val error: String,
    val toolName: String,
  ) : ToolExecutionResult

  /** Tool not found. */
  @Serializable
  data class ToolNotFound(
    val requestedTool: String,
    val availableTools: List<String>,
  ) : ToolExecutionResult
}

/**
 * Abstraction for executing MCP tools by name.
 *
 * This enables the Koog agent to call tools without knowing whether they're
 * executed directly (in-process) or via MCP network protocol.
 *
 * Implementations:
 * - [DirectMcpToolExecutor]: In-process execution via [TrailblazeMcpBridge]
 * - (Future) RemoteMcpToolExecutor: HTTP execution to external MCP servers
 */
interface McpToolExecutor {
  /**
   * Executes a tool by name with the given arguments.
   *
   * @param toolName The tool name (e.g., "tapOnPoint", "swipe", "inputText")
   * @param args JSON object containing tool arguments
   * @return Execution result with output or error
   */
  suspend fun executeToolByName(
    toolName: String,
    args: JsonObject,
  ): ToolExecutionResult

  /**
   * Returns descriptors for all available tools.
   * Used by the Koog agent to know what tools it can call.
   */
  fun getAvailableTools(): List<TrailblazeToolDescriptor>

  /**
   * Returns tool names only (for quick lookup).
   */
  fun getAvailableToolNames(): Set<String> = getAvailableTools().map { it.name }.toSet()

  /**
   * Checks if a tool is available.
   */
  fun isToolAvailable(toolName: String): Boolean = toolName in getAvailableToolNames()
}
