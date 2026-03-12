package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.reflect.KClass

/**
 * Central registry for all available tools across all providers.
 *
 * Aggregates tools from multiple sources:
 * - Kotlin-registered tools (via [TrailblazeToolRepo])
 * - MCP-discovered tools
 * - File-based or runtime-registered dynamic tools
 *
 * This is the single source of truth for "what tools are available" and
 * supports dynamic grouping via [TrailblazeToolSet].
 */
class TrailblazeToolRegistry {

  private val kotlinToolClasses = mutableSetOf<KClass<out TrailblazeTool>>()
  private val dynamicTools = mutableMapOf<ToolName, TrailblazeToolDescriptor>()
  private val toolSets = mutableListOf<TrailblazeToolSet>()

  /** Imports all tools from an existing [TrailblazeToolRepo]. */
  fun registerKotlinTools(repo: TrailblazeToolRepo) {
    kotlinToolClasses.addAll(repo.getRegisteredTrailblazeTools())
  }

  /** Registers a dynamic tool descriptor (e.g., from MCP discovery). */
  fun registerDynamicTool(name: ToolName, descriptor: TrailblazeToolDescriptor) {
    dynamicTools[name] = descriptor
  }

  /** Registers a tool set for dynamic grouping. */
  fun registerToolSet(toolSet: TrailblazeToolSet) {
    toolSets.add(toolSet)
    kotlinToolClasses.addAll(toolSet.asTools())
  }

  /** Returns all registered Kotlin tool classes. */
  fun getKotlinToolClasses(): Set<KClass<out TrailblazeTool>> = kotlinToolClasses.toSet()

  /** Returns all dynamic tool descriptors. */
  fun getDynamicToolDescriptors(): Map<ToolName, TrailblazeToolDescriptor> = dynamicTools.toMap()

  /** Returns all registered tool sets. */
  fun getToolSets(): List<TrailblazeToolSet> = toolSets.toList()

  /** Returns tool names from all sources. */
  fun getAvailableToolNames(): Set<ToolName> = buildSet {
    kotlinToolClasses.forEach { add(it.toolName()) }
    addAll(dynamicTools.keys)
  }

  /**
   * Returns tool sets that support the given driver type.
   * Tool sets with null supportedDriverTypes are considered universal.
   */
  fun getToolSetsForDriver(driverType: TrailblazeDriverType): List<TrailblazeToolSet> =
    toolSets.filter { it.supportedDriverTypes?.contains(driverType) != false }
}
