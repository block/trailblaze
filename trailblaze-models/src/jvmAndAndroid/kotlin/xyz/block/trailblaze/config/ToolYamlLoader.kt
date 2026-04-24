package xyz.block.trailblaze.config

import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * Loads per-tool `.yaml` files from `trailblaze-config/tools/` and resolves each tool's class via
 * reflection (for class-backed tools) or returns the parsed [ToolYamlConfig] (for YAML-defined
 * `tools:` mode tools).
 *
 * Each YAML file maps a tool name to either a class (reflection) or an inline `tools:` list.
 */
object ToolYamlLoader {

  /**
   * Discovers and loads class-backed tool YAMLs. YAMLs in tools-mode (no `class:` field) are
   * silently skipped — use [discoverYamlDefinedTools] to retrieve those.
   *
   * @param resourceSource where to discover YAML files; defaults to [platformConfigResourceSource]
   *   (classpath scan on JVM, AssetManager on Android).
   */
  fun discoverAndLoadAll(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Map<ToolName, KClass<out TrailblazeTool>> =
    resolveClassBackedConfigsLeniently(discoverAllConfigs(resourceSource))

  /**
   * Loads tool definitions from pre-read YAML content strings. Returns a map of [ToolName] to
   * resolved [KClass] — tools-mode YAMLs are skipped.
   */
  fun loadFromYamlContents(
    yamlContents: Map<String, String>,
  ): Map<ToolName, KClass<out TrailblazeTool>> =
    resolveClassBackedConfigsLeniently(parseAllConfigs(yamlContents))

  /**
   * Resolves class-backed configs one at a time, isolating each `Class.forName` failure so a
   * single bad tool YAML (stale FQCN, module dropped from classpath) doesn't abort discovery of
   * every other tool. Mirrors the per-file error containment already provided by
   * `loadAllYamlWithErrorHandling` during parse.
   */
  private fun resolveClassBackedConfigsLeniently(
    configs: List<ToolYamlConfig>,
  ): Map<ToolName, KClass<out TrailblazeTool>> {
    val out = mutableMapOf<ToolName, KClass<out TrailblazeTool>>()
    configs
      .filter { it.mode == ToolYamlConfig.Mode.CLASS }
      .forEach { config ->
        try {
          out[ToolName(config.id)] = resolveToolClass(config.toolClass!!)
        } catch (e: Exception) {
          Console.log(
            "Warning: Failed to resolve tool class for '${config.id}' " +
              "(class=${config.toolClass}): ${e::class.simpleName}: ${e.message}",
          )
        }
      }
    return out
  }

  /**
   * Discovers only the YAML-defined (`tools:` mode) tool configs on the classpath. Class-backed
   * YAMLs are skipped. These configs are expanded into executable tools at runtime by
   * `YamlDefinedTrailblazeTool` (in `trailblaze-common`).
   */
  fun discoverYamlDefinedTools(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Map<ToolName, ToolYamlConfig> =
    discoverAllConfigs(resourceSource)
      .filter { it.mode == ToolYamlConfig.Mode.TOOLS }
      .associate { ToolName(it.id) to it }

  private fun discoverAllConfigs(
    resourceSource: ConfigResourceSource,
  ): List<ToolYamlConfig> {
    val yamlContents = resourceSource.discoverAndLoad(
      directoryPath = TrailblazeConfigPaths.TOOLS_DIR,
      suffix = ".yaml",
    )
    return parseAllConfigs(yamlContents)
  }

  private fun parseAllConfigs(yamlContents: Map<String, String>): List<ToolYamlConfig> =
    loadAllYamlWithErrorHandling(yamlContents, "Tool") { _, content ->
      TrailblazeConfigYaml.instance
        .decodeFromString(ToolYamlConfig.serializer(), content)
        .also { it.validate() }
    }

  @Suppress("UNCHECKED_CAST")
  private fun resolveToolClass(fqcn: String): KClass<out TrailblazeTool> {
    val clazz = Class.forName(fqcn)
    require(TrailblazeTool::class.java.isAssignableFrom(clazz)) {
      "$fqcn is not a TrailblazeTool"
    }
    return clazz.kotlin as KClass<out TrailblazeTool>
  }
}
