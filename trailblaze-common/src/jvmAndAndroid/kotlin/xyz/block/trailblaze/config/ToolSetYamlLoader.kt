package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * A toolset whose tool names have been resolved to their backing implementations. Splits into
 * class-backed tools ([resolvedToolClasses]) and YAML-defined tools ([resolvedYamlToolNames]) —
 * the name-based authoring surface doesn't distinguish, but the runtime does.
 */
data class ResolvedToolSet(
  val config: ToolSetYamlConfig,
  val resolvedToolClasses: Set<KClass<out TrailblazeTool>>,
  val compatibleDriverTypes: Set<TrailblazeDriverType>,
  val resolvedYamlToolNames: Set<ToolName> = emptySet(),
) {
  fun isCompatibleWith(driverType: TrailblazeDriverType): Boolean =
    compatibleDriverTypes.isEmpty() || driverType in compatibleDriverTypes

  fun toCatalogEntry(): ToolSetCatalogEntry = ToolSetCatalogEntry(
    id = config.id,
    description = config.description,
    toolClasses = resolvedToolClasses,
    yamlToolNames = resolvedYamlToolNames,
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
   * Discovers and loads all `.toolset.yaml` files from `trailblaze-config/toolsets/`.
   *
   * @param resourceSource where to discover YAML files; defaults to JVM classpath scanning
   */
  fun discoverAndLoadAll(
    toolNameResolver: ToolNameResolver,
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Map<String, ResolvedToolSet> {
    val yamlContents =
      resourceSource.discoverAndLoad(
        directoryPath = TrailblazeConfigPaths.TOOLSETS_DIR,
        suffix = ".yaml",
      )
    return loadAllFromYamlContents(yamlContents, toolNameResolver)
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
    )
  }
}
