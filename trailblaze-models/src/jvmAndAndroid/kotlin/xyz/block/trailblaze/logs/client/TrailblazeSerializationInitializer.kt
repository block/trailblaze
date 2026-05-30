package xyz.block.trailblaze.logs.client

import kotlinx.serialization.KSerializer
import xyz.block.trailblaze.config.ToolYamlConfig
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
  @Volatile private var cachedYamlDefined: Map<ToolName, ToolYamlConfig>? = null
  @Volatile private var cachedYamlDefinedSerializers:
    Map<ToolName, KSerializer<out TrailblazeTool>>? = null
  private val imperativeTools = mutableMapOf<ToolName, KClass<out TrailblazeTool>>()

  // Workspace-discovered `*.tool.yaml` configs (Mode.TOOLS) that don't ship on the JVM
  // classpath — they live under `<workspace>/trails/config/trailmaps/<id>/tools/` in a
  // user-authored trailmap. Populated lazily from `AppTargetDiscovery` (host-side discovery
  // path) via [registerWorkspaceYamlTools]. Held in a separate field rather than mutating
  // [cachedYamlDefined] so the classpath cache keeps its set-once contract and so a future
  // workspace-rediscovery (CWD change in a long-running daemon) can replace the workspace
  // overlay without invalidating the classpath cache.
  //
  // Read by [buildYamlDefinedTools] which unions classpath + workspace before returning,
  // so every consumer (`ToolNameResolver`, `TrailblazeToolRepo` runtime lookup, the koog
  // descriptor builder, etc.) sees both sets without needing its own discovery path.
  @Volatile private var workspaceYamlDefined: Map<ToolName, ToolYamlConfig> = emptyMap()

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
    val classpath = cachedYamlDefined ?: synchronized(this) {
      cachedYamlDefined ?: run {
        val configs =
          try {
            // Pin to the BUNDLED view — the workspace overlay is layered on via
            // `registerWorkspaceYamlTools()` below, and double-counting (workspace tools
            // appearing in BOTH this cache and the overlay) would force-flip their
            // `requires_host` flag, silently changing dispatch behavior. The JVM platform
            // default is now workspace-aware, so callers that rely on it for *runtime*
            // discovery still get layering — this snapshot is the bundled baseline only.
            // `bundledConfigResourceSource()` is `ClasspathConfigResourceSource` on JVM and
            // the AssetManager-backed platform source on Android (no workspace concept on
            // device) — both correct, both no-workspace-overlay.
            ToolYamlLoader.discoverYamlDefinedTools(bundledConfigResourceSource())
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
    // Workspace tools overlay the classpath set. On a key collision the workspace wins —
    // an end-user-authored `<workspace>/trails/config/trailmaps/<id>/tools/foo.tool.yaml`
    // that happens to share a name with a classpath-bundled tool is a deliberate override (same
    // contract as the filesystem resource source layering in `AppTargetDiscovery`,
    // where workspace files override bundled framework files on filename collision).
    return if (workspaceYamlDefined.isEmpty()) classpath else classpath + workspaceYamlDefined
  }

  /**
   * Registers workspace-discovered `Mode.TOOLS` YAML configs (e.g.
   * `<workspace>/trails/config/trailmaps/<id>/tools/<name>.tool.yaml` in a user trailmap)
   * as an overlay on top of the cached classpath-discovered set. Idempotent for
   * identical inputs; subsequent calls with a different config set REPLACE the overlay
   * rather than appending — the host-side discovery pipeline is the single source of truth
   * for "what workspace tools should be visible right now."
   *
   * Called from `AppTargetDiscovery.discover()` once per discovery pass with the
   * Mode.TOOLS subset of the discovered configs. Pure classpath callers (tests, the
   * standalone trail YAML decoder) don't need to invoke this — they keep getting the
   * cached classpath set.
   *
   * **Override contract.** When a workspace key collides with a classpath key, the overlay
   * wins in [buildYamlDefinedTools]'s `classpath + overlay` union (kotlin `Map.plus` is
   * last-write-wins). Callers that want a workspace tool to override a framework-bundled
   * tool can do so by registering a config with the same id here; the union's collision
   * semantics make the override the natural shape. Pre-filtering the input by
   * "not-already-in-classpath" would silently discard such overrides — don't.
   *
   * Mutable-singleton-state caveat: the daemon's current contract is one workspace per
   * process (CWD captured at startup), so the "replace" semantics are equivalent to "set"
   * in practice. If we ever support hot-swapping workspaces mid-process, this overlay
   * mechanism stays correct (last-write-wins). Tests that exercise multiple workspaces in
   * a single JVM should call this with `emptyMap()` between cases to reset the overlay.
   */
  fun registerWorkspaceYamlTools(configs: Map<ToolName, ToolYamlConfig>) {
    synchronized(this) {
      workspaceYamlDefined = configs
      // Drop the cached serializer map — it was built from the previous classpath-only
      // (or previous workspace overlay) view, and any consumer that asked for a serializer
      // before this call would now race the new overlay. The next `buildYamlDefinedSerializers`
      // call will rebuild from the union.
      cachedYamlDefinedSerializers = null
    }
  }

  /**
   * Returns the cached **classpath-only** YAML-defined tool set, distinct from
   * [buildYamlDefinedTools] which returns the union of classpath + workspace overlay.
   *
   * Diagnostics and overlay-management code (e.g. `AppTargetDiscovery.registerWorkspaceYamlTools`
   * computing the "new vs override classpath" split for its log line) need to see the
   * classpath set without the overlay layered on top. Using [buildYamlDefinedTools] for
   * that comparison is the bug pattern that hit pre-#2837-followup: on a second
   * `discover()` pass the previously-registered overlay would appear in the "classpath
   * names" snapshot, making "workspace-only" empty and the override-detection wrong.
   *
   * Forces the classpath cache to be computed if it isn't yet (lazy init contract is
   * identical to [buildYamlDefinedTools]).
   */
  fun getClasspathYamlDefinedTools(): Map<ToolName, ToolYamlConfig> {
    // Trigger the lazy-init in buildYamlDefinedTools (it populates `cachedYamlDefined`),
    // then return that snapshot directly — bypassing the overlay union.
    buildYamlDefinedTools()
    return cachedYamlDefined ?: emptyMap()
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
            // Workspace YAML tools (`Mode.TOOLS`) flow in via `registerWorkspaceYamlTools()`
            // overlay; class-backed workspace tools are pulled in via the imperative
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
              "trailblaze-playwright, trailblaze-android-world-benchmarks, or your own " +
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
