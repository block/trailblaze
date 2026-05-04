package xyz.block.trailblaze.host

import xyz.block.trailblaze.config.AppTargetCompanion
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.ResolvedToolSet
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.config.ToolSetYamlLoader
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.config.project.TargetEntry
import xyz.block.trailblaze.config.project.ToolEntry
import xyz.block.trailblaze.config.project.ToolsetEntry
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.config.project.ResolvedTrailblazeWorkspaceConfig
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.CompositeConfigResourceSource
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.FilesystemConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Paths
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

/**
 * Shared app-target discovery for desktop builds.
 *
 * Runs `ToolYamlLoader` → `ToolSetYamlLoader` → `AppTargetYamlLoader` over a layered
 * resource source that combines the JVM classpath (framework-shipped config) with an
 * optional workspace `trails/config/` directory, then applies `trailblaze.yaml`
 * entries from that same workspace as the final override layer. Callers parameterize the
 * variable bits:
 *
 *  - [companions] — behavioral extensions keyed by target id (e.g. custom launch hooks).
 *    Opensource users typically pass an empty map.
 *  - [workspaceConfigProvider] — resolves the current workspace anchor
 *    (`trails/config/trailblaze.yaml`) and owning config directory (`trails/config/`)
 *    through one shared rule.
 *  - [defaultFallback] — target to return when discovery finds nothing, so the UI's target
 *    picker has something to render on a clean checkout.
 *  - [logPrefix] — short tag for the diagnostic log lines (`[Block]`, `[OpenSource]`, …).
 *
 * Replaces what used to be separate `BlockAppTargets` / `OpenSourceAppTargets` objects with
 * nearly-identical discovery code. Each caller is now a thin wrapper that supplies its own
 * companions + fallback.
 */
object AppTargetDiscovery {

  /**
   * Discover targets. Intended to be invoked from a `by lazy` on the desktop-config's
   * `availableAppTargets`, so the work runs once per JVM.
   *
   * NOTE: results are computed eagerly on call and not cached inside this object — caching
   * is the caller's responsibility (typically via `by lazy`). Callers that want to react
   * to settings changes can wrap this in a `StateFlow` and recompute on signal.
   */
  fun discover(
    companions: Map<String, AppTargetCompanion> = emptyMap(),
    workspaceConfigProvider: () -> ResolvedTrailblazeWorkspaceConfig = {
      TrailblazeWorkspaceConfigResolver.resolve(Paths.get(""))
    },
    defaultFallback: TrailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    logPrefix: String = "[AppTargetDiscovery]",
  ): Set<TrailblazeHostAppTarget> {
    return try {
      val workspaceConfig = workspaceConfigProvider()
      val source = buildResourceSource(workspaceConfig.configDir, logPrefix)
      val projectConfig = loadProjectConfigLeniently(workspaceConfig, logPrefix)
      val customToolClasses = loadCustomToolClasses(
        resourceSource = source,
        projectConfig = projectConfig,
        logPrefix = logPrefix,
      )
      val resolver = ToolNameResolver.fromBuiltInAndCustomTools(customToolClasses)

      val discoveredToolSets = ToolSetYamlLoader.discoverAndLoadAll(
        toolNameResolver = resolver,
        resourceSource = source,
      )
      val projectToolSets = projectConfig
        ?.let(::projectToolSetConfigs)
        ?.let { ToolSetYamlLoader.loadAllFromConfigs(it, resolver) }
        .orEmpty()
      val toolSets = discoveredToolSets + projectToolSets
      Console.log("$logPrefix Discovered ${toolSets.size} toolsets: ${toolSets.keys.sorted()}")

      val discoveredTargets = AppTargetYamlLoader.discoverAndLoadAll(
        toolNameResolver = resolver,
        availableToolSets = toolSets,
        companions = companions,
        resourceSource = source,
      )
      val projectTargets = projectConfig
        ?.let(::projectTargetConfigs)
        ?.let {
          AppTargetYamlLoader.loadAllFromConfigs(
            configs = it,
            toolNameResolver = resolver,
            availableToolSets = toolSets,
            companions = companions,
          )
        }
        .orEmpty()
      val targets = mergeTargets(discoveredTargets, projectTargets)
      Console.log("$logPrefix Discovered ${targets.size} app targets: ${targets.map { it.id }.sorted()}")

      targets.ifEmpty { setOf(defaultFallback) }
    } catch (e: Exception) {
      // Route through Console.error so the daemon's logger sees it. `printStackTrace()`
      // goes to stderr, which the launcher discards — the trace would be invisible.
      Console.error("$logPrefix Error loading app targets from YAML: ${e.message}\n${e.stackTraceToString()}")
      setOf(defaultFallback)
    }
  }

  private fun loadProjectConfigLeniently(
    workspaceConfig: ResolvedTrailblazeWorkspaceConfig,
    logPrefix: String,
  ): TrailblazeProjectConfig? {
    return try {
      workspaceConfig.loadProjectConfig()
    } catch (e: Exception) {
      Console.log(
        "$logPrefix Ignoring workspace trailblaze.yaml due to load failure: " +
          "${e::class.simpleName}: ${e.message}",
      )
      null
    }
  }

  /**
   * Classpath alone, or classpath + filesystem layered when a per-project dir is available.
   * Filesystem sources come after classpath so user-contributed entries override framework
   * defaults on filename collisions.
   *
   * Two filesystem layers stack here:
   * 1. The workspace `trails/config/` itself, picking up hand-authored target / toolset /
   *    tool YAMLs that users edit directly.
   * 2. The compile-output directory `trails/config/dist/`, which holds materialized
   *    target YAMLs emitted by `trailblaze compile` (and by the daemon-init
   *    [WorkspaceCompileBootstrap] before this discovery runs). The dist layer comes
   *    LAST so generated targets win when both exist for the same id — packs are the
   *    authoritative source for any pack-backed target, and a stale hand-authored copy
   *    from before the pack migration shouldn't shadow the freshly-compiled output.
   */
  private fun buildResourceSource(
    trailblazeConfigDir: File?,
    logPrefix: String,
  ): ConfigResourceSource {
    if (trailblazeConfigDir == null) return ClasspathConfigResourceSource
    Console.log("$logPrefix Layering user config from: ${trailblazeConfigDir.absolutePath}")
    return CompositeConfigResourceSource(
      sources = listOf(
        ClasspathConfigResourceSource,
        FilesystemConfigResourceSource(rootDir = trailblazeConfigDir),
        FilesystemConfigResourceSource(
          rootDir = File(trailblazeConfigDir, TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR),
        ),
      ),
    )
  }

  private fun loadCustomToolClasses(
    resourceSource: ConfigResourceSource,
    projectConfig: TrailblazeProjectConfig?,
    logPrefix: String,
  ): Map<ToolName, KClass<out TrailblazeTool>> {
    val discovered = ToolYamlLoader.discoverAndLoadAll(resourceSource = resourceSource)
    val projectTools = projectConfig?.let(::projectToolConfigs).orEmpty()
    if (projectTools.any { it.mode == ToolYamlConfig.Mode.TOOLS }) {
      val unsupportedCount = projectTools.count { it.mode == ToolYamlConfig.Mode.TOOLS }
      Console.log(
        "$logPrefix Ignoring $unsupportedCount workspace YAML-defined tool(s) from " +
          "trailblaze.yaml for desktop discovery; only class-backed project tools are wired here today.",
      )
    }
    return discovered + ToolYamlLoader.loadFromConfigs(projectTools)
  }

  private fun projectTargetConfigs(projectConfig: TrailblazeProjectConfig): List<AppTargetYamlConfig> =
    projectConfig.targets.map { entry ->
      when (entry) {
        is TargetEntry.Inline -> entry.config
        else -> error("Expected resolved target entries from trailblaze.yaml discovery")
      }
    }

  private fun projectToolSetConfigs(projectConfig: TrailblazeProjectConfig): List<ToolSetYamlConfig> =
    projectConfig.toolsets.map { entry ->
      when (entry) {
        is ToolsetEntry.Inline -> entry.config
        else -> error("Expected resolved toolset entries from trailblaze.yaml discovery")
      }
    }

  private fun projectToolConfigs(projectConfig: TrailblazeProjectConfig): List<ToolYamlConfig> =
    projectConfig.tools.map { entry ->
      when (entry) {
        is ToolEntry.Inline -> entry.config
        else -> error("Expected resolved tool entries from trailblaze.yaml discovery")
      }
    }

  private fun mergeTargets(
    discoveredTargets: Set<TrailblazeHostAppTarget>,
    projectTargets: Set<TrailblazeHostAppTarget>,
  ): Set<TrailblazeHostAppTarget> {
    val merged = discoveredTargets.associateBy { it.id }.toMutableMap()
    projectTargets.forEach { merged[it.id] = it }
    return merged.values.toSet()
  }
}
