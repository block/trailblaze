package xyz.block.trailblaze.config.project

import java.io.File
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.llm.config.BuiltInProviderConfig
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console

/**
 * Loads and resolves a `trailblaze.yaml` file into a [TrailblazeProjectConfig].
 *
 * Runtime now uses this loader for workspace-root configuration discovery: it parses the
 * schema, resolves `ref:` pointers to external YAML files, and hands the resolved config to
 * downstream target / toolset / tool loaders. A file found at load time triggers a DEBUG log
 * so the active workspace remains visible in logs.
 *
 * ## Ref path resolution
 *
 * Refs inside `trailblaze.yaml` are anchor-relative — i.e., resolved against the
 * directory containing `trailblaze.yaml` (`trails/config/` in the project-local
 * workspace layout). This matches the plan's path-resolution rules.
 *
 * - `targets/my-app.yaml`       → `<workspace>/config/targets/my-app.yaml`
 * - `/targets/my-app.yaml`      → `<workspace>/config/targets/my-app.yaml` (leading `/` stripped)
 * - `C:\absolute\path.yaml`     → used as-is on Windows
 *
 * Unix-style OS-absolute paths (e.g. `/Users/...`) are not an escape hatch from
 * `trailblaze.yaml` — they resolve as anchor-relative. This matches the plan's
 * "platform-appropriate absolute form" note.
 */
object TrailblazeProjectConfigLoader {

  /** The single config filename at a workspace root. Proxied from [TrailblazeConfigPaths]. */
  const val CONFIG_FILENAME = TrailblazeConfigPaths.CONFIG_FILENAME

  /**
   * Shared YAML instance. Reuses [TrailblazeConfigYaml.instance] so this loader stays
   * consistent with the other config loaders if parsing flags are adjusted centrally.
   */
  internal val yaml = TrailblazeConfigYaml.instance

  /**
   * Reads `trailblaze.yaml` at [configFile], decodes it, and returns a
   * [LoadedTrailblazeProjectConfig] bundle that preserves both the raw parse result
   * (refs intact) and the source file for downstream path resolution.
   *
   * Returns null when the file does not exist. Throws [TrailblazeProjectConfigException]
   * on parse failure or schema violations.
   */
  fun load(configFile: File): LoadedTrailblazeProjectConfig? {
    if (!configFile.exists()) return null
    val raw = parseFile(configFile)
    Console.log("Loaded trailblaze.yaml from ${configFile.absolutePath}")
    return LoadedTrailblazeProjectConfig(raw = raw, sourceFile = configFile)
  }

  /**
   * Resolves every [TargetEntry.Ref], [ToolsetEntry.Ref], [ToolEntry.Ref], and
   * [ProviderEntry.Ref] against [loaded]'s source directory and returns a new config with
   * all entries promoted to their `Inline` form.
   *
   * Throws [TrailblazeProjectConfigException] if a referenced file is missing or fails
   * to parse.
   *
   * **Classpath behavior — changed in this PR.** Internally calls
   * [resolveInternal] with `includeClasspathPacks = true`, so workspace packs declaring
   * `dependencies: [trailblaze]` (or any other framework-shipped pack) resolve their
   * deps against the JVM classpath. Previously this defaulted to `false`, which silently
   * dropped any workspace pack that depended on the framework stdlib and broke the
   * production runtime path used by [loadResolved] / [TrailblazeWorkspaceConfigResolver].
   *
   * If you need the historical "isolated workspace view" (no classpath packs in scope —
   * appropriate for unit tests that want to enumerate ONLY what's in the workspace),
   * call [resolveInternal] directly with `includeClasspathPacks = false`. That escape
   * hatch is intentional; it just isn't the default any more.
   */
  fun resolveRefs(loaded: LoadedTrailblazeProjectConfig): TrailblazeProjectConfig =
    resolveInternal(loaded, includeClasspathPacks = true).projectConfig

  /**
   * Resolves [loaded] into a [TrailblazeResolvedConfig] that includes runtime artifacts
   * surfaced from packs — today: waypoints. Use this when callers need access to
   * pack-bundled non-schema data; otherwise [resolveRefs] is sufficient.
   *
   * ## Pack discovery sources
   *
   * Two sources contribute pack artifacts, in precedence order from base to override:
   *  1. Classpath-bundled packs (auto-discovered under `trailblaze-config/packs/<id>/pack.yaml`)
   *  2. Workspace `packs:` entries in `trailblaze.yaml`
   *
   * When the same pack `id` appears in both sources, the workspace pack wholesale
   * shadows the classpath pack — workspace authors can override framework-shipped
   * packs without having to fork them. See [TrailblazeResolvedConfig] for the
   * documented precedence rule.
   *
   * Set [includeClasspathPacks] to `false` to suppress classpath discovery — used by
   * unit tests that need an isolated workspace view.
   */
  fun resolveRuntime(
    loaded: LoadedTrailblazeProjectConfig,
    includeClasspathPacks: Boolean = true,
  ): TrailblazeResolvedConfig = resolveInternal(loaded, includeClasspathPacks)

  private fun resolveInternal(
    loaded: LoadedTrailblazeProjectConfig,
    includeClasspathPacks: Boolean,
  ): TrailblazeResolvedConfig {
    val anchor = loaded.sourceFile.parentFile ?: File(".")
    val raw = loaded.raw
    val resolvedTargets = raw.targets.map { resolveTargetEntry(it, anchor) }
    val resolvedToolsets = raw.toolsets.map { resolveToolsetEntry(it, anchor) }
    val resolvedTools = raw.tools.map { resolveToolEntry(it, anchor) }
    val packArtifacts = resolvePackArtifacts(raw.packs, anchor, includeClasspathPacks)
    val projectConfig = TrailblazeProjectConfig(
      defaults = raw.defaults,
      packs = packArtifacts.workspaceRefs,
      targets = mergeById(
        base = packArtifacts.targets,
        overrides = resolvedTargets,
        idSelector = AppTargetYamlConfig::id,
      ).map(TargetEntry::Inline),
      toolsets = mergeById(
        base = packArtifacts.toolsets,
        overrides = resolvedToolsets,
        idSelector = ToolSetYamlConfig::id,
      ).map(ToolsetEntry::Inline),
      tools = mergeById(
        base = packArtifacts.tools,
        overrides = resolvedTools,
        idSelector = ToolYamlConfig::id,
      ).map(ToolEntry::Inline),
      providers = raw.providers.map { entry ->
        when (entry) {
          is ProviderEntry.Inline -> entry
          is ProviderEntry.Ref -> ProviderEntry.Inline(
            loadRef(entry.path, anchor, BuiltInProviderConfig.serializer(), "provider"),
          )
        }
      },
      llm = raw.llm,
    )
    return TrailblazeResolvedConfig(
      projectConfig = projectConfig,
      waypoints = packArtifacts.waypoints,
    )
  }

  /**
   * Convenience: [load] + [resolveRefs] in one call. Returns the resolved
   * [TrailblazeProjectConfig] schema only — classpath-bundled packs are NOT discovered
   * and pack-bundled waypoints are NOT surfaced. Use this when you only need the
   * workspace's declared targets/toolsets/tools/providers and no runtime artifacts.
   *
   * Callers that need pack-bundled waypoints (or any future pack-bundled runtime
   * artifact like routes/trails) should call [loadResolvedRuntime] instead, which
   * returns a [TrailblazeResolvedConfig] wrapper carrying both the schema and the
   * resolved runtime artifacts.
   */
  fun loadResolved(configFile: File): TrailblazeProjectConfig? =
    load(configFile)?.let(::resolveRefs)

  /**
   * Convenience: [load] + [resolveRuntime] in one call. Returns a
   * [TrailblazeResolvedConfig] wrapper that bundles the resolved
   * [TrailblazeProjectConfig] schema together with classpath+workspace pack-bundled
   * runtime artifacts (today: waypoints; later: routes/trails).
   *
   * Compared to [loadResolved]: this entry point ALSO discovers classpath-bundled
   * packs (controlled by [includeClasspathPacks]) and surfaces their `waypoints:`
   * resolution on the returned wrapper. Use this for runtime code paths
   * (CLI commands, agent runtime). Use [loadResolved] for tests that need an
   * isolated workspace view, or for legacy callers that only need the schema.
   */
  fun loadResolvedRuntime(
    configFile: File,
    includeClasspathPacks: Boolean = true,
  ): TrailblazeResolvedConfig? =
    load(configFile)?.let { resolveRuntime(it, includeClasspathPacks) }

  private fun parseFile(file: File): TrailblazeProjectConfig {
    return try {
      val content = file.readText()
      if (content.isBlank()) return TrailblazeProjectConfig()
      yaml.decodeFromString(TrailblazeProjectConfig.serializer(), content)
    } catch (e: IOException) {
      throw TrailblazeProjectConfigException(
        "Failed to read ${file.absolutePath}: ${e.message}",
        e,
      )
    } catch (e: SerializationException) {
      throw TrailblazeProjectConfigException(
        "Failed to parse ${file.absolutePath}: ${e.message}",
        e,
      )
    } catch (e: IllegalArgumentException) {
      throw TrailblazeProjectConfigException(
        "Invalid ${file.absolutePath}: ${e.message}",
        e,
      )
    }
  }

  internal fun resolveTargetEntry(entry: TargetEntry, anchor: File): AppTargetYamlConfig =
    when (entry) {
      is TargetEntry.Inline -> entry.config
      is TargetEntry.Ref -> loadRef(entry.path, anchor, AppTargetYamlConfig.serializer(), "target")
    }

  internal fun resolveToolsetEntry(entry: ToolsetEntry, anchor: File): ToolSetYamlConfig =
    when (entry) {
      is ToolsetEntry.Inline -> entry.config
      is ToolsetEntry.Ref -> loadRef(entry.path, anchor, ToolSetYamlConfig.serializer(), "toolset")
    }

  internal fun resolveToolEntry(entry: ToolEntry, anchor: File): ToolYamlConfig =
    when (entry) {
      is ToolEntry.Inline -> entry.config
      is ToolEntry.Ref -> loadRef(entry.path, anchor, ToolYamlConfig.serializer(), "tool")
    }

  internal fun resolveProviderEntry(entry: ProviderEntry, anchor: File): BuiltInProviderConfig =
    when (entry) {
      is ProviderEntry.Inline -> entry.config
      is ProviderEntry.Ref -> loadRef(entry.path, anchor, BuiltInProviderConfig.serializer(), "provider")
    }

  internal fun <T> loadRef(
    refPath: String,
    anchor: File,
    serializer: KSerializer<T>,
    entryLabel: String,
  ): T {
    val file = resolveRefFile(refPath, anchor)
    if (!file.exists()) {
      throw TrailblazeProjectConfigException(
        "Referenced $entryLabel file not found: $refPath (resolved to ${file.absolutePath})",
      )
    }
    return try {
      val content = file.readText()
      yaml.decodeFromString(serializer, content)
    } catch (e: IOException) {
      throw TrailblazeProjectConfigException(
        "Failed to read $entryLabel ref '$refPath' at ${file.absolutePath}: ${e.message}",
        e,
      )
    } catch (e: SerializationException) {
      throw TrailblazeProjectConfigException(
        "Failed to parse $entryLabel ref '$refPath' at ${file.absolutePath}: ${e.message}",
        e,
      )
    } catch (e: IllegalArgumentException) {
      throw TrailblazeProjectConfigException(
        "Invalid $entryLabel ref '$refPath' at ${file.absolutePath}: ${e.message}",
        e,
      )
    }
  }

  /**
   * Resolves a ref path against [anchor] per the Phase 1 rules above.
   *
   * Leading-`/` handling runs before [File.isAbsolute] because on Unix
   * `File("/foo").isAbsolute` is true and would otherwise swallow anchor-relative
   * paths. On Windows `/foo` is not absolute, but the same stripping is harmless.
   *
   * Parent-traversal refs (`../shared/targets.yaml`) are intentionally allowed —
   * they're legitimate in monorepo layouts where multiple workspaces share a common
   * targets/toolsets directory. `trailblaze.yaml` is commit-owned and trusted, so a
   * containment check would block valid uses without preventing any real threat.
   * This design decision should be revisited only if Trailblaze ever accepts config
   * from an untrusted source.
   */
  internal fun resolveRefFile(refPath: String, anchor: File): File {
    if (refPath.startsWith("/")) {
      return File(anchor, refPath.removePrefix("/"))
    }
    val asFile = File(refPath)
    if (asFile.isAbsolute) return asFile
    return File(anchor, refPath)
  }

  private fun resolvePackArtifacts(
    packRefs: List<String>,
    anchor: File,
    includeClasspathPacks: Boolean,
  ): ResolvedPackArtifacts {
    val workspaceLoad = if (packRefs.isEmpty()) {
      TrailblazePackManifestLoader.LoadResult(emptyList(), emptyList())
    } else {
      TrailblazePackManifestLoader.loadAllResilient(packRefs, anchor)
    }
    workspaceLoad.failures.forEach { failure ->
      Console.log(
        "Warning: Failed to load pack '${failure.requestedPath}': ${failure.cause.message}",
      )
    }

    val classpathManifests = if (includeClasspathPacks) {
      TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
    } else {
      emptyList()
    }

    // Workspace pack ids wholesale shadow same-id classpath packs. See the precedence
    // doc on TrailblazeResolvedConfig.
    val workspacePackIds = workspaceLoad.definitions.mapTo(mutableSetOf()) { it.manifest.manifest.id }
    val effectiveClasspathManifests = classpathManifests.filter { it.manifest.id !in workspacePackIds }

    // Order matters: classpath packs first (base), workspace packs second (override).
    // We pair each manifest with its requesting workspace ref (or null for classpath
    // packs) so the post-resolution `successfulWorkspaceRefs` list can be populated
    // without leaking workspace-vs-classpath origin into LoadedTrailblazePackManifest.
    val orderedManifests: List<Pair<LoadedTrailblazePackManifest, String?>> =
      effectiveClasspathManifests.map { it to null } +
        workspaceLoad.definitions.map { it.manifest to it.requestedRef }

    // First pass: per-pack sibling resolution into [ResolvedPack]. A pack whose siblings
    // fail to resolve drops out entirely (atomic-per-pack), but doesn't take siblings
    // down with it.
    val resolvedPacks = mutableListOf<ResolvedPack>()
    orderedManifests.forEach { (loadedManifest, workspaceRef) ->
      try {
        resolvedPacks += resolvePackSiblings(loadedManifest, workspaceRef)
      } catch (e: TrailblazeProjectConfigException) {
        // Surface the nested cause class when present so authors can distinguish
        // "sibling file not found" (most common) from "sibling file malformed YAML",
        // "containment-rule violation", and other failure shapes without grepping logs.
        val causeHint = e.cause?.let { " (${it::class.simpleName})" }.orEmpty()
        Console.log(
          "Warning: Failed to resolve pack '${loadedManifest.manifest.id}' " +
            "from ${loadedManifest.source.describe()}: ${e.message}$causeHint",
        )
      }
    }

    // Second pass: emit targets with dependency-graph defaults applied. Each pack's
    // bundled artifacts (toolsets/tools/waypoints) contribute to the global pool
    // regardless of whether the pack itself defines a runnable target.
    //
    // ## `successfulWorkspaceRefs` semantics
    //
    // Two distinct "this pack contributed" notions live in this loop, and the
    // workspace-refs list deliberately reflects the second:
    //  1. Pool contributions (toolsets/tools/waypoints) — UNCONDITIONAL once sibling
    //     resolution succeeded (pass 1). Even a pack with no `target:` block, or one
    //     whose dep graph fails to resolve, still ships its bundled files into the
    //     workspace pool.
    //  2. Workspace-ref success — recorded only when this pack's *target* made it into
    //     the resolved config. A pack with no `target:` counts (it has no target to
    //     fail). A pack whose target failed dep resolution does NOT count, because the
    //     caller-visible projectConfig.packs: list is meant to answer "which workspace
    //     packs landed as runnable targets?", not "which workspace files were touched."
    //     Partial-failure packs (siblings contributed, target dropped) show up as
    //     not-success so they're visibly distinct from clean loads.
    val packsById = resolvedPacks.associateBy { it.manifest.id }
    val targets = mutableListOf<AppTargetYamlConfig>()
    val toolsets = mutableListOf<ToolSetYamlConfig>()
    val tools = mutableListOf<ToolYamlConfig>()
    val waypoints = mutableListOf<WaypointDefinition>()
    val successfulWorkspaceRefs = mutableListOf<String>()

    resolvedPacks.forEach { pack ->
      // (1) Pool contributions — unconditional, see the doc above.
      toolsets += pack.toolsets
      tools += pack.tools
      waypoints += pack.waypoints

      // A pack that doesn't declare a `target:` block isn't surfaced as a runnable
      // target. Workspace ref still counts as successful — there was no target to fail
      // and the pack's siblings landed in the pool.
      val ownTarget = pack.target
      if (ownTarget == null) {
        if (pack.workspaceRef != null) successfulWorkspaceRefs += pack.workspaceRef
        return@forEach
      }

      // Walk the dep graph and apply closest-wins defaults inheritance for this target.
      // Cycle / missing-dep failures isolate to this consumer pack — sibling packs are
      // unaffected. Per the `successfulWorkspaceRefs` doc above, this branch's failure
      // *does not* mark the workspace ref successful even though pool contributions
      // already landed at (1).
      val finalTarget = try {
        PackDependencyResolver.resolveTarget(
          ownTarget = ownTarget,
          ownDependencies = pack.manifest.dependencies,
          packsById = packsById,
          rootPackId = pack.manifest.id,
        )
      } catch (e: TrailblazeProjectConfigException) {
        Console.log(
          "Warning: Failed dependency resolution for pack '${pack.manifest.id}' " +
            "from ${pack.source.describe()}: ${e.message}",
        )
        return@forEach
      }

      targets += finalTarget
      if (pack.workspaceRef != null) successfulWorkspaceRefs += pack.workspaceRef
    }

    return ResolvedPackArtifacts(
      workspaceRefs = successfulWorkspaceRefs,
      targets = targets,
      toolsets = toolsets,
      tools = tools,
      waypoints = waypoints,
    )
  }

  private fun resolvePackSiblings(
    loadedManifest: LoadedTrailblazePackManifest,
    workspaceRef: String?,
  ): ResolvedPack {
    val resolvedScriptedTools: List<InlineScriptToolConfig> =
      loadedManifest.manifest.target?.tools.orEmpty()
        .map { path ->
          loadPackSibling(path, loadedManifest.source, PackScriptedToolFile.serializer(), "pack scripted tool")
            .toInlineScriptToolConfig()
        }
    val target = loadedManifest.manifest.target
      ?.toAppTargetYamlConfig(
        defaultId = loadedManifest.manifest.id,
        resolvedTools = resolvedScriptedTools,
      )
    val resolvedToolsets = loadedManifest.manifest.toolsets
      .map { path -> loadPackSibling(path, loadedManifest.source, ToolSetYamlConfig.serializer(), "pack toolset") }
    val resolvedTools = loadedManifest.manifest.tools
      .map { path -> loadPackSibling(path, loadedManifest.source, ToolYamlConfig.serializer(), "pack tool") }
    val resolvedWaypoints = loadedManifest.manifest.waypoints
      .map { path -> loadPackSibling(path, loadedManifest.source, WaypointDefinition.serializer(), "pack waypoint") }
    return ResolvedPack(
      manifest = loadedManifest.manifest,
      source = loadedManifest.source,
      target = target,
      toolsets = resolvedToolsets,
      tools = resolvedTools,
      waypoints = resolvedWaypoints,
      workspaceRef = workspaceRef,
    )
  }

  private fun <T> loadPackSibling(
    refPath: String,
    source: PackSource,
    serializer: KSerializer<T>,
    entryLabel: String,
  ): T = try {
    val content = source.readSibling(refPath)
      ?: throw TrailblazeProjectConfigException(
        "Referenced $entryLabel file not found: $refPath (in ${source.describe()})",
      )
    yaml.decodeFromString(serializer, content)
  } catch (e: TrailblazeProjectConfigException) {
    throw e
  } catch (e: SerializationException) {
    throw TrailblazeProjectConfigException(
      "Failed to parse $entryLabel ref '$refPath' from ${source.describe()}: ${e.message}",
      e,
    )
  } catch (e: IllegalArgumentException) {
    // From PackSource.readSibling containment validation OR YAML decode constraints.
    // Either is a user-side error against this pack and should drop just the pack —
    // not crash sibling-pack resolution. The atomic-per-pack catch in
    // resolvePackArtifacts logs and continues.
    throw TrailblazeProjectConfigException(
      "Invalid $entryLabel ref '$refPath' from ${source.describe()}: ${e.message}",
      e,
    )
  }

  private fun <T> mergeById(
    base: List<T>,
    overrides: List<T>,
    idSelector: (T) -> String,
  ): List<T> {
    val merged = linkedMapOf<String, T>()
    base.forEach { merged[idSelector(it)] = it }
    overrides.forEach { merged[idSelector(it)] = it }
    return merged.values.toList()
  }

  private data class ResolvedPackArtifacts(
    /**
     * Successfully resolved workspace `packs:` ref strings (only). Populates
     * `projectConfig.packs:` so the resolved config can record which workspace packs
     * actually contributed (broken refs are excluded). Classpath-discovered packs are
     * NOT included here — they're internal framework defaults, not user-declared.
     */
    val workspaceRefs: List<String> = emptyList(),
    val targets: List<AppTargetYamlConfig> = emptyList(),
    val toolsets: List<ToolSetYamlConfig> = emptyList(),
    val tools: List<ToolYamlConfig> = emptyList(),
    val waypoints: List<WaypointDefinition> = emptyList(),
  )

  /**
   * A pack manifest after sibling refs are resolved but before dependency-graph
   * defaults are applied. The first pass of [resolvePackArtifacts] produces these;
   * the second pass walks the dep graph using [PackDependencyResolver] and emits
   * final [AppTargetYamlConfig] entries.
   */
  internal data class ResolvedPack(
    val manifest: TrailblazePackManifest,
    val source: PackSource,
    val target: AppTargetYamlConfig?,
    val toolsets: List<ToolSetYamlConfig>,
    val tools: List<ToolYamlConfig>,
    val waypoints: List<WaypointDefinition>,
    val workspaceRef: String?,
  )
}

/**
 * Pairs a decoded [TrailblazeProjectConfig] with the file it came from. The source file
 * is the anchor for resolving any remaining `ref:` pointers, as well as (in later
 * phases) the workspace root for trail discovery.
 */
data class LoadedTrailblazeProjectConfig(
  val raw: TrailblazeProjectConfig,
  val sourceFile: File,
)

/** Thrown by [TrailblazeProjectConfigLoader] on parse failure or missing refs. */
class TrailblazeProjectConfigException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
