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
import xyz.block.trailblaze.config.project.ToolEntry
import xyz.block.trailblaze.config.project.ToolsetEntry
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeResolvedConfig
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.ResolvedTrailblazeWorkspaceConfig
import xyz.block.trailblaze.config.project.workspaceRootFromConfigDir
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.workspaceLayeredConfigResourceSource
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Paths
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
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
   *
   * [failFast] picks which of two incompatible callers is being served. The daemon-startup seed
   * needs a set no matter what (the app has to boot), so it takes the default and gets
   * [defaultFallback] on error. A caller that REPLACES an existing set needs to tell "this
   * workspace declares nothing" apart from "discovery blew up", because those want opposite
   * outcomes — install the fallback vs. keep what's already there. Passing true rethrows instead
   * of masking the failure as a one-target result. An empty-but-successful discovery still
   * returns [defaultFallback] either way; only the error path differs.
   *
   * It also turns off the internal leniency that lets a pass continue past a malformed
   * `trailblaze.yaml`. That produces a PARTIAL set rather than the
   * fallback, which is the more dangerous shape for a replacing caller: it looks like a clean
   * discovery, so it installs silently and then matches itself on the follow-up drift check,
   * clearing the restart nudge that was the last warning the user would have gotten.
   */
  fun discover(
    companions: Map<String, AppTargetCompanion> = emptyMap(),
    workspaceConfigProvider: () -> ResolvedTrailblazeWorkspaceConfig = {
      TrailblazeWorkspaceConfigResolver.resolve(Paths.get(""))
    },
    defaultFallback: TrailblazeHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    logPrefix: String = "[AppTargetDiscovery]",
    failFast: Boolean = false,
  ): Set<TrailblazeHostAppTarget> {
    return try {
      val workspaceConfig = workspaceConfigProvider()
      val source = workspaceLayeredConfigResourceSource(workspaceConfig.configDir, logPrefix)
      val resolvedConfig = loadResolvedWorkspaceConfig(workspaceConfig, logPrefix, failFast)
      val projectConfig = resolvedConfig?.projectConfig
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

      // Register the workspace-aware merged toolset map as a catalog overlay so dispatch-time
      // resolution (`TrailblazeMcpServer.ensureSessionScriptToolRuntime` calling
      // `TrailblazeToolSetCatalog.resolveForDriver(...)`) can find workspace-authored toolset
      // ids. The catalog's `defaultEntries()` cache is classpath-only; without this overlay,
      // a target whose `platforms.<p>.tool_sets:` references a
      // `<workspace>/trails/config/trailmaps/<id>/toolsets/<name>.yaml` would resolve to the
      // alwaysEnabled classpath subset only, and the tools that toolset lists would never reach
      // the session repo — surfacing as "Unknown tool" at dispatch.
      registerWorkspaceToolSets(toolSets, logPrefix)

      val discoveredTargets = AppTargetYamlLoader.discoverAndLoadAll(
        toolNameResolver = resolver,
        availableToolSets = toolSets,
        companions = companions,
        resourceSource = source,
      )
      // Workspace-resolved targets come from the trailmap pool — a list of fully-resolved
      // [AppTargetYamlConfig] sitting on TrailblazeResolvedConfig.targets, not on the
      // schema's `targets:` field (which is now just a list of ids).
      val projectTargets = resolvedConfig
        ?.targets
        ?.let {
          AppTargetYamlLoader.loadAllFromConfigs(
            configs = it,
            toolNameResolver = resolver,
            availableToolSets = toolSets,
            companions = companions,
            trailmapDirs = workspaceTrailmapDirs(resolvedConfig),
          )
        }
        .orEmpty()
      val targets = mergeTargets(discoveredTargets, projectTargets)
      Console.log("$logPrefix Discovered ${targets.size} app targets: ${targets.map { it.id }.sorted()}")

      targets.ifEmpty { setOf(defaultFallback) }
    } catch (e: Exception) {
      // A failFast caller rethrows to ITS caller, which logs the same exception with the context
      // that matters there (which reload, and what happened to the live set). Logging here too
      // would put the same stack trace in the daemon log twice.
      if (failFast) throw e
      // Route through Console.error so the daemon's logger sees it. `printStackTrace()`
      // goes to stderr, which the launcher discards — the trace would be invisible.
      Console.error("$logPrefix Error loading app targets from YAML: ${e.message}\n${e.stackTraceToString()}")
      setOf(defaultFallback)
    }
  }

  /**
   * A [discover] `workspaceConfigProvider` anchored at an explicitly-selected `trails/config`
   * directory (e.g. the one the user picked in Trail Runner settings, published via
   * `WorkspaceConfigDirHolder`), with fallback to the default CWD walk-up.
   *
   * When [configDirProvider] supplies a dir that carries its own `trailblaze.yaml`, the ENTIRE
   * workspace anchors there - anchor file plus declared targets/toolsets/tools - so live target
   * re-discovery reads the same workspace the create-target write used, rather than the daemon
   * launch CWD. Falls back to the CWD walk-up when no dir is supplied or it has no own
   * `trailblaze.yaml` (then we keep the legacy split: CWD anchor, overridden payload dir).
   */
  fun anchoredWorkspaceConfigProvider(
    configDirProvider: () -> File?,
  ): () -> ResolvedTrailblazeWorkspaceConfig = {
    val overrideConfigDir = configDirProvider()
    val anchored = overrideConfigDir
      ?.let { workspaceRootFromConfigDir(it) }
      ?.let { root ->
        ResolvedTrailblazeWorkspaceConfig(
          workspaceRoot = root,
          configFile = root.configFile.toFile(),
          configDir = overrideConfigDir,
        )
      }
    anchored ?: TrailblazeWorkspaceConfigResolver.resolve(Paths.get("")).let { resolved ->
      if (overrideConfigDir != null) resolved.copy(configDir = overrideConfigDir) else resolved
    }
  }

  private fun loadResolvedWorkspaceConfig(
    workspaceConfig: ResolvedTrailblazeWorkspaceConfig,
    logPrefix: String,
    failFast: Boolean,
  ): TrailblazeResolvedConfig? {
    return try {
      workspaceConfig.loadResolvedRuntime(
        scriptedToolEnrichment = resolveScriptedToolEnrichment(),
      )
    } catch (e: Exception) {
      // Leniency is wrong for a [failFast] caller. Ignoring the workspace's own config leaves a
      // classpath-only set that is indistinguishable from a successful discovery, which a caller
      // that REPLACES a live set would then install over the previous workspace's real targets —
      // and the follow-up drift check would compare that set against itself and clear the restart
      // nudge, so the user would be left with neither their targets nor a warning.
      if (failFast) throw e
      // Console.error (not Console.log) so this warning survives callers that wrap
      // discovery in `Console.runQuiet` to suppress informational chatter (e.g. CLI
      // `config show` / `config target`). Without this, a workspace with a malformed
      // `trailblaze.yaml` would silently list only classpath-fallback targets with no
      // hint why the user's custom targets disappeared.
      Console.error(
        "$logPrefix Ignoring workspace trailblaze.yaml due to load failure: " +
          "${e::class.simpleName}: ${e.message}",
      )
      null
    }
  }

  /**
   * Delegates to [xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment.Companion.resolveFromEnvironment]
   * — the shared resolver every host call site uses (also wired into
   * `WorkspaceCompileBootstrap`, `CompileCommand`, `TrailblazeHostYamlRunner`).
   */
  private fun resolveScriptedToolEnrichment(): xyz.block.trailblaze.config.project.ScriptedToolEnrichment? =
    xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment.resolveFromEnvironment()

  private fun loadCustomToolClasses(
    resourceSource: ConfigResourceSource,
    projectConfig: TrailblazeProjectConfig?,
    logPrefix: String,
  ): Map<ToolName, KClass<out TrailblazeTool>> {
    val discovered = ToolYamlLoader.discoverAndLoadAll(resourceSource = resourceSource)
    val projectTools = projectConfig?.let(::projectToolConfigs).orEmpty()
    // Returns only class-backed (`Mode.CLASS`) tools. `loadFromConfigs` validates each inline
    // entry and logs-and-skips one that fails, including a retired `tools:` body.
    return discovered + ToolYamlLoader.loadFromConfigs(projectTools)
  }

  /**
   * Resolves a workspace's on-disk toolsets and returns them merged over the classpath
   * snapshot, WITHOUT running target discovery and WITHOUT registering a catalog overlay.
   *
   * [discover] is the usual path, but it also loads targets, companions and custom tool classes
   * — work a deviceless caller has no use for. `trailblaze compile` / `trailblaze check` need
   * the toolsets because the typed-surface emitter expands a trailmap's
   * `platforms.<p>.tool_sets:` through [TrailblazeToolSetCatalog], whose default entries are the
   * classpath snapshot alone; they need nothing else [discover] produces. Returning the catalog
   * instead of mutating the process-global overlay keeps a one-shot CLI compile from leaving
   * state behind for a later session — or a later test — in the same JVM.
   *
   * **Scope: on-disk YAML only.** This walks the `toolsets/` and `tools/` directories under each
   * `trailmaps/<id>/`. Unlike [discover] it does NOT fold in a workspace
   * `trailblaze.yaml`'s inline `toolsets:` / `tools:`, nor CLASS-mode custom tool classes. That
   * matches what the compile path can see: both `TrailblazeCompiler.compile` and the CLI's
   * codegen re-resolution build a synthetic [TrailblazeProjectConfig], so `trailblaze.yaml` is
   * not read on that path at all and an inline toolset fails reference validation before it
   * could reach the emitter.
   *
   * Best-effort: a discovery failure logs and falls back to the classpath-only catalog rather
   * than taking the caller down, matching the log-and-continue posture of the rest of this
   * object.
   */
  fun workspaceToolSetCatalog(
    resourceSource: ConfigResourceSource,
    logPrefix: String = "[AppTargetDiscovery]",
  ): List<ToolSetCatalogEntry> = try {
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools(resourceSource = resourceSource)
    val toolSets = ToolSetYamlLoader.discoverAndLoadAll(
      toolNameResolver = resolver,
      resourceSource = resourceSource,
    )
    val entries = toolSets.values.map { it.toCatalogEntry() }
    logWorkspaceToolSets(entries, logPrefix, verb = "Resolved")
    TrailblazeToolSetCatalog.mergedWithClasspath(entries)
  } catch (e: Exception) {
    Console.error(
      "$logPrefix Workspace toolset resolution failed (${e::class.simpleName}: ${e.message}); " +
        "continuing with classpath toolsets only.\n${e.stackTraceToString()}",
    )
    TrailblazeToolSetCatalog.defaultEntries()
  }

  /**
   * Converts the already-resolved workspace-aware toolset map (classpath + filesystem,
   * computed at line ~102 of `discover()` with the composite resource source) into
   * `TrailblazeToolSetCatalog` entries and registers them as the workspace overlay. The full
   * map is registered; the log line reports the workspace-only vs classpath-override breakdown
   * using the catalog's
   * `getClasspathEntries()` accessor to avoid round-tripping the overlay through the
   * merged-view accessor.
   */
  private fun registerWorkspaceToolSets(
    toolSets: Map<String, ResolvedToolSet>,
    logPrefix: String,
  ) {
    val entries = toolSets.values.map { it.toCatalogEntry() }
    TrailblazeToolSetCatalog.registerWorkspaceToolSets(entries)
    logWorkspaceToolSets(entries, logPrefix, verb = "Registered")
  }

  /**
   * Shared breakdown log for the two callers that resolve workspace toolsets —
   * [registerWorkspaceToolSets] (installs the global overlay) and [workspaceToolSetCatalog]
   * (returns a scoped catalog). [verb] is the only difference, so the workspace-only vs
   * classpath-override accounting can't drift between them.
   */
  private fun logWorkspaceToolSets(
    entries: List<ToolSetCatalogEntry>,
    logPrefix: String,
    verb: String,
  ) {
    if (entries.isEmpty()) return
    val classpathIds = TrailblazeToolSetCatalog.getClasspathEntries().map { it.id }.toSet()
    val ids = entries.map { it.id }.toSet()
    val overrides = ids.intersect(classpathIds)
    val overrideSuffix = if (overrides.isNotEmpty()) {
      " (overrides classpath: ${overrides.sorted()})"
    } else {
      ""
    }
    Console.log(
      "$logPrefix $verb ${entries.size} toolset(s) from the layered config source " +
        "(workspace-only: ${(ids - classpathIds).sorted()})$overrideSuffix",
    )
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

  /**
   * Per target id, where its trailmap and every trailmap it depends on were loaded from: manifest
   * id → on-disk directory, or null for a classpath copy. See [YamlBackedHostAppTarget.trailmapDirs].
   */
  private fun workspaceTrailmapDirs(
    resolvedConfig: TrailblazeResolvedConfig,
  ): Map<String, Map<String, File?>> {
    val trailmapsById = resolvedConfig.resolvedTrailmaps.associateBy { it.manifest.id }
    return resolvedConfig.resolvedTrailmaps.mapNotNull { trailmap ->
      val targetId = trailmap.target?.id ?: return@mapNotNull null
      val dirs = linkedMapOf<String, File?>()
      val pending = ArrayDeque(listOf(trailmap.manifest.id))
      while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (id in dirs) continue
        val resolved = trailmapsById[id] ?: continue
        dirs[id] = (resolved.source as? TrailmapSource.Filesystem)?.trailmapDir
        pending.addAll(resolved.manifest.dependencies)
      }
      targetId to dirs
    }.toMap()
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
