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
import xyz.block.trailblaze.config.project.ResolvedTrailblazeWorkspaceConfig
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.CompositeConfigResourceSource
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.FilesystemConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Paths
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
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
      val resolvedConfig = loadResolvedConfigLeniently(workspaceConfig, logPrefix)
      val projectConfig = resolvedConfig?.projectConfig
      val customToolClasses = loadCustomToolClasses(
        resourceSource = source,
        projectConfig = projectConfig,
        logPrefix = logPrefix,
      )
      // Register workspace-discovered Mode.TOOLS YAML configs (pure-YAML composed tools)
      // BEFORE building the resolver. The resolver snapshots
      // `TrailblazeSerializationInitializer.buildYamlDefinedTools().keys` at construction,
      // so a `<workspace>/trails/config/tools/foo.tool.yaml` that isn't registered by this
      // point can't be referenced by name from a toolset (`Unknown tool name 'foo'`
      // warning at toolset load). The discovered map is the union of classpath + workspace
      // (the composite resource source layers them); the serialization initializer dedupes
      // against its own classpath cache so re-registering the same classpath tools is a
      // no-op. See `registerWorkspaceYamlTools` for the overlay semantics.
      registerWorkspaceYamlTools(source, logPrefix)
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
      // a target whose `platforms.<p>.tool_sets:` references a `<workspace>/trails/config/
      // toolsets/<id>.yaml` would resolve to the alwaysEnabled classpath subset only, and
      // workspace YAML tool names listed by that toolset would never reach the session repo's
      // `registeredYamlToolNames` — surfacing as "Unknown tool" at dispatch. Same overlay
      // shape as `TrailblazeSerializationInitializer.registerWorkspaceYamlTools` above.
      registerWorkspaceToolSets(toolSets, logPrefix)

      val discoveredTargets = AppTargetYamlLoader.discoverAndLoadAll(
        toolNameResolver = resolver,
        availableToolSets = toolSets,
        companions = companions,
        resourceSource = source,
      )
      // Workspace-resolved targets come from the pack pool — a list of fully-resolved
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

  private fun loadResolvedConfigLeniently(
    workspaceConfig: ResolvedTrailblazeWorkspaceConfig,
    logPrefix: String,
  ): TrailblazeResolvedConfig? {
    return try {
      workspaceConfig.loadResolvedRuntime()
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
    // Note: this returns only class-backed (`Mode.CLASS`) tools. TOOLS-mode entries that
    // appear in `projectConfig.tools` (pack-resolved `*.tool.yaml` files flattened into the
    // inline list by the project-config resolver) flow through `registerWorkspaceYamlTools`
    // instead, which threads them into the YAML-defined tool overlay. No warning needed here:
    // the workspace YAML-defined tool registration line covers the operational signal.
    return discovered + ToolYamlLoader.loadFromConfigs(projectTools)
  }

  /**
   * Discovers workspace-side `Mode.TOOLS` YAML configs (pure-YAML composed tools — files
   * under `<workspace>/trails/config/tools/<id>.tool.yaml` and `<pack>/tools/<id>.tool.yaml`)
   * and hands them to [TrailblazeSerializationInitializer.registerWorkspaceYamlTools] so they
   * become resolvable by name from toolsets and reachable at runtime by the YAML-tool
   * executor (`TrailblazeToolRepo.toolCallToTrailblazeTool`).
   *
   * **Classpath/workspace separation.** The composite resource source walks both layers,
   * so `ToolYamlLoader.discoverYamlDefinedTools(source)` surfaces classpath-bundled tools
   * (`pressBack`, `eraseText`) alongside workspace files. Pure classpath entries get
   * filtered out via the `classpathConfigs[k] == v` equality check — they already live
   * in the serialization initializer's classpath cache, and registering them as workspace
   * overlay entries would force-flip their `requires_host` flag, silently changing their
   * dispatch path through the host-expansion gate in
   * `TrailblazeMcpBridgeImpl.executeTrailblazeTool`.
   *
   * **Override contract preserved.** A workspace `<id>.tool.yaml` that intentionally
   * collides with a classpath-shipped id MUST reach the overlay so the union's
   * last-write-wins semantics in `buildYamlDefinedTools()` can apply the override. The
   * equality filter distinguishes "pure classpath" (configs match exactly, drop) from
   * "workspace override of a classpath id" (configs differ, keep). Pinned by
   * `workspace tool with classpath-colliding id overrides the bundled one`.
   *
   * **Re-discovery soundness.** On a hypothetical second `discover()` call in the same
   * JVM, the classpath snapshot is stable (`getClasspathYamlDefinedTools` reads the
   * lazy-init classpath cache, not the overlay-merged view), so the equality check stays
   * sound across passes. Pinned by `second discover pass preserves the previously-
   * registered workspace overlay`.
   */
  private fun registerWorkspaceYamlTools(
    resourceSource: ConfigResourceSource,
    logPrefix: String,
  ) {
    val allDiscovered = try {
      ToolYamlLoader.discoverYamlDefinedTools(resourceSource)
    } catch (e: Exception) {
      Console.error(
        "$logPrefix Workspace YAML-defined tool discovery failed: ${e.message}. " +
          "Workspace `*.tool.yaml` files will not be registered.\n${e.stackTraceToString()}",
      )
      return
    }
    // Filter out configs that came from the classpath unchanged. `allDiscovered` was
    // walked from a composite resource source (classpath + workspace filesystem), so
    // classpath-bundled `*.tool.yaml` files (`pressBack`, `eraseText`, etc.) show up
    // alongside any workspace-authored ones. If we registered the full set, the
    // `requires_host = null → true` mutation below would flip THEIR host-vs-device
    // dispatch flag too — silently routing classpath tools through the host-expansion
    // path in `TrailblazeMcpBridgeImpl.executeTrailblazeTool` with a synthesized empty
    // `AgentMemory()` context instead of their normal on-device run with the live
    // session. For trivial compositions the outcome is identical, but any classpath
    // tool that uses `{{memory.X}}` token interpolation would substitute empty values.
    //
    // Equality check `classpathConfigs[k] == v` separates "pure classpath" from
    // "workspace override of a classpath id" — if the workspace authored a real
    // override (different content), the configs differ and the workspace version stays
    // in the overlay. If the workspace doesn't touch a tool, the discovered config
    // exactly matches the classpath one and gets filtered out. Preserves the override
    // contract (pinned by `workspace tool with classpath-colliding id overrides the
    // bundled one`) while leaving non-overridden classpath tools untouched.
    val classpathConfigs = TrailblazeSerializationInitializer.getClasspathYamlDefinedTools()
    val workspaceOnly = allDiscovered.filterNot { (k, v) -> classpathConfigs[k] == v }
    // Force `requires_host = true` on workspace YAML configs whose author left it null.
    // Workspace `*.tool.yaml` files live only on the host filesystem — the device's
    // classpath doesn't have them, so RPC dispatch would fail with "Unknown tool 'X' is
    // not registered". The new host-expansion gate in `TrailblazeMcpBridgeImpl
    // .executeTrailblazeTool` honors this flag and expands the tool host-side, then
    // dispatches each child primitive through per-child routing. Authors who explicitly
    // set `requires_host: false` (because they're shipping the same config to the
    // on-device runner out-of-band) keep their explicit value — the override only
    // fires when the field is null.
    val configsToRegister = workspaceOnly.mapValues { (_, config) ->
      if (config.requiresHost == null) config.copy(requiresHost = true) else config
    }
    TrailblazeSerializationInitializer.registerWorkspaceYamlTools(configsToRegister)
    if (configsToRegister.isNotEmpty()) {
      val overrides = configsToRegister.keys.intersect(classpathConfigs.keys)
      val overrideSuffix = if (overrides.isNotEmpty()) {
        " (overrides classpath: ${overrides.map { it.toolName }.sorted()})"
      } else {
        ""
      }
      Console.log(
        "$logPrefix Registered ${configsToRegister.size} workspace YAML-defined tool(s): " +
          "${configsToRegister.keys.map { it.toolName }.sorted()}$overrideSuffix",
      )
    }
  }

  /**
   * Converts the already-resolved workspace-aware toolset map (classpath + filesystem,
   * computed at line ~102 of `discover()` with the composite resource source) into
   * `TrailblazeToolSetCatalog` entries and registers them as the workspace overlay.
   * Mirrors the shape of [registerWorkspaceYamlTools] above — full map registered, log
   * line reports the workspace-only vs classpath-override breakdown using the catalog's
   * `getClasspathEntries()` accessor to avoid round-tripping the overlay through the
   * merged-view accessor.
   */
  private fun registerWorkspaceToolSets(
    toolSets: Map<String, ResolvedToolSet>,
    logPrefix: String,
  ) {
    val entries = toolSets.values.map { it.toCatalogEntry() }
    TrailblazeToolSetCatalog.registerWorkspaceToolSets(entries)
    if (entries.isEmpty()) return
    val classpathIds = TrailblazeToolSetCatalog.getClasspathEntries().map { it.id }.toSet()
    val overrides = entries.map { it.id }.toSet().intersect(classpathIds)
    val workspaceOnlyIds = entries.map { it.id }.toSet() - classpathIds
    val overrideSuffix = if (overrides.isNotEmpty()) {
      " (overrides classpath: ${overrides.sorted()})"
    } else {
      ""
    }
    Console.log(
      "$logPrefix Registered ${entries.size} toolset(s) into catalog overlay " +
        "(workspace-only: ${workspaceOnlyIds.sorted()})$overrideSuffix",
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

  private fun mergeTargets(
    discoveredTargets: Set<TrailblazeHostAppTarget>,
    projectTargets: Set<TrailblazeHostAppTarget>,
  ): Set<TrailblazeHostAppTarget> {
    val merged = discoveredTargets.associateBy { it.id }.toMutableMap()
    projectTargets.forEach { merged[it.id] = it }
    return merged.values.toSet()
  }
}
