package xyz.block.trailblaze.config

import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console

/**
 * Loads `.app.yaml` files into [YamlBackedHostAppTarget] instances.
 *
 * Supports loading from JVM classpath (default) or any [ConfigResourceSource] such as Android
 * assets.
 */
object AppTargetYamlLoader {

  /** Cached raw YAML contents from classpath discovery. Volatile for thread-safe lazy init. */
  @Volatile private var cachedYamlContents: Map<String, String>? = null

  /** Cached lightweight configs (no tool resolution). Volatile for thread-safe lazy init. */
  @Volatile private var cachedConfigs: List<AppTargetYamlConfig>? = null

  private fun discoverYamlContents(
    resourceSource: ConfigResourceSource,
  ): Map<String, String> {
    if (resourceSource === ClasspathConfigResourceSource) {
      cachedYamlContents?.let { return it }
    }
    val contents =
      resourceSource.discoverAndLoad(
        directoryPath = TrailblazeConfigPaths.TARGETS_DIR,
        suffix = ".yaml",
      )
    if (resourceSource === ClasspathConfigResourceSource) {
      cachedYamlContents = contents
    }
    return contents
  }

  /**
   * Parses a single YAML string into a [YamlBackedHostAppTarget].
   */
  fun loadFromYaml(
    yamlString: String,
    toolNameResolver: ToolNameResolver,
    availableToolSets: Map<String, ResolvedToolSet> = emptyMap(),
    companion: AppTargetCompanion? = null,
  ): YamlBackedHostAppTarget {
    val config =
      TrailblazeConfigYaml.instance.decodeFromString(AppTargetYamlConfig.serializer(), yamlString)
    return YamlBackedHostAppTarget(
      config = config,
      toolNameResolver = toolNameResolver,
      availableToolSets = availableToolSets,
      companion = companion,
    )
  }

  /**
   * Loads app targets from pre-read YAML content strings.
   */
  fun loadAllFromYamlContents(
    yamlContents: Map<String, String>,
    toolNameResolver: ToolNameResolver,
    availableToolSets: Map<String, ResolvedToolSet> = emptyMap(),
    companions: Map<String, AppTargetCompanion> = emptyMap(),
  ): Set<TrailblazeHostAppTarget> {
    return loadAllYamlWithErrorHandling(yamlContents, "App target") { _, content ->
      val config =
        TrailblazeConfigYaml.instance.decodeFromString(AppTargetYamlConfig.serializer(), content)
      YamlBackedHostAppTarget(
        config = config,
        toolNameResolver = toolNameResolver,
        availableToolSets = availableToolSets,
        companion = companions[config.id],
      )
    }.toSet()
  }

  /**
   * Loads app targets from already-parsed configs.
   */
  fun loadAllFromConfigs(
    configs: List<AppTargetYamlConfig>,
    toolNameResolver: ToolNameResolver,
    availableToolSets: Map<String, ResolvedToolSet> = emptyMap(),
    companions: Map<String, AppTargetCompanion> = emptyMap(),
  ): Set<TrailblazeHostAppTarget> {
    return configs
      .mapNotNull { config ->
        try {
          YamlBackedHostAppTarget(
            config = config,
            toolNameResolver = toolNameResolver,
            availableToolSets = availableToolSets,
            companion = companions[config.id],
          )
        } catch (e: Exception) {
          Console.log(
            "Warning: Failed to load app target '${config.id}': " +
              "${e::class.simpleName}: ${e.message}",
          )
          null
        }
      }
      .toSet()
  }

  /**
   * Discovers all target YAML configs and returns just the parsed [AppTargetYamlConfig]s.
   * Lightweight -- no tool resolution needed. Results are cached for the default classpath source.
   *
   * @param resourceSource where to discover YAML files; defaults to JVM classpath scanning
   */
  fun discoverConfigs(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): List<AppTargetYamlConfig> {
    if (resourceSource === ClasspathConfigResourceSource) {
      cachedConfigs?.let { return it }
    }
    return try {
      loadAllYamlWithErrorHandling(
        discoverYamlContents(resourceSource),
        "App target config",
      ) { _, content ->
        TrailblazeConfigYaml.instance.decodeFromString(AppTargetYamlConfig.serializer(), content)
      }
    } catch (e: Exception) {
      Console.log("Warning: Failed to discover app target configs: ${e.message}")
      emptyList()
    }.also {
      if (resourceSource === ClasspathConfigResourceSource) {
        cachedConfigs = it
      }
    }
  }

  /**
   * Discovers and loads all `.app.yaml` files from `trailblaze-config/targets/`. Reuses cached
   * classpath discovery from [discoverConfigs] if already called with the default source.
   *
   * @param resourceSource where to discover YAML files; defaults to JVM classpath scanning
   */
  fun discoverAndLoadAll(
    toolNameResolver: ToolNameResolver,
    availableToolSets: Map<String, ResolvedToolSet> = emptyMap(),
    companions: Map<String, AppTargetCompanion> = emptyMap(),
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Set<TrailblazeHostAppTarget> {
    return loadAllFromConfigs(
      configs = discoverConfigs(resourceSource),
      toolNameResolver = toolNameResolver,
      availableToolSets = availableToolSets,
      companions = companions,
    )
  }
}
