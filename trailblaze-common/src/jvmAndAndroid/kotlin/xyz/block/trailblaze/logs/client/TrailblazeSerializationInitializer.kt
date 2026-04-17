package xyz.block.trailblaze.logs.client

import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.createTrailblazeYamlFromAllTools
import kotlin.reflect.KClass

/**
 * Initializes [TrailblazeJsonInstance] and [TrailblazeYaml.Default] with all tool classes
 * discovered from the classpath.
 *
 * Tool classes are discovered from two sources:
 * 1. Built-in tools from [TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerialization]
 * 2. YAML-registered tools from `trailblaze-config/tools/` YAML files on the classpath,
 *    loaded via [ToolYamlLoader] (includes driver-specific tools like Playwright, Compose,
 *    Revyl, and app-specific custom tools)
 *
 * Safe to call multiple times. If called again with new [additionalToolClasses], the
 * serializers are rebuilt to include them.
 */
object TrailblazeSerializationInitializer {

  /** YAML-discovered tools, cached after first successful discovery. */
  private var cachedYamlTools: Map<ToolName, KClass<out TrailblazeTool>>? = null

  /** Tool names registered in the current initialization. */
  private var registeredToolNames: Set<ToolName> = emptySet()

  /**
   * Discovers all tool classes from the classpath and initializes both
   * [TrailblazeJsonInstance] and [TrailblazeYaml.Default].
   *
   * @param additionalToolClasses extra tool classes not discoverable from YAML
   *   (e.g., app-target custom tools passed at runtime)
   */
  @Suppress("DEPRECATION")
  fun initialize(
    additionalToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
  ) {
    synchronized(this) {
      // On failure the result is NOT cached, so subsequent calls will retry discovery.
      val yamlDiscoveredTools =
        cachedYamlTools
          ?: try {
              ToolYamlLoader.discoverAndLoadAll().also { cachedYamlTools = it }
            } catch (e: Exception) {
              Console.error(
                "YAML tool discovery failed: ${e.message}. " +
                  "Falling back to built-in tools only."
              )
              e.printStackTrace()
              emptyMap()
            }

      val allByName =
        TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerializationByToolName +
          yamlDiscoveredTools +
          additionalToolClasses.associateBy { it.toolName() }

      // Skip re-initialization if the exact same tool set is already registered.
      val newToolNames = allByName.keys
      if (newToolNames == registeredToolNames) return

      TrailblazeJsonInstance = TrailblazeJson.createTrailblazeJsonInstance(allByName)
      TrailblazeYaml.initDefault(createTrailblazeYamlFromAllTools(allByName.values.toSet()))
      registeredToolNames = newToolNames
    }
  }
}
