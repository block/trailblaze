package xyz.block.trailblaze.config

import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

/**
 * Loads per-tool `.yaml` files from `trailblaze-config/tools/` and resolves each tool's class via
 * reflection.
 *
 * Each YAML file maps a tool name to its fully qualified class name, enabling tool discovery without
 * requiring [AppTargetCompanion.getAdditionalToolClasses] to enumerate them at compile time.
 */
object ToolYamlLoader {

  /**
   * Discovers and loads all per-tool YAML files from `trailblaze-config/tools/`. Returns a map of
   * [ToolName] to resolved [KClass] for use with [ToolNameResolver].
   *
   * @param resourceSource where to discover YAML files; defaults to JVM classpath scanning
   */
  fun discoverAndLoadAll(
    resourceSource: ConfigResourceSource = ClasspathConfigResourceSource,
  ): Map<ToolName, KClass<out TrailblazeTool>> {
    val yamlContents =
      resourceSource.discoverAndLoad(
        directoryPath = TrailblazeConfigPaths.TOOLS_DIR,
        suffix = ".yaml",
      )
    return loadFromYamlContents(yamlContents)
  }

  /**
   * Loads tool definitions from pre-read YAML content strings. Returns a map of [ToolName] to
   * resolved [KClass].
   */
  fun loadFromYamlContents(
    yamlContents: Map<String, String>
  ): Map<ToolName, KClass<out TrailblazeTool>> {
    return loadAllYamlWithErrorHandling(yamlContents, "Tool") { _, content ->
      val config =
        TrailblazeConfigYaml.instance.decodeFromString(ToolYamlConfig.serializer(), content)
      val kClass = resolveToolClass(config.toolClass)
      ToolName(config.id) to kClass
    }.toMap()
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
