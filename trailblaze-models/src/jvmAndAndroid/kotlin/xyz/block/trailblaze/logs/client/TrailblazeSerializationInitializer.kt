package xyz.block.trailblaze.logs.client

import kotlinx.serialization.KSerializer
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * Produces the single tool map consumed by [TrailblazeJsonInstance] and
 * [TrailblazeYaml.Default].
 *
 * Every tool is registered via YAML files under `trailblaze-config/tools/` on the classpath — there
 * is no imperative registration API. YAML discovery uses a platform-aware
 * [xyz.block.trailblaze.llm.config.ConfigResourceSource]:
 *
 * - JVM: classpath scanning (directories and JARs).
 * - Android: AssetManager scanning via `InstrumentationRegistry`.
 *
 * [buildAllTools] is called exactly once per process, by the lazy initializers of
 * [TrailblazeJsonInstance] and [TrailblazeYaml.Default]. The result is cached.
 */
object TrailblazeSerializationInitializer {

  @Volatile private var cached: Map<ToolName, KClass<out TrailblazeTool>>? = null
  @Volatile private var cachedYamlDefined: Map<ToolName, ToolYamlConfig>? = null
  @Volatile private var cachedYamlDefinedSerializers:
    Map<ToolName, KSerializer<out TrailblazeTool>>? = null
  private val imperativeTools = mutableMapOf<ToolName, KClass<out TrailblazeTool>>()

  /**
   * Registers tool classes imperatively. These classes are merged with classpath-discovered tools
   * during [buildAllTools].
   *
   * Must be called before [buildAllTools] or [TrailblazeJsonInstance] is first accessed.
   * Late registration throws [IllegalStateException].
   */
  fun registerImperativeToolClasses(tools: Map<ToolName, KClass<out TrailblazeTool>>) {
    synchronized(this) {
      check(cached == null) {
        "Cannot register imperative tools after TrailblazeSerializationInitializer has been initialized"
      }
      imperativeTools.putAll(tools)
    }
  }

  /**
   * Builds and returns the map of YAML-defined (`tools:` mode) tool configs. The result is
   * cached on first invocation — including discovery failures and legitimate empty results —
   * to match the singleton-init contract of `buildAllTools` above and avoid surprise
   * re-discovery during a process lifetime. If discovery fails, the cached `emptyMap()`
   * still lets subsequent readers proceed (YAML-defined tools just won't decode). Restart
   * the process to retry discovery.
   *
   * Consumed by `TrailblazeYaml.Default` (trail YAML decode) and `TrailblazeJsonInstance`
   * (log serialization) to register per-tool custom serializers alongside the reflectively-
   * built class-backed serializers. Also consumed by the Koog tool registry in
   * `trailblaze-common` to build LLM-visible descriptors for YAML-defined tools.
   */
  fun buildYamlDefinedTools(): Map<ToolName, ToolYamlConfig> {
    cachedYamlDefined?.let { return it }
    return synchronized(this) {
      cachedYamlDefined ?: run {
        val configs =
          try {
            ToolYamlLoader.discoverYamlDefinedTools()
          } catch (e: Exception) {
            Console.error(
              "YAML-defined tool discovery failed: ${e.message}. " +
                "tools: mode YAML definitions will not be registered.\n" +
                e.stackTraceToString(),
            )
            emptyMap()
          }
        if (configs.isNotEmpty()) {
          val sampleNames = configs.keys.map { it.toolName }.sorted().take(6).joinToString(", ")
          Console.log(
            "TrailblazeSerializationInitializer: discovered ${configs.size} YAML-defined tools " +
              "(sample: $sampleNames…)",
          )
        }
        cachedYamlDefined = configs
        configs
      }
    }
  }

  /**
   * Builds and returns the tool map. Cached after first successful discovery. If discovery
   * yields an empty set, logs a loud warning with remediation hints — an empty tool set
   * almost always means a module with `trailblaze-config/tools/` YAML resources isn't on
   * the classpath / in the Android assets.
   */
  internal fun buildAllTools(): Map<ToolName, KClass<out TrailblazeTool>> {
    cached?.let { return it }
    return synchronized(this) {
      cached ?: run {
        val tools =
          try {
            ToolYamlLoader.discoverAndLoadAll() + imperativeTools
          } catch (e: Exception) {
            Console.error(
              "YAML tool discovery failed: ${e.message}. " +
                "Serialization will have no registered tools — polymorphic decoding will " +
                "fall through to OtherTrailblazeTool for every tool call.\n" +
                e.stackTraceToString(),
            )
            imperativeTools
          }
        if (tools.isEmpty()) {
          Console.error(
            "TrailblazeSerializationInitializer: no tools discovered via " +
              "trailblaze-config/tools/ YAML files. Check that the module providing your tools " +
              "(trailblaze-common, trailblaze-compose, trailblaze-playwright, " +
              "trailblaze-android-world-benchmarks, or your own tool module) is on the " +
              "classpath — on Android, verify commonMain/resources is wired as both " +
              "Java resources AND Android assets in the module's build.gradle.kts.",
          )
        } else {
          val sampleNames = tools.keys.map { it.toolName }.sorted().take(6).joinToString(", ")
          Console.log(
            "TrailblazeSerializationInitializer: discovered ${tools.size} tool classes " +
              "via YAML (sample: $sampleNames…)",
          )
        }
        cached = tools
        tools
      }
    }
  }

  /**
   * Builds a `Map<toolName, KSerializer>` for every discovered YAML-defined (`tools:` mode) tool
   * by reflectively constructing `YamlDefinedToolSerializer(config)` from `trailblaze-common`.
   *
   * The reflection indirection is required because `YamlDefinedToolSerializer` lives in
   * `trailblaze-common` (alongside `YamlDefinedTrailblazeTool`, which transitively depends on
   * `TrailblazeToolExecutionContext` and other jvm-side interfaces that can't move down to
   * `trailblaze-models`). This file is in `trailblaze-models` and can't import from the upper
   * layer directly. If `trailblaze-common` is not on the classpath, the lookup fails gracefully
   * and YAML-defined tools simply won't decode — existing class-backed tools still work.
   */
  @Suppress("UNCHECKED_CAST")
  fun buildYamlDefinedToolSerializers(): Map<ToolName, KSerializer<out TrailblazeTool>> {
    cachedYamlDefinedSerializers?.let { return it }
    return synchronized(this) {
      cachedYamlDefinedSerializers ?: run {
        val configs = buildYamlDefinedTools()
        val result =
          if (configs.isEmpty()) {
            emptyMap()
          } else {
            try {
              val serializerClass = Class.forName(YAML_DEFINED_TOOL_SERIALIZER_FQCN)
              val constructor = serializerClass.getConstructor(ToolYamlConfig::class.java)
              configs.entries.associate { (name, config) ->
                name to (constructor.newInstance(config) as KSerializer<out TrailblazeTool>)
              }
            } catch (e: ReflectiveOperationException) {
              Console.error(
                "YamlDefinedToolSerializer reflection failed — `tools:`-mode YAML tool definitions " +
                  "will not decode. Ensure `:trailblaze-common` is on your module's runtime " +
                  "classpath and YamlDefinedToolSerializer has a public `(ToolYamlConfig)` " +
                  "constructor. ${e::class.simpleName}: ${e.message}",
              )
              emptyMap()
            }
          }
        cachedYamlDefinedSerializers = result
        result
      }
    }
  }

  private const val YAML_DEFINED_TOOL_SERIALIZER_FQCN =
    "xyz.block.trailblaze.config.YamlDefinedToolSerializer"
}
