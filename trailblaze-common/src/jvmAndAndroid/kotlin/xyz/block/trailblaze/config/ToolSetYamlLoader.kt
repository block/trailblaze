package xyz.block.trailblaze.config

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

/**
 * A toolset whose tool names have been resolved to [KClass] references.
 */
data class ResolvedToolSet(
  val config: ToolSetYamlConfig,
  val resolvedToolClasses: Set<KClass<out TrailblazeTool>>,
  val compatibleDriverTypes: Set<TrailblazeDriverType>,
) {
  fun isCompatibleWith(driverType: TrailblazeDriverType): Boolean =
    compatibleDriverTypes.isEmpty() || driverType in compatibleDriverTypes

  fun toCatalogEntry(): ToolSetCatalogEntry = ToolSetCatalogEntry(
    id = config.id,
    description = config.description,
    toolClasses = resolvedToolClasses,
    alwaysEnabled = config.alwaysEnabled,
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
   * Discovers and loads all `.toolset.yaml` files from `trailblaze-config/toolsets/`.
   *
   * @param resourceSource where to discover YAML files; defaults to JVM classpath scanning
   */
  fun discoverAndLoadAll(
    toolNameResolver: ToolNameResolver,
    resourceSource: ConfigResourceSource = ClasspathConfigResourceSource,
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
    val toolClasses =
      toolNameResolver.resolveAllLenient(config.tools, context = "toolset '${config.id}'")
    val driverTypes = buildSet {
      config.platforms?.forEach { addAll(DriverTypeKey.resolve(it)) }
      config.drivers?.forEach { addAll(DriverTypeKey.resolve(it)) }
    }
    return ResolvedToolSet(
      config = config,
      resolvedToolClasses = toolClasses,
      compatibleDriverTypes = driverTypes,
    )
  }
}
