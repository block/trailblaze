package xyz.block.trailblaze.logs.client

import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.llm.config.bundledConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * Produces the single tool map consumed by [TrailblazeJsonInstance] and
 * [TrailblazeYaml.Default].
 *
 * Every tool is registered via YAML files under `trails/config/trailmaps/<id>/tools/` on the
 * classpath — there is no imperative registration API. YAML discovery uses a platform-aware
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
   * Builds and returns the tool map. Cached after first successful discovery. If discovery
   * yields an empty set, logs a loud warning with remediation hints — an empty tool set
   * almost always means a module with `trails/config/trailmaps/<id>/tools/` YAML resources
   * isn't on the classpath / in the Android assets.
   */
  // Public so cross-module consumers (notably `TrailblazeToolRepo.toolCallToTrailblazeToolUnfiltered`
  // in `:trailblaze-common`) can fall back to the global class registry when scripted-tool
  // composition needs to reach a framework tool that isn't in the session's registered set.
  fun buildAllTools(): Map<ToolName, KClass<out TrailblazeTool>> {
    cached?.let { return it }
    return synchronized(this) {
      cached ?: run {
        val tools =
          try {
            // Bundled view by design — this is the serialization registry baseline, used
            // by `TrailblazeJsonInstance` / `TrailblazeYaml.Default` for polymorphic decode.
            // Class-backed workspace tools are pulled in via the imperative
            // registration path (`registerImperativeToolClasses`). Letting workspace bleed
            // into THIS cache would force-flip dispatch flags on every tool that happens
            // to share an id with a bundled one. `bundledConfigResourceSource()` is
            // `ClasspathConfigResourceSource` on JVM and the AssetManager-backed platform
            // source on Android — both no-workspace-overlay.
            ToolYamlLoader.discoverAndLoadAll(bundledConfigResourceSource()) + imperativeTools
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
              "trails/config/trailmaps/<id>/tools/ YAML files. Check that the module " +
              "providing your tools (trailblaze-common, trailblaze-compose, " +
              "trailblaze-playwright, or your own " +
              "tool module) is on the classpath — on Android, verify commonMain/resources " +
              "is wired as both Java resources AND Android assets in the module's " +
              "build.gradle.kts.",
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
}
