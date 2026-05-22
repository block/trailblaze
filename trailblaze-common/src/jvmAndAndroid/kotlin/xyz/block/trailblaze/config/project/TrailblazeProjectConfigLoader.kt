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
    val resolvedToolsets = raw.toolsets.map { resolveToolsetEntry(it, anchor) }
    val resolvedTools = raw.tools.map { resolveToolEntry(it, anchor) }
    val packArtifacts = resolvePackArtifacts(raw.targets, anchor, includeClasspathPacks)
    val projectConfig = TrailblazeProjectConfig(
      defaults = raw.defaults,
      targets = packArtifacts.successfulTargetIds,
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
      targets = packArtifacts.targets,
      waypoints = packArtifacts.waypoints,
      resolvedPacks = packArtifacts.resolvedPacks,
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

  /**
   * Resolves the pack pool from the workspace's declared target ids (or via
   * auto-discovery if [declaredTargetIds] is empty), bringing transitive `dependencies:`
   * into scope along the way. Returns the resolved targets, plus the toolsets / tools /
   * waypoints contributed by every pack that landed in the pool.
   *
   * Workspace `<anchor>/packs/<id>/pack.yaml` is the resolution convention for both the
   * workspace-listed roots and any packs pulled in transitively via `dependencies:`. The
   * classpath is also a discovery source for both purposes (when [includeClasspathPacks]
   * is true): a target listed in workspace `targets:` may be classpath-bundled (e.g. the
   * framework `clock` target), and the same goes for any dependency a pack declares.
   *
   * ## Resolution flow
   *
   * 1. Determine the **root set** of pack ids — either the explicit [declaredTargetIds]
   *    list, or (when empty) every target pack discovered under `<anchor>/packs/` plus
   *    every classpath-bundled target pack. Library packs are not auto-discovered as
   *    roots — they only enter scope transitively via `dependencies:`.
   * 2. **Validate roots** are target packs. A workspace listing a library-pack id in
   *    `targets:` is rejected with a redirecting error message.
   * 3. **Walk transitive `dependencies:`** to build the full set of pack ids that need
   *    to load. Each id resolves via the workspace pack dir (`<anchor>/packs/<id>/`) or
   *    the classpath, with workspace shadowing the classpath on id collisions.
   * 4. **Strict dep-graph validation**: every pack's `dependencies:` must resolve to a
   *    pack id in the loaded pool. Unresolvable deps are aggregated and surfaced as a
   *    single consolidated error.
   * 5. **Resolve siblings** for each loaded pack — auto-discover operational tool YAMLs
   *    from `<pack>/tools/`, load declared toolsets and waypoints.
   * 6. **Apply per-target defaults inheritance** via `PackDependencyResolver` and emit
   *    the final `AppTargetYamlConfig` list.
   */
  private fun resolvePackArtifacts(
    declaredTargetIds: List<String>,
    anchor: File,
    includeClasspathPacks: Boolean,
  ): ResolvedPackArtifacts {
    val workspacePackDir = File(anchor, TrailblazeConfigPaths.PACKS_SUBDIR)

    val classpathManifests: List<LoadedTrailblazePackManifest> = if (includeClasspathPacks) {
      TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
    } else {
      emptyList()
    }
    val classpathById = classpathManifests.associateBy { it.manifest.id }

    // Step 1: figure out the root set of target-pack ids that need to load.
    val rootIds: List<String> = if (declaredTargetIds.isNotEmpty()) {
      declaredTargetIds
    } else {
      autoDiscoverTargetPackIds(workspacePackDir, classpathManifests)
    }

    // Step 2: load pack manifests transitively from the root set, walking
    // `dependencies:` and resolving each id via workspace pack dir → classpath fallback.
    // Atomic-per-pack on resolution failure (logged), strict on dep-graph violations.
    val loadCtx = PackLoadContext(
      workspacePackDir = workspacePackDir,
      classpathById = classpathById,
    )
    // Two distinct failure shapes for workspace-listed roots, handled differently:
    //   1. **Parse failure** of an existing pack manifest — atomic-per-pack drop with
    //      a logged warning (existing behavior, preserved). The pack file is there
    //      but malformed; sibling packs continue to load. The author already gets a
    //      console warning naming the offending pack and the parse error.
    //   2. **Hard-error** category errors — id refers to a library pack (cross-target
    //      reusable tooling, not a runnable target), or id resolves to nothing on
    //      disk and isn't classpath-bundled. These are config-level mistakes, not
    //      content-level mistakes; surface them as a single consolidated error so
    //      the author can fix the workspace declaration.
    val rootValidationErrors = mutableListOf<String>()
    rootIds.forEach { rootId ->
      val rootResult = loadCtx.loadById(rootId)
      when (rootResult) {
        is PackLoadResult.NotFound -> {
          rootValidationErrors += "Workspace target id '$rootId' not found at " +
            "${File(workspacePackDir, "$rootId/${TrailblazeConfigPaths.PACK_MANIFEST_FILENAME}").absolutePath}" +
            (if (includeClasspathPacks) " or as a classpath-bundled pack" else "") + "."
        }
        is PackLoadResult.ParseFailure -> {
          // Already logged inside loadById; nothing else to do — atomic-per-pack drop.
        }
        is PackLoadResult.Found -> {
          val rootManifest = rootResult.manifest
          // Workspace `targets:` only accepts target packs. A library pack id in the list is
          // a category error: library packs reach scope only via dependencies. Surface here
          // (rather than silently dropping) so the author understands what to fix.
          if (rootManifest.manifest.target == null) {
            rootValidationErrors += "Workspace target id '$rootId' resolves to a *library* pack " +
              "(no `target:` block in ${rootManifest.source.describe()}). The workspace `targets:` " +
              "list only accepts target packs. Library packs come into scope automatically when a " +
              "target pack declares them in `dependencies:`."
            return@forEach
          }
          // Walk transitive dependencies. Atomic-per-pack on a transitive load failure
          // (e.g. a depended-on pack file is malformed) — those are logged and the
          // partial graph continues; missing-dep references are caught by the strict
          // validation pass below.
          try {
            loadCtx.loadTransitively(rootManifest)
          } catch (e: TrailblazeProjectConfigException) {
            Console.log(
              "Warning: Failed to load pack '${rootManifest.manifest.id}' " +
                "from ${rootManifest.source.describe()}: ${e.message}",
            )
          }
        }
      }
    }
    if (rootValidationErrors.isNotEmpty()) {
      throw TrailblazeProjectConfigException(
        "Workspace `targets:` validation failed:\n" +
          rootValidationErrors.joinToString("\n") { "  - $it" },
      )
    }

    // Step 3: strict dependency-graph validation across the full loaded pool. Every pack's
    // `dependencies:` must resolve to a pack id in `loadedById`. Aggregate failures into a
    // single error so the author sees every broken edge in one shot.
    val loadedById = loadCtx.loaded
    val depErrors = mutableListOf<String>()
    loadedById.values.forEach { loaded ->
      val missing = loaded.manifest.dependencies.filter { it !in loadedById }
      if (missing.isNotEmpty()) {
        depErrors += "  - pack '${loaded.manifest.id}' (${loaded.source.describe()}) " +
          "depends on ${missing.joinToString(", ") { "'$it'" }} which ${
            if (missing.size > 1) "are" else "is"
          } not in the resolved pool."
      }
    }
    if (depErrors.isNotEmpty()) {
      val available = loadedById.keys.sorted().joinToString(", ")
      throw TrailblazeProjectConfigException(
        "Pack dependency-graph validation failed:\n" +
          depErrors.joinToString("\n") + "\n" +
          "Available pack ids in the resolved pool: [$available]. " +
          "Add the missing pack(s) to the workspace pack directory or to the framework " +
          "classpath, or remove the unresolvable dependency.",
      )
    }

    // Step 4: per-pack sibling resolution. A pack whose siblings fail to resolve drops
    // out entirely (atomic-per-pack), but doesn't take siblings down with it.
    val resolvedPacks = mutableListOf<ResolvedPack>()
    loadedById.values.forEach { loadedManifest ->
      try {
        resolvedPacks += resolvePackSiblings(loadedManifest)
      } catch (e: TrailblazeProjectConfigException) {
        val causeHint = e.cause?.let { " (${it::class.simpleName})" }.orEmpty()
        Console.log(
          "Warning: Failed to resolve pack '${loadedManifest.manifest.id}' " +
            "from ${loadedManifest.source.describe()}: ${e.message}$causeHint",
        )
      }
    }

    // Step 5: emit final targets (with defaults inheritance) + pool contributions
    // (toolsets/tools/waypoints from every successfully-resolved pack).
    val packsById = resolvedPacks.associateBy { it.manifest.id }
    val targets = mutableListOf<AppTargetYamlConfig>()
    val successfulTargetIds = mutableListOf<String>()
    val toolsets = mutableListOf<ToolSetYamlConfig>()
    val tools = mutableListOf<ToolYamlConfig>()
    val waypoints = mutableListOf<WaypointDefinition>()

    resolvedPacks.forEach { pack ->
      toolsets += pack.toolsets
      tools += pack.tools
      waypoints += pack.waypoints

      val ownTarget = pack.target ?: return@forEach

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
      successfulTargetIds += pack.manifest.id
    }

    return ResolvedPackArtifacts(
      successfulTargetIds = successfulTargetIds,
      targets = targets,
      toolsets = toolsets,
      tools = tools,
      waypoints = waypoints,
      resolvedPacks = resolvedPacks.toList(),
    )
  }

  /**
   * Walks `<workspacePackDir>` and the classpath pack pool, returning every id that
   * corresponds to a target pack (one whose `pack.yaml` declares a `target:` block).
   * Library packs are not auto-discovered as roots — they reach scope only via
   * transitive `dependencies:`. The auto-discovery branch fires when the workspace's
   * `targets:` list is empty/omitted.
   */
  private fun autoDiscoverTargetPackIds(
    workspacePackDir: File,
    classpathManifests: List<LoadedTrailblazePackManifest>,
  ): List<String> {
    val ids = linkedSetOf<String>()
    if (workspacePackDir.isDirectory) {
      workspacePackDir.listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .forEach { packDir ->
          val manifestFile = File(packDir, TrailblazeConfigPaths.PACK_MANIFEST_FILENAME)
          if (!manifestFile.isFile) return@forEach
          val manifest = try {
            TrailblazePackManifestLoader.load(manifestFile).manifest
          } catch (e: TrailblazeProjectConfigException) {
            Console.log(
              "Warning: Skipping ${manifestFile.absolutePath} during auto-discovery: ${e.message}",
            )
            return@forEach
          }
          if (manifest.target != null) ids += manifest.id
        }
    }
    classpathManifests
      .filter { it.manifest.target != null }
      .map { it.manifest.id }
      .sorted()
      .forEach { ids += it }
    return ids.toList()
  }

  /**
   * Outcome of a pack-id lookup. Distinguishes "id not in scope" (config-level mistake)
   * from "manifest exists but failed to parse" (content-level mistake) so callers can
   * apply different policies — workspace `targets:` resolution wants strict on the
   * former and atomic-per-pack on the latter.
   */
  private sealed class PackLoadResult {
    data class Found(val manifest: LoadedTrailblazePackManifest) : PackLoadResult()
    /** Manifest file existed but failed to parse — already logged by [PackLoadContext]. */
    object ParseFailure : PackLoadResult()
    /** No manifest file at workspace path and not classpath-bundled. */
    object NotFound : PackLoadResult()
  }

  /**
   * Mutable working set for the transitive pack-load walk. Resolves each pack id via
   * `<workspacePackDir>/<id>/pack.yaml` (workspace shadows classpath on collision),
   * then recurses into the manifest's `dependencies:` until a fixed point is reached.
   * Cycles are prevented by checking [loaded] before recursing.
   */
  private class PackLoadContext(
    private val workspacePackDir: File,
    private val classpathById: Map<String, LoadedTrailblazePackManifest>,
  ) {
    val loaded: MutableMap<String, LoadedTrailblazePackManifest> = linkedMapOf()

    fun loadById(id: String): PackLoadResult {
      loaded[id]?.let { return PackLoadResult.Found(it) }
      val workspaceFile = File(workspacePackDir, "$id/${TrailblazeConfigPaths.PACK_MANIFEST_FILENAME}")
      val manifest = if (workspaceFile.isFile) {
        try {
          TrailblazePackManifestLoader.load(workspaceFile)
        } catch (e: TrailblazeProjectConfigException) {
          Console.log(
            "Warning: Failed to load workspace pack '$id' at ${workspaceFile.absolutePath}: ${e.message}",
          )
          return PackLoadResult.ParseFailure
        }
      } else {
        classpathById[id] ?: return PackLoadResult.NotFound
      }
      loaded[id] = manifest
      return PackLoadResult.Found(manifest)
    }

    fun loadTransitively(root: LoadedTrailblazePackManifest) {
      loaded.putIfAbsent(root.manifest.id, root)
      val frontier = ArrayDeque<LoadedTrailblazePackManifest>().apply { add(root) }
      while (frontier.isNotEmpty()) {
        val current = frontier.removeFirst()
        current.manifest.dependencies.forEach { depId ->
          if (depId in loaded) return@forEach
          val depResult = loadById(depId)
          if (depResult is PackLoadResult.Found) {
            // loadById already inserted into `loaded` if found; recurse only when we
            // genuinely advanced the frontier so a cycle terminates after one visit.
            frontier.add(depResult.manifest)
          }
          // Other outcomes (NotFound / ParseFailure) leave `loaded` without `depId`,
          // so the strict dep-graph validation at the call site catches it as a
          // missing edge with full context.
        }
      }
    }
  }

  private fun resolvePackSiblings(
    loadedManifest: LoadedTrailblazePackManifest,
  ): ResolvedPack {
    // Auto-discover every scripted-tool descriptor under `<pack>/tools/` and index it by the
    // `name:` field declared inside (or by each entry's `name:` for multi-tool descriptors).
    // `target.tools:` then names which of those discovered tools the target exposes — names,
    // not paths. The registry also serves the duplicate-name guard so two files declaring the
    // same tool name fail loudly with both file paths in the error.
    val discovery = discoverPackScriptedTools(loadedManifest)
    val scriptedToolRegistry = discovery.registry

    val declaredTargetTools = loadedManifest.manifest.target?.tools.orEmpty()
    // Detect duplicates inside `target.tools:` itself before resolving them; without this guard
    // a `target.tools:` list with the same name twice silently double-registers the resulting
    // [InlineScriptToolConfig] and the runtime tool repo's later collision check then fails
    // with a confusing "tool already registered" message that points away from the manifest.
    // SISTER-IMPL-TAG: pack-target-tools-dup-detection. Same per-pack dup check lives in
    // TrailblazePackBundler / DaemonScriptedToolBundler / TrailblazeBundledConfigTasks;
    // grep that tag to find all four sites if you change the wording or behavior.
    val seenTargetToolNames = mutableSetOf<String>()
    declaredTargetTools.forEach { toolName ->
      if (!seenTargetToolNames.add(toolName)) {
        throw TrailblazeProjectConfigException(
          "Pack '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
            "`target.tools:` lists '$toolName' more than once. Each scripted-tool name " +
            "must appear at most once in `target.tools:`.",
        )
      }
    }
    val resolvedScriptedTools: List<InlineScriptToolConfig> =
      declaredTargetTools
        .flatMap { toolName ->
          val match = scriptedToolRegistry[toolName]
            ?: throw TrailblazeProjectConfigException(
              buildString {
                append("Pack '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): ")
                append("`target.tools:` references '$toolName' but no scripted-tool descriptor ")
                append("with that `name:` was discovered under <pack>/tools/. ")
                append(describeAvailableScriptedTools(scriptedToolRegistry))
                append(" Tool names must match the `name:` field inside a `<pack>/tools/<file>.yaml` ")
                append("descriptor (or one of its `tools:` entries) — `target.tools:` is a list of ")
                append("names, not file paths.")
                if (toolName.endsWith(".yaml") || toolName.contains('/')) {
                  append(" Hint: '$toolName' looks like a file path; this field used to hold paths ")
                  append("but now holds tool names — open the descriptor at that path and copy its ")
                  append("`name:` field here.")
                }
                // If a descriptor was skipped during discovery whose pack-relative path's base
                // matches the unknown tool name (the conventional `<name>.yaml`), point the
                // author directly at it rather than relying on them grepping back through
                // Console.log warnings.
                val likelyCulprit = discovery.skipped.firstOrNull {
                  it.substringAfterLast('/') == "$toolName.yaml"
                }
                if (likelyCulprit != null) {
                  append(" Note: descriptor '$likelyCulprit' was skipped during discovery ")
                  append("(see earlier log warning for the parse error). Fix that file to ")
                  append("register the '$toolName' name.")
                } else if (discovery.skipped.isNotEmpty()) {
                  append(" Note: ${discovery.skipped.size} other descriptor(s) under ")
                  append("<pack>/tools/ were skipped during discovery (see earlier log ")
                  append("warnings); one of them may have been intended to declare '$toolName'.")
                }
              },
            )
          // For multi-tool descriptors, `toInlineScriptToolConfigs()` returns one config per
          // entry — but we only want the entry matching `toolName`, not all of them. The
          // descriptor's other entries are referenced separately via their own `target.tools:`
          // entries (or omitted intentionally). Filtering by name keeps the surfaces aligned:
          // one `target.tools:` entry → one runtime [InlineScriptToolConfig].
          val configs = match.descriptor.toInlineScriptToolConfigs().filter { it.name == toolName }
          // Resolve `script:` relative to the descriptor YAML file's parent directory so the
          // downstream runtime bundler can read the source from a stable absolute path
          // regardless of the daemon's cwd. Pure path math when the pack is filesystem-backed
          // (the only variant the runtime bundler can read today); classpath-backed packs pass
          // the value through unchanged so the existing TODO surface for on-classpath script
          // bundling stays explicit at the bundler call site.
          val source = loadedManifest.source
          if (source is PackSource.Filesystem) {
            val yamlDir = File(source.packDir, match.relativePath).parentFile
              ?: source.packDir
            configs.map { cfg ->
              val raw = File(cfg.script)
              if (raw.isAbsolute) cfg else cfg.copy(
                script = File(yamlDir, cfg.script).toPath().normalize().toFile().absolutePath,
              )
            }
          } else {
            configs
          }
        }
    val resolvedSystemPrompt: String? =
      loadedManifest.manifest.target?.systemPromptFile?.let { path ->
        loadedManifest.source.readSibling(path)
          ?: throw TrailblazeProjectConfigException(
            "Referenced system_prompt_file not found: $path (in ${loadedManifest.source.describe()})",
          )
      }
    val target = loadedManifest.manifest.target
      ?.toAppTargetYamlConfig(
        defaultId = loadedManifest.manifest.id,
        resolvedTools = resolvedScriptedTools,
        resolvedSystemPrompt = resolvedSystemPrompt,
      )
    val resolvedToolsets = loadedManifest.manifest.toolsets
      .map { path -> loadPackSibling(path, loadedManifest.source, ToolSetYamlConfig.serializer(), "pack toolset") }

    // Operational tool YAMLs auto-discovered from three sibling directories, one per
    // operational class:
    //   - `<pack>/tools/**.tool.yaml`
    //   - `<pack>/shortcuts/**.shortcut.yaml`
    //   - `<pack>/trailheads/**.trailhead.yaml`
    // Each dir is walked recursively at any depth — subdirs are organizational only
    // (e.g. `shortcuts/web/`, `trailheads/android/`), matching the existing
    // `<pack>/waypoints/` convention. Library-pack contract: a pack with no `target:`
    // block cannot ship trailhead tools (a trailhead bootstraps to a known waypoint).
    // Enforced here because the trailhead block lives inside the tool YAML, not the
    // manifest, so the check needs the tool decoded first. The waypoints-on-library-pack
    // rule is enforced in [TrailblazePackManifestLoader] where it's visible from the
    // manifest alone.
    val toolPaths = TrailblazeConfigPaths.PACK_TOOL_LAYOUT.flatMap { (dir, suffix) ->
      loadedManifest.source.listSiblingsRecursive(
        relativeDir = dir,
        suffixes = listOf(suffix),
      )
    }
    val resolvedTools = toolPaths.map { path ->
      val tool = loadPackSibling(path, loadedManifest.source, ToolYamlConfig.serializer(), "pack tool")
      if (loadedManifest.manifest.target == null && tool.trailhead != null) {
        throw TrailblazeProjectConfigException(
          "Pack '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}) is a library " +
            "pack (no target: block) but its tool '$path' declares a trailhead: block. " +
            "Trailheads bootstrap to a known waypoint and only make sense within a target pack. " +
            "Move the trailhead tool into a target pack, or add a target: block to this pack.",
        )
      }
      tool
    }
    // Waypoint YAMLs auto-discovered from `<pack>/waypoints/` (any depth). The manifest no
    // longer enumerates them — anything in the directory tree with a `.waypoint.yaml`
    // suffix ships with the pack. Library-pack contract: a pack with no `target:` block
    // cannot ship waypoints (a waypoint binds to a target's screen state). The
    // manifest-side check in TrailblazePackManifestLoader still fires when an old-style
    // pack.yaml lists waypoints explicitly; this discovery-side check covers the new
    // path where the YAMLs are present on disk without a manifest list.
    val waypointPaths = loadedManifest.source.listSiblingsRecursive(
      relativeDir = PACK_WAYPOINTS_DIR,
      suffixes = PACK_WAYPOINT_SUFFIXES,
    )
    if (loadedManifest.manifest.target == null && waypointPaths.isNotEmpty()) {
      throw TrailblazeProjectConfigException(
        "Pack '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}) is a library " +
          "pack (no target: block) but has waypoint files on disk under $PACK_WAYPOINTS_DIR/: " +
          "${waypointPaths.joinToString(", ")}. " +
          "Library packs cannot own waypoints — waypoints bind to a target's screen state. " +
          "Move the waypoint files into a target pack, or add a target: block to this pack.",
      )
    }
    val resolvedWaypoints = waypointPaths.map { path ->
      loadPackSibling(path, loadedManifest.source, WaypointDefinition.serializer(), "pack waypoint")
    }
    return ResolvedPack(
      manifest = loadedManifest.manifest,
      source = loadedManifest.source,
      target = target,
      toolsets = resolvedToolsets,
      tools = resolvedTools,
      waypoints = resolvedWaypoints,
    )
  }

  private const val PACK_WAYPOINTS_DIR = "waypoints"
  private val PACK_WAYPOINT_SUFFIXES = listOf(".waypoint.yaml")

  /**
   * Subdirectory under each pack that holds scripted-tool descriptor YAMLs. Same directory
   * the operational tool layout's `*.tool.yaml` entries occupy — the suffix distinguishes
   * the two flavors at discovery time.
   */
  private const val PACK_SCRIPTED_TOOLS_DIR = "tools"

  /**
   * Filename suffixes that mark an operational tool YAML under `<pack>/tools/` rather than a
   * scripted-tool descriptor. Scripted-tool descriptors are plain `*.yaml` files whose name
   * does NOT end with any of these reserved operational suffixes. Keeping the exclude list
   * explicit (rather than just `*.tool.yaml`) leaves room for any future operational class
   * that lands under `tools/` without re-litigating the discovery rule.
   */
  private val OPERATIONAL_TOOL_YAML_SUFFIXES = listOf(
    ".tool.yaml",
    ".shortcut.yaml",
    ".trailhead.yaml",
    ".waypoint.yaml",
  )

  /**
   * A scripted-tool descriptor entry in the per-pack name registry — the descriptor itself
   * plus the pack-relative path it was discovered at. The path is used to resolve relative
   * `script:` references against the descriptor's parent directory and to produce actionable
   * duplicate-name errors that name both contributing files.
   */
  private data class ScriptedToolRegistryEntry(
    val relativePath: String,
    val descriptor: PackScriptedToolFile,
  )

  /**
   * Builds a name-keyed registry of every scripted-tool descriptor under `<pack>/tools/`.
   * Direct children of `tools/` only — operational tool YAMLs (`.tool.yaml`, etc.) are
   * skipped via the suffix exclude list. Each descriptor's `name:` (single-tool shape) or
   * each entry's `name:` (multi-tool shape) registers one key.
   *
   * Duplicate names fail loudly with both contributing file paths so the author can pick
   * which file to keep or rename.
   *
   * SISTER IMPLEMENTATIONS — same discovery walk + suffix exclude list + name-keyed registry
   * shape lives in three other places. Keep them in lockstep on every edit (the exclude list,
   * the single-vs-multi shape detection, and the duplicate-name diagnostic format must all
   * agree — otherwise the runtime resolves a different set than the build-time generators):
   *   - `trailblaze-pack-bundler/src/main/kotlin/xyz/block/trailblaze/bundle/TrailblazePackBundler.kt`
   *     `buildScriptedToolRegistry` — emits the typed `.d.ts` augmentation per pack.
   *   - `trailblaze-host/src/main/java/xyz/block/trailblaze/scripting/DaemonScriptedToolBundler.kt`
   *     `discoverScriptedToolDescriptors` — esbuild-bundles each script at daemon start.
   *   - `build-logic/src/main/kotlin/TrailblazeBundledConfigTasks.kt`
   *     `buildPackScriptedToolRegistry` — generates the bundled per-target YAMLs under
   *     `dist/targets/`.
   * Build-logic stays independent of `:trailblaze-models` (the Gradle plugin classpath
   * concern documented on `TrailblazePackBundler`'s class kdoc) so the duplication can't be
   * collapsed into one shared util today; cross-referenced comments are the second-best fix.
   *
   * Search tag for grepping all four sister implementations at once (resilient against
   * future file moves): `SISTER-IMPL-TAG: pack-scripted-tool-discovery`.
   */
  /**
   * Discovery result: the name-keyed registry plus the list of descriptor pack-relative paths
   * that were skipped because their decode failed. The skip list is plumbed downstream to the
   * UnknownScriptedToolName diagnostic so an author whose `target.tools:` references the
   * skipped file's intended name gets pointed at it directly rather than just seeing a
   * Console.log warning earlier in the build log.
   */
  private data class ScriptedToolDiscoveryResult(
    val registry: Map<String, ScriptedToolRegistryEntry>,
    val skipped: List<String>,
  )

  private fun discoverPackScriptedTools(
    loadedManifest: LoadedTrailblazePackManifest,
  ): ScriptedToolDiscoveryResult {
    val candidatePaths = loadedManifest.source
      .listSiblings(relativeDir = PACK_SCRIPTED_TOOLS_DIR, suffixes = listOf(".yaml"))
      .filter { path -> OPERATIONAL_TOOL_YAML_SUFFIXES.none { suffix -> path.endsWith(suffix) } }
    val registry = linkedMapOf<String, ScriptedToolRegistryEntry>()
    val skipped = mutableListOf<String>()
    candidatePaths.forEach { path ->
      // Per-descriptor decode wrapped in try/log/skip so a single malformed (or half-written
      // WIP) file under `<pack>/tools/` doesn't tank the entire pack at load time. The
      // referenced-name resolution downstream surfaces a clear UnknownScriptedToolName error
      // if `target.tools:` actually points at the skipped file's name — so the failure stays
      // visible to anyone who relied on the tool, just not to packs that never referenced it.
      // See lead-dev review #2 (round 2) for the rationale.
      val descriptor = try {
        loadPackSibling(
          path,
          loadedManifest.source,
          PackScriptedToolFile.serializer(),
          "pack scripted tool",
        )
      } catch (e: TrailblazeProjectConfigException) {
        Console.log(
          "Note: skipping malformed scripted-tool descriptor '$path' in pack " +
            "'${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): ${e.message}. " +
            "Sibling descriptors still register; any `target.tools:` entry that names a tool " +
            "from this file will fail with UnknownScriptedToolName until the file is fixed.",
        )
        skipped += path
        return@forEach
      }
      val multi = descriptor.tools
      val single = descriptor.name
      val declaredNames: List<String> = when {
        multi != null -> multi.map { it.name }
        single != null -> listOf(single)
        else -> {
          // Same skip-and-log treatment for a descriptor missing both `name:` and `tools:`. A
          // typical WIP shape (`script: ./foo.ts` and nothing else) is invisible rather than
          // pack-fatal; `target.tools:` references surface as UnknownScriptedToolName.
          Console.log(
            "Note: skipping scripted-tool descriptor '$path' in pack " +
              "'${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}) — must " +
              "declare either a top-level `name:` (single-tool shape) or a `tools:` list " +
              "(multi-tool shape). Sibling descriptors still register.",
          )
          skipped += path
          return@forEach
        }
      }
      declaredNames.forEach { name ->
        // Reject blank tool names symmetrically with the bundler's `BlankToolName` guard —
        // `name: ""` (or whitespace-only) decodes successfully but would register under the
        // empty key, where a `target.tools: [""]` typo or the runtime tool repo's later
        // registration would silently match it. SISTER-IMPL-TAG: pack-scripted-tool-discovery.
        if (name.isBlank()) {
          throw TrailblazeProjectConfigException(
            "Pack '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
              "scripted-tool descriptor '$path' declares a blank tool name. Tool names " +
              "must be non-empty and contain at least one non-whitespace character.",
          )
        }
        val previous = registry[name]
        if (previous != null) {
          throw TrailblazeProjectConfigException(
            "Pack '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
              "two scripted-tool descriptors under <pack>/tools/ declare the same tool name " +
              "'$name': '${previous.relativePath}' and '$path'. Tool names must be unique within " +
              "a pack — rename one of the descriptors' `name:` field (or, for a multi-tool " +
              "descriptor, the offending entry under `tools:`).",
          )
        }
        registry[name] = ScriptedToolRegistryEntry(relativePath = path, descriptor = descriptor)
      }
    }
    return ScriptedToolDiscoveryResult(registry, skipped)
  }

  private fun describeAvailableScriptedTools(
    registry: Map<String, ScriptedToolRegistryEntry>,
  ): String = if (registry.isEmpty()) {
    "No scripted-tool descriptors discovered under <pack>/tools/."
  } else {
    "Available tool names: [${registry.keys.sorted().joinToString(", ")}]."
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
     * Target-pack ids that successfully resolved (workspace-listed roots and their
     * transitive deps that surfaced runnable targets). Populates
     * [TrailblazeProjectConfig.targets] post-resolution.
     */
    val successfulTargetIds: List<String> = emptyList(),
    val targets: List<AppTargetYamlConfig> = emptyList(),
    val toolsets: List<ToolSetYamlConfig> = emptyList(),
    val tools: List<ToolYamlConfig> = emptyList(),
    val waypoints: List<WaypointDefinition> = emptyList(),
    /** Every pack that completed sibling resolution — surfaced via [TrailblazeResolvedConfig]. */
    val resolvedPacks: List<ResolvedPack> = emptyList(),
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

/**
 * A pack manifest after sibling refs are resolved but before dependency-graph defaults
 * are applied. Surfaced on [TrailblazeResolvedConfig.resolvedPacks] so codegen consumers
 * (per-pack `client.d.ts` emitter) can read pack-local declarations without re-walking
 * the workspace's pack manifests.
 *
 * @property manifest The parsed pack manifest with its `id`, `dependencies`, optional
 *   `target:` block, optional top-level `platforms:` (library packs), and optional
 *   `exports:` list.
 * @property source Where the pack was loaded from — filesystem packDir or classpath
 *   resource prefix. Codegen that writes per-pack output (e.g. `client.d.ts`) skips
 *   classpath-backed packs since they can't be written into a JAR.
 * @property target The resolved [AppTargetYamlConfig] for target packs; null for library
 *   packs (those without a `target:` block).
 * @property toolsets Toolset YAML configs declared via the pack manifest's `toolsets:` list.
 * @property tools Operational tool YAMLs auto-discovered under the pack's `tools/` /
 *   `shortcuts/` / `trailheads/` directories. These are the pure-YAML composed tools
 *   (`.tool.yaml`), not the scripted tools listed under `target.tools:` (those live on
 *   [target] via `AppTargetYamlConfig.tools`).
 * @property waypoints Waypoints auto-discovered under the pack's `waypoints/` directory.
 */
data class ResolvedPack(
  val manifest: TrailblazePackManifest,
  val source: PackSource,
  val target: AppTargetYamlConfig?,
  val toolsets: List<ToolSetYamlConfig>,
  val tools: List<ToolYamlConfig>,
  val waypoints: List<WaypointDefinition>,
)
