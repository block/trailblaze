package xyz.block.trailblaze.mcp.executor

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.toolcalls.KoogToolExt
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
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

  /**
   * Repo that resolves a (toolName, argsJson) pair to a concrete [TrailblazeTool]. Built
   * lazily from [availableTools] — [ToolSetCategoryMapping.resolve] can be expensive on first
   * access, so we share that work between [toolDescriptors] and this repo.
   */
  private val toolRepo: TrailblazeToolRepo by lazy {
    TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "DirectMcpToolExecutor",
        toolClasses = availableToolClasses,
        yamlToolNames = availableYamlToolNames,
      ),
    )
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
   * Deserializes a tool from (name, args) by routing through [toolRepo]. The repo
   * dispatches on toolName to the matching class-backed or YAML-defined serializer,
   * decoding the flat args object directly — no `toolName`/`raw` wrapping involved.
   */
  private fun deserializeTool(toolName: String, args: JsonObject): TrailblazeTool =
    toolRepo.toolCallToTrailblazeTool(toolName, args.toString())
}
