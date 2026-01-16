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
   */
  @OptIn(InternalSerializationApi::class)
  fun registerTrailblazeTools(
    toolClasses: Set<KClass<out TrailblazeTool>>,
    mcpServer: Server,
    mcpSessionId: McpSessionId,
  ) {
    println("Registering ${toolClasses.size} TrailblazeTools as MCP tools")

    toolClasses.forEach { toolClass ->
      val descriptor = toolClass.toKoogToolDescriptor()
      if (descriptor == null) {
        println("  Skipping ${toolClass.simpleName} - no tool descriptor")
        return@forEach
      }

      // Build properties JsonObject for the tool parameters
      val properties = buildJsonObject {
        (descriptor.requiredParameters + descriptor.optionalParameters).forEach { param ->
          put(param.name, param.toMcpJsonSchemaObject())
        }
      }

      val required = descriptor.requiredParameters.map { it.name }

      println("Registering MCP tool: ${descriptor.name}")
      println("  Description: ${descriptor.description}")
      println("  Properties: $properties")
      println("  Required: $required")

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
    }
  }

  @OptIn(InternalSerializationApi::class)
  private suspend fun handleToolCall(
    request: CallToolRequest,
    toolClass: KClass<out TrailblazeTool>,
    toolName: String,
    mcpSessionId: McpSessionId,
  ): CallToolResult {
    // Extract progress token from request metadata
    val progressToken = request.meta?.get("progressToken")?.let { progressTokenValue ->
      when (progressTokenValue) {
        is JsonPrimitive -> {
          val tokenString = progressTokenValue.content
          println("progressToken for session $mcpSessionId = $tokenString")
          RequestId.StringId(tokenString)
        }

        else -> null
      }
    }

    // Notify about progress token (for session context updates)
    onProgressToken?.invoke(mcpSessionId, progressToken)
    sessionContext?.progressToken = progressToken

    println("MCP Tool Called: $toolName")

    // Convert request arguments to JsonObject
    val argumentsJsonObject = request.arguments ?: JsonObject(emptyMap())
    println("  Arguments JSON: $argumentsJsonObject")

    // Deserialize arguments into the TrailblazeTool instance
    val tool: TrailblazeTool = try {
      @Suppress("UNCHECKED_CAST")
      val serializer = serializer(toolClass.starProjectedType)
      TrailblazeJsonInstance.decodeFromJsonElement(serializer, argumentsJsonObject) as TrailblazeTool
    } catch (e: Exception) {
      println("ERROR deserializing arguments for tool $toolName: ${e.message}")
      e.printStackTrace()
      return CallToolResult(
        content = mutableListOf(
          TextContent("Error deserializing arguments: ${e.message}"),
        ),
        isError = true,
      )
    }

    println("Executing TrailblazeTool: $toolName")
    println("  Tool instance: $tool")

    // Execute tool via the bridge (which delegates to the device)
    return try {
      val result = withContext(Dispatchers.IO) {
        mcpBridge.executeTrailblazeTool(
          tool = tool,
        )
      }
      println("Tool result: $result")

      CallToolResult(
        content = mutableListOf(
          TextContent(result),
        ),
        isError = false,  // Explicitly set to false for success (some MCP clients require this)
      )
    } catch (e: Exception) {
      println("ERROR executing tool $toolName: ${e.message}")
      e.printStackTrace()
      CallToolResult(
        content = mutableListOf(
          TextContent("Error executing tool: ${e.message}"),
        ),
        isError = true,
      )
    }
  }
}
