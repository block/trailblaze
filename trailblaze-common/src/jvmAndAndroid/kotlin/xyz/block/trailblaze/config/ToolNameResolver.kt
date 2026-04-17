package xyz.block.trailblaze.config

import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * Resolves tool name strings from YAML to [KClass] references at runtime.
 *
 * Built from the union of framework built-in tools and app-specific custom tools.
 * Follows the same pattern as [BuiltInLlmModelRegistry] — a registry of known
 * names populated at startup, then used for on-demand resolution.
 */
class ToolNameResolver(
  private val knownTools: Map<ToolName, KClass<out TrailblazeTool>>,
) {

  /**
   * Resolves a single tool name to its [KClass].
   *
   * @throws IllegalArgumentException if the name is not found
   */
  fun resolve(name: String): KClass<out TrailblazeTool> {
    return knownTools[ToolName(name)]
      ?: throw IllegalArgumentException(
        "Unknown tool name: '$name'. " +
          "Known tools: ${knownTools.keys.map { it.toolName }.sorted().joinToString(", ")}"
      )
  }

  /**
   * Resolves a single tool name, returning null if not found.
   */
  fun resolveOrNull(name: String): KClass<out TrailblazeTool>? = knownTools[ToolName(name)]

  /**
   * Resolves a list of tool names. All must be found.
   */
  fun resolveAll(names: List<String>): Set<KClass<out TrailblazeTool>> =
    names.map { resolve(it) }.toSet()

  /**
   * Resolves a list of tool names leniently — skips unknown names with a warning.
   */
  fun resolveAllLenient(names: List<String>, context: String = ""): Set<KClass<out TrailblazeTool>> =
    names.mapNotNull { name ->
      resolveOrNull(name) ?: run {
        val ctx = if (context.isNotEmpty()) " (in $context)" else ""
        Console.log("Warning: Unknown tool name '$name'$ctx — skipping")
        null
      }
    }.toSet()

  /**
   * Returns a new resolver with additional tool classes merged in.
   */
  fun withAdditionalTools(
    extra: Map<ToolName, KClass<out TrailblazeTool>>,
  ): ToolNameResolver = ToolNameResolver(knownTools + extra)

  companion object {
    /**
     * Creates a resolver from the framework's built-in tools plus custom tools
     * provided by app target companions.
     */
    fun fromBuiltInAndCustomTools(
      customToolClasses: Map<ToolName, KClass<out TrailblazeTool>> = emptyMap(),
    ): ToolNameResolver = ToolNameResolver(
      TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerializationByToolName + customToolClasses,
    )

    /**
     * Creates a resolver from an explicit set of tool classes.
     */
    fun fromToolClasses(
      toolClasses: Set<KClass<out TrailblazeTool>>,
    ): ToolNameResolver = ToolNameResolver(
      toolClasses.associateBy { it.toolName() },
    )
  }
}
