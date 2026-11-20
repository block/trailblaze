package xyz.block.trailblaze.config

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
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
 * class-backed tools ([resolvedToolClasses]) and scripted (`.ts` / `.js`) tools
 * ([resolvedScriptedToolNames]) — the name-based authoring surface doesn't distinguish, but the
 * runtime does (scripted names are dispatched through the per-session scripted-tool runtime).
 */
data class ResolvedToolSet(
  val config: ToolSetYamlConfig,
  val resolvedToolClasses: Set<KClass<out TrailblazeTool>>,
  val compatibleDriverTypes: Set<TrailblazeDriverType>,
  val resolvedScriptedToolNames: Set<ToolName> = emptySet(),
  /** The trailmap whose `toolsets/` folder holds this toolset, when it was discovered from one. */
  val trailmapId: String? = null,
  /** What is wrong with [config]'s `memory:` block; non-empty means its keys are ignored. */
  val memoryProblems: List<String> = emptyList(),
) : TrailblazeToolSurface {
  // The interface uses unprefixed names; this loader-local type prefixes them with `resolved*`.
  // Alias overrides let it satisfy [TrailblazeToolSurface] (and so expose `allToolNames`) without
  // renaming its public fields.
  override val toolClasses: Set<KClass<out TrailblazeTool>> get() = resolvedToolClasses
  override val scriptedToolNames: Set<ToolName> get() = resolvedScriptedToolNames

  fun isCompatibleWith(driverType: TrailblazeDriverType): Boolean =
    compatibleDriverTypes.isEmpty() || driverType in compatibleDriverTypes

  fun toCatalogEntry(): ToolSetCatalogEntry = ToolSetCatalogEntry(
    id = config.id,
    description = config.description,
    toolClasses = resolvedToolClasses,
    scriptedToolNames = resolvedScriptedToolNames,
    alwaysEnabled = config.alwaysEnabled,
    compatibleDriverTypes = compatibleDriverTypes,
    memoryKeys = if (memoryProblems.isEmpty()) config.memory else emptyMap(),
    trailmapId = trailmapId,
    memoryProblems = memoryProblems,
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
    val yaml = TrailblazeConfigYaml.instance
    val config = try {
      yaml.decodeFromString(ToolSetYamlConfig.serializer(), yamlString)
    } catch (e: Exception) {
      // A `memory:` block the decoder can't read at all (a list, or a key mapped to a bare string)
      // must not drop the toolset either: load it without the block and report the block instead.
      val withoutMemory = withoutMemoryBlock(yamlString) ?: throw e
      val config = runCatching { yaml.decodeFromYamlNode(ToolSetYamlConfig.serializer(), withoutMemory) }
        .getOrElse { throw e }
      val problem = "`memory:` maps each key name to `{ description: … }`, but it couldn't be read: ${e.message}"
      return resolve(config, toolNameResolver, extraMemoryProblems = listOf(problem))
    }
    return resolve(config, toolNameResolver)
  }

  /** [yamlString]'s root map without its `memory` entry, or null when it has none or isn't a map. */
  private fun withoutMemoryBlock(yamlString: String): YamlNode? {
    val root = runCatching { TrailblazeConfigYaml.instance.parseToYamlNode(yamlString) }.getOrNull() as? YamlMap
      ?: return null
    val kept = root.entries.filterKeys { it.content != "memory" }
    return if (kept.size == root.entries.size) null else YamlMap(kept, root.path)
  }

  /**
   * Loads toolsets from pre-read YAML content strings.
   */
  fun loadAllFromYamlContents(
    yamlContents: Map<String, String>,
    toolNameResolver: ToolNameResolver,
  ): Map<String, ResolvedToolSet> {
    return loadAllYamlWithErrorHandling(yamlContents, "ToolSet") { path, content ->
      // A `<trailmap>/toolsets/<name>.yaml` path names the trailmap that ships the toolset.
      val trailmapId = path.split('/').takeIf { it.size >= 3 && it[1] == "toolsets" }?.first()
      val resolved = loadFromYaml(content, toolNameResolver).copy(trailmapId = trailmapId)
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
    extraMemoryProblems: List<String> = emptyList(),
  ): ResolvedToolSet {
    // A malformed `memory:` block is descriptive data gone wrong, never a reason to drop the
    // toolset's tools: its keys are ignored and `trailblaze check` reports it.
    val memoryProblems = extraMemoryProblems + MemoryKeyDeclaration.problems(config.memory)
    if (memoryProblems.isNotEmpty()) {
      Console.log(
        "ToolSetYamlLoader: WARNING: ignoring the invalid memory: block in toolset '${config.id}': " +
          memoryProblems.joinToString("; "),
      )
    }
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
      resolvedScriptedToolNames = partitioned.scriptedToolNames,
      memoryProblems = memoryProblems,
    )
  }
}
