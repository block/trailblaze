package xyz.block.trailblaze.config

import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * Resolves tool name strings from YAML to their backing implementation at runtime.
 *
 * Trailblaze uses a flat global namespace for tool names — a name resolves to exactly
 * one backing implementation, regardless of whether that's a Kotlin [KClass] (`class:` mode
 * YAML config) or a pure-YAML composition (`tools:` mode YAML config). Future TypeScript/QuickJS
 * tools will slot into the same namespace.
 *
 * Toolset YAML files like `core_interaction.yaml` list tools by bare name; this resolver
 * looks each name up across both backings so authors never have to think about whether a tool
 * is class-backed or YAML-defined.
 *
 * Collisions between backings are surfaced at construction time.
 *
 * Follows the same pattern as [BuiltInLlmModelRegistry] — a registry of known names populated
 * at startup, then used for on-demand resolution.
 */
class ToolNameResolver(
  private val knownTools: Map<ToolName, KClass<out TrailblazeTool>>,
  private val knownYamlToolNames: Set<ToolName> = emptySet(),
) {

  init {
    val overlap = knownTools.keys.intersect(knownYamlToolNames)
    require(overlap.isEmpty()) {
      "Tool name collision(s) between class-backed and YAML-defined tools: " +
        "${overlap.map { it.toolName }.sorted()}. Each name must bind to exactly one backing."
    }
  }

  /**
   * Resolves a single tool name to its [KClass].
   *
   * @throws IllegalArgumentException if the name is not found, or is YAML-defined
   *   (use [resolveOrNull] / [resolveYamlNameOrNull] when YAML-defined tools are valid).
   */
  fun resolve(name: String): KClass<out TrailblazeTool> {
    return knownTools[ToolName(name)]
      ?: throw IllegalArgumentException(
        "Unknown tool name: '$name'. " +
          "Known class-backed tools: ${knownTools.keys.map { it.toolName }.sorted().joinToString(", ")}. " +
          "Known YAML-defined tools: ${knownYamlToolNames.map { it.toolName }.sorted().joinToString(", ")}",
      )
  }

  /**
   * Resolves a single tool name to its [KClass], returning null if the name is unknown OR
   * the name refers to a YAML-defined tool.
   */
  fun resolveOrNull(name: String): KClass<out TrailblazeTool>? = knownTools[ToolName(name)]

  /**
   * Returns the typed canonical [ToolName] if [name] refers to a YAML-defined (`tools:` mode)
   * tool, otherwise null. Typed return matches the wrap-at-the-boundary pattern — callers
   * don't re-wrap the raw string after classification.
   */
  fun resolveYamlNameOrNull(name: String): ToolName? =
    ToolName(name).takeIf { it in knownYamlToolNames }

  /**
   * Returns true if [name] is known under either backing.
   */
  fun isKnown(name: String): Boolean =
    ToolName(name) in knownTools.keys || ToolName(name) in knownYamlToolNames

  /**
   * Resolves a list of tool names. All must be class-backed and found.
   */
  fun resolveAll(names: List<String>): Set<KClass<out TrailblazeTool>> =
    names.map { resolve(it) }.toSet()

  /**
   * Resolves a list of tool names leniently — skips unknown names with a warning. Only
   * returns class-backed tools; YAML-defined tools in [names] are silently excluded from the
   * result set. Callers that care about YAML-defined tools should also consult
   * [partitionLenient].
   */
  fun resolveAllLenient(names: List<String>, context: String = ""): Set<KClass<out TrailblazeTool>> =
    partitionLenient(names, context).classBacked

  /**
   * Result of splitting a list of tool names by backing. `yamlDefinedNames` is typed as
   * [ToolName] — the YAML-wire layer (`tools: List<String>` in toolset configs) is the one
   * legitimate place raw strings become typed identifiers; everything downstream of this
   * should stay on [ToolName] to avoid mixing raw-string tool identifiers with typed ones.
   */
  data class Partitioned(
    val classBacked: Set<KClass<out TrailblazeTool>>,
    val yamlDefinedNames: Set<ToolName>,
  )

  /**
   * Splits a list of tool names into class-backed and YAML-defined. Unknown names log a
   * warning and are skipped.
   */
  fun partitionLenient(names: List<String>, context: String = ""): Partitioned {
    val classBacked = mutableSetOf<KClass<out TrailblazeTool>>()
    val yamlNames = mutableSetOf<ToolName>()
    for (name in names) {
      val toolName = ToolName(name)
      val klass = knownTools[toolName]
      if (klass != null) {
        classBacked.add(klass)
        continue
      }
      if (toolName in knownYamlToolNames) {
        yamlNames.add(toolName)
        continue
      }
      val ctx = if (context.isNotEmpty()) " (in $context)" else ""
      Console.log("Warning: Unknown tool name '$name'$ctx — skipping")
    }
    return Partitioned(classBacked = classBacked, yamlDefinedNames = yamlNames)
  }

  /**
   * Returns a new resolver with additional tool classes merged in.
   */
  fun withAdditionalTools(
    extra: Map<ToolName, KClass<out TrailblazeTool>>,
  ): ToolNameResolver = ToolNameResolver(
    knownTools = knownTools + extra,
    knownYamlToolNames = knownYamlToolNames,
  )

  companion object {
    /**
     * Creates a resolver from the framework's classpath-discoverable tools plus custom
     * tools provided by app target companions. Also includes YAML-defined (`tools:` mode)
     * tools so toolset YAMLs can list them by bare name without authors having to know
     * which backing a tool uses.
     */
    fun fromBuiltInAndCustomTools(
      customToolClasses: Map<ToolName, KClass<out TrailblazeTool>> = emptyMap(),
    ): ToolNameResolver = ToolNameResolver(
      knownTools = ToolYamlLoader.discoverAndLoadAll() + customToolClasses,
      knownYamlToolNames = TrailblazeSerializationInitializer.buildYamlDefinedTools().keys,
    )

    /**
     * Creates a resolver from an explicit set of tool classes.
     */
    fun fromToolClasses(
      toolClasses: Set<KClass<out TrailblazeTool>>,
    ): ToolNameResolver = ToolNameResolver(
      knownTools = toolClasses.associateBy { it.toolName() },
    )
  }
}
