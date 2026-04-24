package xyz.block.trailblaze.mcp.executor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.KoogToolExt
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.reflect.KClass

/**
 * Executes TrailblazeTools directly via the bridge, without MCP network round-trip.
 *
 * This is the optimized path for the self-connection pattern - the Koog agent
 * calls tools through this executor, which resolves and executes them in-process.
 *
 * @param mcpBridge Bridge to the device manager for tool execution
 * @param categories Tool categories to include (defaults to CORE_INTERACTION + NAVIGATION)
 */
class DirectMcpToolExecutor(
  private val mcpBridge: TrailblazeMcpBridge,
  private val categories: Set<ToolSetCategory> =
    setOf(
      ToolSetCategory.CORE_INTERACTION,
      ToolSetCategory.NAVIGATION,
    ),
) : McpToolExecutor {

  /**
   * Combined class-backed + YAML-defined tool surface for the configured categories. Routed
   * through [ToolSetCategoryMapping.resolve] so we can't accidentally advertise only one
   * half — catalog entries like `navigation` include YAML-only tools (e.g. `pressBack`)
   * that MCP must handle alongside class-backed tools.
   */
  private val availableTools by lazy { ToolSetCategoryMapping.resolve(categories) }
  private val availableToolClasses: Set<KClass<out TrailblazeTool>> get() = availableTools.toolClasses
  private val availableYamlToolNames: Set<ToolName> get() = availableTools.yamlToolNames

  /** Map of tool name -> tool class for lookup */
  private val toolClassByName: Map<String, KClass<out TrailblazeTool>> by lazy {
    availableToolClasses.associateBy { it.toolName().toolName }
  }

  /** Cached tool descriptors (class-backed + YAML-defined). */
  private val toolDescriptors: List<TrailblazeToolDescriptor> by lazy {
    val classDescriptors = availableToolClasses.mapNotNull { toolClass ->
      toolClass.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()
    }
    val yamlDescriptors = KoogToolExt.buildDescriptorsForYamlDefined(availableYamlToolNames)
      .map { it.toTrailblazeToolDescriptor() }
    classDescriptors + yamlDescriptors
  }

  /**
   * All tool names the executor will accept. Derived from [toolDescriptors] so the
   * advertised surface and the acceptance gate are guaranteed to match — if
   * [KoogToolExt.buildDescriptorsForYamlDefined] skips a malformed YAML config (it logs a
   * warning and returns null), that name will also not pass the `ToolNotFound` check.
   */
  private val knownToolNames: Set<String> by lazy {
    toolDescriptors.map { it.name }.toSet()
  }

  override suspend fun executeToolByName(
    toolName: String,
    args: JsonObject,
  ): ToolExecutionResult {
    if (toolName !in knownToolNames) {
      return ToolExecutionResult.ToolNotFound(
        requestedTool = toolName,
        availableTools = knownToolNames.toList(),
      )
    }

    return try {
      val tool = deserializeTool(toolName, args)
      val output = mcpBridge.executeTrailblazeTool(tool)
      ToolExecutionResult.Success(
        output = output,
        toolName = toolName,
      )
    } catch (e: Exception) {
      ToolExecutionResult.Failure(
        error = "Failed to execute '$toolName': ${e.message}",
        toolName = toolName,
      )
    }
  }

  override fun getAvailableTools(): List<TrailblazeToolDescriptor> = toolDescriptors

  /**
   * Deserializes a tool from name and args.
   *
   * Routes through [TrailblazeJsonInstance]'s polymorphic tool serializer by stamping `toolName`
   * into the payload. The serializer dispatches on that name to either a class-backed serializer
   * or a pre-bound [xyz.block.trailblaze.config.YamlDefinedToolSerializer], so both tool flavors
   * land here without the executor needing a separate code path per flavor.
   */
  private fun deserializeTool(toolName: String, args: JsonObject): TrailblazeTool {
    // OtherTrailblazeToolSerializer looks for "toolName" to match tool classes by their ToolName
    val mutableMap = args.toMutableMap()
    mutableMap["toolName"] = JsonPrimitive(toolName)
    val toolJson = JsonObject(mutableMap).toString()
    val tool = TrailblazeJsonInstance.decodeFromString<TrailblazeTool>(toolJson)
    // Reject silent fallback to OtherTrailblazeTool — typed deserialization likely failed
    // (e.g., Long/Int type coercion from string-typed LLM responses)
    if (tool is OtherTrailblazeTool) {
      error("Tool '$toolName' deserialized as OtherTrailblazeTool — typed serializer likely failed. Args: $args")
    }
    return tool
  }
}
