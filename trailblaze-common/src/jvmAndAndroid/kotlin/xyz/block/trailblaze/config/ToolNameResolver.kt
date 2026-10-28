package xyz.block.trailblaze.config

import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.bundledConfigResourceSource
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * Resolves tool name strings from YAML to their backing implementation at runtime.
 *
 * Trailblaze uses a flat global namespace for tool names — a name resolves to exactly
 * one backing implementation, regardless of whether that's a Kotlin [KClass] (`class:` YAML
 * config) or a scripted (`.ts` / `.js`) tool declared by a per-trailmap descriptor and discovered
 * via [ScriptedToolNameDiscoverer].
 *
 * Toolset YAML files like `core_interaction.yaml` list tools by bare name; this resolver
 * looks each name up across both backings so authors never have to think about whether a
 * tool is class-backed or scripted. A scripted tool listed in a toolset
 * resolves here so it gets *advertised* like any other tool; its `.ts` is still bundled and
 * dispatched through the per-session scripted-tool machinery (the resolver only owns names).
 *
 * Collisions between backings are surfaced at construction time.
 *
 * Follows the same pattern as [BuiltInLlmModelRegistry] — a registry of known names populated
 * at startup, then used for on-demand resolution.
 */
class ToolNameResolver(
  private val knownTools: Map<ToolName, KClass<out TrailblazeTool>>,
  private val knownScriptedToolNames: Set<ToolName> = emptySet(),
) {

  init {
    // Each name must bind to exactly one backing.
    val classVsScripted = knownTools.keys.intersect(knownScriptedToolNames)
    require(classVsScripted.isEmpty()) {
      "Tool name collision(s) — each name must bind to exactly one backing. " +
        "class-backed vs scripted: ${classVsScripted.map { it.toolName }.sorted()}."
    }
  }

  /**
   * Resolves a single tool name to its [KClass].
   *
   * @throws IllegalArgumentException if the name is not found, or is scripted
   *   (use [resolveOrNull] / [resolveScriptedNameOrNull] when scripted tools are valid).
   */
  fun resolve(name: String): KClass<out TrailblazeTool> {
    return knownTools[ToolName(name)]
      ?: throw IllegalArgumentException(
        "Unknown tool name: '$name'. " +
          "Known class-backed tools: ${knownTools.keys.map { it.toolName }.sorted().joinToString(", ")}",
      )
  }

  /**
   * Resolves a single tool name to its [KClass], returning null if the name is unknown OR
   * the name refers to a scripted tool.
   */
  fun resolveOrNull(name: String): KClass<out TrailblazeTool>? = knownTools[ToolName(name)]

  /**
   * Returns the typed canonical [ToolName] if [name] refers to a scripted (`.ts` / `.js`) tool
   * declared by a per-trailmap descriptor (discovered via [ScriptedToolNameDiscoverer]),
   * otherwise null.
   */
  fun resolveScriptedNameOrNull(name: String): ToolName? =
    ToolName(name).takeIf { it in knownScriptedToolNames }

  /**
   * Returns true if [name] is known under either backing (class-backed or scripted).
   */
  fun isKnown(name: String): Boolean = ToolName(name).let {
    it in knownTools.keys || it in knownScriptedToolNames
  }

  /** Every name [isKnown] accepts, for "did you mean" repair of one it rejected. */
  fun allKnownNames(): Set<String> =
    (knownTools.keys + knownScriptedToolNames).mapTo(mutableSetOf()) { it.toolName }

  /**
   * Resolves a list of tool names. All must be class-backed and found.
   */
  fun resolveAll(names: List<String>): Set<KClass<out TrailblazeTool>> =
    names.map { resolve(it) }.toSet()

  /**
   * Result of splitting a list of tool names by backing. `scriptedToolNames` are typed as
   * [ToolName] — the YAML-wire layer (`tools: List<String>` in toolset configs) is the one
   * legitimate place raw strings become typed identifiers; everything downstream of this should
   * stay on [ToolName] to avoid mixing raw-string tool identifiers with typed ones.
   *
   * `scriptedToolNames` are names that resolved to a scripted (`.ts` / `.js`) tool descriptor,
   * dispatched through the per-session scripted-tool runtime (host bun/QuickJS, on-device QuickJS
   * bundle) — kept separate so the bundling layer knows which names need a scripted runtime.
   */
  data class Partitioned(
    val classBacked: Set<KClass<out TrailblazeTool>>,
    val scriptedToolNames: Set<ToolName> = emptySet(),
  )

  /**
   * Splits a list of tool names into class-backed and scripted. Unknown names log a warning and
   * are skipped.
   */
  fun partitionLenient(names: List<String>, context: String = ""): Partitioned {
    val classBacked = mutableSetOf<KClass<out TrailblazeTool>>()
    val scriptedNames = mutableSetOf<ToolName>()
    for (name in names) {
      val toolName = ToolName(name)
      val klass = knownTools[toolName]
      if (klass != null) {
        classBacked.add(klass)
        continue
      }
      if (toolName in knownScriptedToolNames) {
        scriptedNames.add(toolName)
        continue
      }
      val ctx = if (context.isNotEmpty()) " (in $context)" else ""
      Console.log("Warning: Unknown tool name '$name'$ctx — skipping")
    }
    return Partitioned(
      classBacked = classBacked,
      scriptedToolNames = scriptedNames,
    )
  }

  companion object {
    /**
     * Creates a resolver from the framework's classpath-discoverable tools plus custom
     * tools provided by app target companions, plus the scripted tool names discovered from
     * per-trailmap descriptors, so toolset YAMLs can list any tool by bare name.
     *
     * The bundled class-backed tools are read first and [resourceSource] layered over them, so a
     * workspace file at a bundled manifest's path can swap in another class but never hides the
     * built-in class behind a YAML body that discovery refuses to register.
     */
    fun fromBuiltInAndCustomTools(
      customToolClasses: Map<ToolName, KClass<out TrailblazeTool>> = emptyMap(),
      resourceSource: ConfigResourceSource = platformConfigResourceSource(),
    ): ToolNameResolver = ToolNameResolver(
      knownTools = bundledClassBackedTools(resourceSource) +
        ToolYamlLoader.discoverAndLoadAll(resourceSource) +
        customToolClasses,
      knownScriptedToolNames = ScriptedToolNameDiscoverer.discoverAllNames(resourceSource),
    )

    private fun bundledClassBackedTools(
      resourceSource: ConfigResourceSource,
    ): Map<ToolName, KClass<out TrailblazeTool>> {
      val bundled = bundledConfigResourceSource()
      return if (bundled === resourceSource) emptyMap() else ToolYamlLoader.discoverAndLoadAll(bundled)
    }

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
