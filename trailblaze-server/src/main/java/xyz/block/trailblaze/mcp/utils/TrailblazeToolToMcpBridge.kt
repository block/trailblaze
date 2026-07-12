package xyz.block.trailblaze.mcp.utils

import ai.koog.agents.core.tools.ToolDescriptor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toMcpJsonSchemaObject
import xyz.block.trailblaze.mcp.utils.filterNonNullableRequired
import xyz.block.trailblaze.mcp.utils.simplifyNullableAnyOf
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * Thrown by a descriptor-tool executor when the incoming MCP `tools/call` arguments fail
 * schema validation (unknown keys, missing required keys, type mismatches, unknown tool).
 * [TrailblazeToolToMcpBridge] surfaces the message as a clean MCP error result — no stack
 * trace, no raw Kotlin exception text beyond the validator's directed message.
 */
class McpToolArgumentValidationException(message: String) : Exception(message)

/**
 * Thrown by a descriptor-tool executor when the tool itself reports a failure
 * ([xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error] — script threw, assertion
 * failed, element not found). Distinct from [McpToolArgumentValidationException] (bad
 * arguments) and from dispatch/transport exceptions (device unreachable) so
 * [TrailblazeToolToMcpBridge] can surface the tool's own failure message without the
 * misleading "check device connection" hint.
 */
class McpToolExecutionException(message: String) : Exception(message)

/**
 * MCP `inputSchema` for a [TrailblazeToolDescriptor].
 *
 * Scripted (`.ts`) tools carry their full JSON Schema on [TrailblazeToolDescriptor.inputSchema]
 * (a body property — NOT carried by `copy()`, so callers must not have copied the descriptor) —
 * use it verbatim so nested object/array shapes survive. Descriptors without a schema
 * (YAML-defined tools) fall back to the flat parameter view via [toMcpToolSchema] on the Koog
 * descriptor — the same conversion class-backed registration uses.
 */
internal fun TrailblazeToolDescriptor.toMcpToolSchema(): ToolSchema {
  val schema = inputSchema
  if (schema != null) {
    val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (schema["required"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?: emptyList()
    return ToolSchema(properties = properties, required = required)
  }
  return toKoogToolDescriptor(strict = false).toMcpToolSchema()
}

/**
 * Flat Koog parameter view → MCP [ToolSchema], shared by class-backed registration and the
 * no-schema descriptor fallback.
 *
 * Koog wraps nullable Kotlin types as `anyOf: [{type:"null"}, {type:"string"}]`, which is
 * valid JSON Schema but rejected by clients expecting a top-level `type` field
 * (e.g. Codex: `Failed to convert MCP tool: Error("missing field 'type'")` —
 * https://github.com/openai/codex/issues/1973, https://github.com/JetBrains/koog/issues/642).
 * [simplifyNullableAnyOf] strips the null leg; nullable params are then also removed from
 * `required` (via [filterNonNullableRequired]) so clients don't reject requests that omit them.
 */
internal fun ToolDescriptor.toMcpToolSchema(): ToolSchema {
  val properties = buildJsonObject {
    (requiredParameters + optionalParameters).forEach { param ->
      put(param.name, param.toMcpJsonSchemaObject().simplifyNullableAnyOf())
    }
  }
  // Always provide properties (even if empty) - Goose client expects properties to be present
  return ToolSchema(
    properties = properties,
    required = requiredParameters.filterNonNullableRequired(),
  )
}

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
  private companion object {
    /** Cap on argument-payload characters written to the daemon log; see [argumentsLogPreview]. */
    const val MAX_ARG_LOG_CHARS = 500
  }

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

      val inputSchema = descriptor.toMcpToolSchema()

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
    
    // One summary line per registration pass, not per tool: registerTools re-runs on every
    // device-connect / target-switch / mode-change, so per-tool schema dumps flood the daemon log.
    if (newToolNames.isNotEmpty()) {
      Console.log("Registered ${newToolNames.size} TrailblazeTools: ${newToolNames.sorted().joinToString()}")
    }
  }

  /**
   * Registers descriptor-backed tools (scripted `.ts` tools and YAML-defined tools — the two
   * backings without a Kotlin [KClass]) as first-class MCP tools, with their real JSON Schemas.
   *
   * Execution routes through [executeDescriptorTool] — the caller supplies the dispatch (schema
   * validation + name→tool resolution + the same host-local/bridge routing recorded replay uses).
   * A [McpToolArgumentValidationException] thrown by the executor surfaces as a clean MCP error
   * result naming the violation; any other exception gets the standard failure envelope.
   */
  fun registerTrailblazeToolDescriptors(
    descriptors: List<TrailblazeToolDescriptor>,
    mcpServer: Server,
    mcpSessionId: McpSessionId,
    executeDescriptorTool: suspend (toolName: String, arguments: JsonObject) -> String,
  ) {
    descriptors.forEach { descriptor ->
      val inputSchema = descriptor.toMcpToolSchema()
      mcpServer.addTool(
        name = descriptor.name,
        description = descriptor.description ?: "",
        inputSchema = inputSchema,
      ) { request: CallToolRequest ->
        handleDescriptorToolCall(
          request = request,
          toolName = descriptor.name,
          mcpSessionId = mcpSessionId,
          executeDescriptorTool = executeDescriptorTool,
        )
      }
      registeredToolNames.add(descriptor.name)
    }
    if (descriptors.isNotEmpty()) {
      Console.log(
        "Registered ${descriptors.size} descriptor-backed TrailblazeTools: " +
          descriptors.joinToString { it.name },
      )
    }
  }

  private suspend fun handleDescriptorToolCall(
    request: CallToolRequest,
    toolName: String,
    mcpSessionId: McpSessionId,
    executeDescriptorTool: suspend (toolName: String, arguments: JsonObject) -> String,
  ): CallToolResult {
    extractAndPublishProgressToken(request, mcpSessionId)
    val argumentsJsonObject = request.arguments ?: JsonObject(emptyMap())
    Console.log("MCP Tool Called (descriptor-backed): $toolName")
    Console.log("  Arguments JSON: ${argumentsLogPreview(argumentsJsonObject)}")

    return try {
      val result = withContext(Dispatchers.IO) {
        executeDescriptorTool(toolName, argumentsJsonObject)
      }
      buildSuccessResult(result)
    } catch (e: CancellationException) {
      // Request cancelled / client disconnected — propagate so the coroutine actually
      // cancels instead of being reported as a tool failure.
      throw e
    } catch (e: McpToolArgumentValidationException) {
      // Contract violation in the incoming arguments — the validator's message is the whole
      // story; a stack trace would just bury it.
      Console.log("MCP tool $toolName rejected: ${e.message}")
      ToolResultSummary.failure(
        action = "Invalid arguments for $toolName",
        reason = e.message ?: "Arguments did not match the tool's inputSchema",
        nextHint = "Fix the arguments to match the tool's inputSchema and retry",
      ).toCallToolResult()
    } catch (e: McpToolExecutionException) {
      // The tool itself reported a failure (TrailblazeToolResult.Error) — its message is the
      // signal; a "check device connection" hint would misdirect (the dispatch worked).
      Console.log("MCP tool $toolName reported a failure: ${e.message}")
      ToolResultSummary.failure(
        action = "Tool $toolName reported a failure",
        reason = e.message ?: "Tool execution failed",
        nextHint = "Review the tool's failure message and fix the underlying condition before retrying",
      ).toCallToolResult()
    } catch (e: Exception) {
      Console.error("ERROR executing tool $toolName: ${e.message}")
      e.printStackTrace()
      ToolResultSummary.failure(
        action = "Failed to execute $toolName",
        reason = e.message ?: "Unknown error",
        nextHint = "Check device connection and try again",
      ).toCallToolResult()
    }
  }

  /**
   * Bounded, single-line preview of tool-call arguments for the daemon log. Tool arguments can
   * carry secrets (a signed-in launch tool's credentials, tokens), so the full payload is never
   * logged — capped at [MAX_ARG_LOG_CHARS], matching the class-backed Koog dispatch path.
   */
  private fun argumentsLogPreview(arguments: JsonObject): String {
    val text = arguments.toString()
    return if (text.length > MAX_ARG_LOG_CHARS) {
      "${text.take(MAX_ARG_LOG_CHARS)}…(${text.length} chars)"
    } else {
      text
    }
  }

  /**
   * Lifts the MCP progress token off the request metadata (`_meta.progressToken`) and publishes
   * it to the session context + [onProgressToken] hook. Shared by the class-backed and
   * descriptor-backed call handlers.
   */
  private fun extractAndPublishProgressToken(
    request: CallToolRequest,
    mcpSessionId: McpSessionId,
  ): RequestId? {
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
    onProgressToken?.invoke(mcpSessionId, progressToken)
    sessionContext?.mcpProgressToken = progressToken
    return progressToken
  }

  /** Success envelope + optional auto-screenshot, shared by both call handlers. */
  private suspend fun buildSuccessResult(result: String): CallToolResult {
    val contentBuilder = McpContentBuilder(sessionContext)
      .addText(result.toToolResult(success = true).format())
    if (sessionContext?.autoIncludeScreenshotAfterAction == true) {
      val screenState = mcpBridge.getCurrentScreenState()
      contentBuilder.addScreenshot(screenState?.screenshotBytes)
    }
    return CallToolResult(
      content = contentBuilder.build(),
      isError = false,
    )
  }

  @OptIn(InternalSerializationApi::class)
  private suspend fun handleToolCall(
    request: CallToolRequest,
    toolClass: KClass<out TrailblazeTool>,
    toolName: String,
    mcpSessionId: McpSessionId,
  ): CallToolResult {
    extractAndPublishProgressToken(request, mcpSessionId)

    Console.log("MCP Tool Called: $toolName")

    // Convert request arguments to JsonObject
    val argumentsJsonObject = request.arguments ?: JsonObject(emptyMap())
    Console.log("  Arguments JSON: ${argumentsLogPreview(argumentsJsonObject)}")

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

      buildSuccessResult(result)
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
