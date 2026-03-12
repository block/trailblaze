package xyz.block.trailblaze.mcp.utils

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toMcpJsonSchemaObject
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * Bridge utility for registering TrailblazeTools as MCP tools.
 *
 * This class provides methods to convert and register TrailblazeTools to an MCP server,
 * enabling MCP clients (like Claude) to act as the agent and directly control devices.
 *
 * Usage:
 * ```kotlin
 * val bridge = TrailblazeToolToMcpBridge(
 *   mcpBridge = myMcpBridge,
 *   sessionContext = sessionContext,
 * )
 *
 * // Register the default device control toolset
 * bridge.registerTrailblazeToolSet(
 *   trailblazeToolSet = TrailblazeToolSet.DeviceControlTrailblazeToolSet,
 *   mcpServer = server,
 *   mcpSessionId = sessionId,
 * )
 * ```
 */
class TrailblazeToolToMcpBridge(
  private val mcpBridge: TrailblazeMcpBridge,
  private val sessionContext: TrailblazeMcpSessionContext? = null,
  private val onProgressToken: ((McpSessionId, RequestId?) -> Unit)? = null,
) {
  /**
   * Tracks the names of TrailblazeTools currently registered with the MCP server.
   * Used to remove stale tools when categories change.
   */
  private val registeredToolNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /**
   * Registers all tools from a [TrailblazeToolSet] as MCP tools.
   * When called via MCP, these tools will execute directly on the connected device.
   */
  fun registerTrailblazeToolSet(
    trailblazeToolSet: TrailblazeToolSet,
    mcpServer: Server,
    mcpSessionId: McpSessionId,
  ) {
    registerTrailblazeTools(
      toolClasses = trailblazeToolSet.toolClasses,
      mcpServer = mcpServer,
      mcpSessionId = mcpSessionId,
    )
  }

  /**
   * Registers specific TrailblazeTool classes as MCP tools.
   * 
   * This method:
   * 1. Removes any previously registered TrailblazeTools that are no longer needed
   * 2. Registers the new set of tools
   * 3. Triggers `tools/list_changed` notification via the SDK
   */
  @OptIn(InternalSerializationApi::class)
  fun registerTrailblazeTools(
    toolClasses: Set<KClass<out TrailblazeTool>>,
    mcpServer: Server,
    mcpSessionId: McpSessionId,
  ) {
    Console.log("Registering ${toolClasses.size} TrailblazeTools as MCP tools")

    // Get the names of tools to be registered
    val newToolNames = toolClasses.mapNotNull { it.toKoogToolDescriptor()?.name }.toSet()

    // Remove old tools that are no longer needed
    val toolsToRemove = registeredToolNames - newToolNames
    if (toolsToRemove.isNotEmpty()) {
      Console.log("Removing ${toolsToRemove.size} stale tools: ${toolsToRemove.joinToString()}")
      mcpServer.removeTools(toolsToRemove.toList())
      registeredToolNames.removeAll(toolsToRemove)
    }

    // Register the new tools
    toolClasses.forEach { toolClass ->
      val descriptor = toolClass.toKoogToolDescriptor()
      if (descriptor == null) {
        Console.log("  Skipping ${toolClass.simpleName} - no tool descriptor")
        return@forEach
      }

      // Build properties JsonObject for the tool parameters
      val properties = buildJsonObject {
        (descriptor.requiredParameters + descriptor.optionalParameters).forEach { param ->
          put(param.name, param.toMcpJsonSchemaObject())
        }
      }

      val required = descriptor.requiredParameters.map { it.name }

      Console.log("Registering MCP tool: ${descriptor.name}")
      Console.log("  Description: ${descriptor.description}")
      Console.log("  Properties: $properties")
      Console.log("  Required: $required")

      // Always provide properties (even if empty) - Goose client expects properties to be present
      val inputSchema = ToolSchema(properties, required)

      mcpServer.addTool(
        name = descriptor.name,
        description = descriptor.description,
        inputSchema = inputSchema,
      ) { request: CallToolRequest ->
        handleToolCall(
          request = request,
          toolClass = toolClass,
          toolName = descriptor.name,
          mcpSessionId = mcpSessionId,
        )
      }
      
      // Track registered tool
      registeredToolNames.add(descriptor.name)
    }
    
    if (newToolNames.isNotEmpty()) {
      Console.log("Registered ${newToolNames.size} TrailblazeTools")
    }
  }

  @OptIn(InternalSerializationApi::class)
  private suspend fun handleToolCall(
    request: CallToolRequest,
    toolClass: KClass<out TrailblazeTool>,
    toolName: String,
    mcpSessionId: McpSessionId,
  ): CallToolResult {
    // Extract MCP progress token from request metadata (_meta.progressToken)
    val progressToken = request.meta?.get("progressToken")?.let { progressTokenValue ->
      when (progressTokenValue) {
        is JsonPrimitive -> {
          val tokenString = progressTokenValue.content
          Console.log("progressToken for session $mcpSessionId = $tokenString")
          RequestId.StringId(tokenString)
        }
        else -> null
      }
    }

    // Notify about progress token (for session context updates)
    onProgressToken?.invoke(mcpSessionId, progressToken)
    sessionContext?.mcpProgressToken = progressToken

    Console.log("MCP Tool Called: $toolName")

    // Convert request arguments to JsonObject
    val argumentsJsonObject = request.arguments ?: JsonObject(emptyMap())
    Console.log("  Arguments JSON: $argumentsJsonObject")

    // Deserialize arguments into the TrailblazeTool instance
    val tool: TrailblazeTool = try {
      @Suppress("UNCHECKED_CAST")
      val serializer = serializer(toolClass.starProjectedType)
      TrailblazeJsonInstance.decodeFromJsonElement(serializer, argumentsJsonObject) as TrailblazeTool
    } catch (e: Exception) {
      Console.error("ERROR deserializing arguments for tool $toolName: ${e.message}")
      e.printStackTrace()
      return CallToolResult(
        content = mutableListOf(
          TextContent("Error deserializing arguments: ${e.message}"),
        ),
        isError = true,
      )
    }

    Console.log("Executing TrailblazeTool: $toolName")
    Console.log("  Tool instance: $tool")

    // Execute tool via the bridge (which delegates to the device)
    return try {
      val result = withContext(Dispatchers.IO) {
        mcpBridge.executeTrailblazeTool(
          tool = tool,
        )
      }
      Console.log("Tool result: $result")

      // Format result using standardized format
      val formattedResult = result.toToolResult(success = true)

      // Build response with optional auto-screenshot
      val contentBuilder = McpContentBuilder(sessionContext)
        .addText(formattedResult.format())

      // Add screenshot if auto-include is enabled
      if (sessionContext?.autoIncludeScreenshotAfterAction == true) {
        val screenState = mcpBridge.getCurrentScreenState()
        contentBuilder.addScreenshot(screenState?.screenshotBytes)
      }

      CallToolResult(
        content = contentBuilder.build(),
        isError = false,
      )
    } catch (e: Exception) {
      Console.error("ERROR executing tool $toolName: ${e.message}")
      e.printStackTrace()

      val errorResult = ToolResultSummary.failure(
        action = "Failed to execute $toolName",
        reason = e.message ?: "Unknown error",
        nextHint = "Check device connection and try again",
      )

      CallToolResult(
        content = mutableListOf(TextContent(errorResult.format())),
        isError = true,
      )
    }
  }
}
