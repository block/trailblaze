package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSurface
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * A toolset whose tool names have been resolved to their backing implementations. Splits into
 * class-backed tools ([resolvedToolClasses]), YAML-defined tools ([resolvedYamlToolNames]), and
 * scripted (`.ts` / `.js`) tools ([resolvedScriptedToolNames]) — the name-based authoring surface
 * doesn't distinguish, but the runtime does (scripted names are advertised like YAML names but
 * dispatched through the per-session scripted-tool runtime).
 */
data class ResolvedToolSet(
  val config: ToolSetYamlConfig,
  val resolvedToolClasses: Set<KClass<out TrailblazeTool>>,
  val compatibleDriverTypes: Set<TrailblazeDriverType>,
  val resolvedYamlToolNames: Set<ToolName> = emptySet(),
  val resolvedScriptedToolNames: Set<ToolName> = emptySet(),
) : TrailblazeToolSurface {
  // The interface uses unprefixed names; this loader-local type prefixes them with `resolved*`.
  // Alias overrides let it satisfy [TrailblazeToolSurface] (and so expose `allToolNames`) without
  // renaming its public fields.
  override val toolClasses: Set<KClass<out TrailblazeTool>> get() = resolvedToolClasses
  override val yamlToolNames: Set<ToolName> get() = resolvedYamlToolNames
  override val scriptedToolNames: Set<ToolName> get() = resolvedScriptedToolNames

  fun isCompatibleWith(driverType: TrailblazeDriverType): Boolean =
    compatibleDriverTypes.isEmpty() || driverType in compatibleDriverTypes

  fun toCatalogEntry(): ToolSetCatalogEntry = ToolSetCatalogEntry(
    id = config.id,
    description = config.description,
    toolClasses = resolvedToolClasses,
    yamlToolNames = resolvedYamlToolNames,
    scriptedToolNames = resolvedScriptedToolNames,
    alwaysEnabled = config.alwaysEnabled,
    compatibleDriverTypes = compatibleDriverTypes,
  )
}

/**
 * Loads `.toolset.yaml` files from classpath resources or other [ConfigResourceSource]s.
 */
object ToolSetYamlLoader {

  /**
   * Parses a single YAML string into a [ResolvedToolSet].
   */
  fun loadFromYaml(
    yamlString: String,
    toolNameResolver: ToolNameResolver,
  ): ResolvedToolSet {
    val config =
      TrailblazeConfigYaml.instance.decodeFromString(ToolSetYamlConfig.serializer(), yamlString)
    return resolve(config, toolNameResolver)
  }

  /**
   * Loads toolsets from pre-read YAML content strings.
   */
  fun loadAllFromYamlContents(
    yamlContents: Map<String, String>,
    toolNameResolver: ToolNameResolver,
  ): Map<String, ResolvedToolSet> {
    return loadAllYamlWithErrorHandling(yamlContents, "ToolSet") { _, content ->
      val resolved = loadFromYaml(content, toolNameResolver)
      resolved.config.id to resolved
    }.toMap()
  }

  /**
   * Loads toolsets from already-parsed configs.
   */
  fun loadAllFromConfigs(
    configs: List<ToolSetYamlConfig>,
    toolNameResolver: ToolNameResolver,
  ): Map<String, ResolvedToolSet> =
    buildMap {
      configs.forEach { config ->
        try {
          val resolved = resolve(config, toolNameResolver)
          put(resolved.config.id, resolved)
        } catch (e: Exception) {
          Console.log(
            "Warning: Failed to load toolset '${config.id}': " +
              "${e::class.simpleName}: ${e.message}",
          )
        }
      }
    }

  /**
   * Discovers and loads all `.yaml` toolset files under
   * `trails/config/trailmaps/<id>/toolsets/`. This is the only authoring layout the
   * framework supports — every framework module's toolsets live here, and workspaces
   * drop their own at `<workspace>/trails/config/trailmaps/<id>/toolsets/<name>.yaml`.
   *
   * On same-relPath collision between workspace and classpath the workspace wins —
   * `CompositeConfigResourceSource` collapses that before discovery sees it.
   *
   * @param resourceSource where to discover YAML files; defaults to the platform default
   *   (workspace-layered on JVM, AssetManager-backed on Android).
   */
  fun discoverAndLoadAll(
    toolNameResolver: ToolNameResolver,
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Map<String, ResolvedToolSet> {
    // Walk every `trailmaps/<id>/toolsets/<name>.yaml` entry recursively. The resource-source
    // contract strips the leading `trails/config/trailmaps/` prefix, so relPath starts at
    // `<id>/...`. Filter to entries whose second segment is `toolsets` so we don't pick up
    // `trailmap.yaml`, `tools/*.tool.yaml`, etc. from the same recursive walk.
    val trailmapScopedContents = try {
      resourceSource.discoverAndLoadRecursive(
        directoryPath = TrailblazeConfigPaths.TRAILMAPS_DIR,
        suffix = ".yaml",
      ).mapNotNull { (relPath, content) ->
        val segments = relPath.split('/')
        if (segments.size < 3 || segments[1] != "toolsets") return@mapNotNull null
        // Key by the full relPath (e.g. `trailblaze/toolsets/core_interaction.yaml`) so two
        // trailmaps shipping the same filename — `trailmaps/a/toolsets/core.yaml` and
        // `trailmaps/b/toolsets/core.yaml` — both reach [loadAllFromYamlContents]. The id-level
        // dedup happens there against the parsed `config.id`; keying by basename here would
        // silently drop one of the two entries before it ever got the chance to be deduped
        // (and "they're different toolsets if their ids differ" is the contract).
        relPath to content
      }.toMap()
    } catch (e: Exception) {
      Console.log(
        "ToolSetYamlLoader: WARNING: failed to scan ${TrailblazeConfigPaths.TRAILMAPS_DIR} " +
          "for trailmap-scoped toolsets (${e::class.simpleName}: ${e.message}). Toolset " +
          "discovery will return empty for this pass.",
      )
      emptyMap()
    }
    return loadAllFromYamlContents(trailmapScopedContents, toolNameResolver)
  }

  private fun resolve(
    config: ToolSetYamlConfig,
    toolNameResolver: ToolNameResolver,
  ): ResolvedToolSet {
    val partitioned =
      toolNameResolver.partitionLenient(config.tools, context = "toolset '${config.id}'")
    val driverTypes = buildSet {
      config.platforms?.forEach { addAll(DriverTypeKey.resolve(it)) }
      config.drivers?.forEach { addAll(DriverTypeKey.resolve(it)) }
    }
    return ResolvedToolSet(
      config = config,
      resolvedToolClasses = partitioned.classBacked,
      compatibleDriverTypes = driverTypes,
      resolvedYamlToolNames = partitioned.yamlDefinedNames,
      resolvedScriptedToolNames = partitioned.scriptedToolNames,
    )
  }
}
