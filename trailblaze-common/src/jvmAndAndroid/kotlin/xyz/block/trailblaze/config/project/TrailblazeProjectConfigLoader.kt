package xyz.block.trailblaze.config.project

import java.io.File
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig
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
   * [resolveInternal] with `includeClasspathTrailmaps = true`, so workspace trailmaps declaring
   * `dependencies: [trailblaze]` (or any other framework-shipped trailmap) resolve their
   * deps against the JVM classpath. Previously this defaulted to `false`, which silently
   * dropped any workspace trailmap that depended on the framework stdlib and broke the
   * production runtime path used by [loadResolved] / [TrailblazeWorkspaceConfigResolver].
   *
   * If you need the historical "isolated workspace view" (no classpath trailmaps in scope —
   * appropriate for unit tests that want to enumerate ONLY what's in the workspace),
   * call [resolveInternal] directly with `includeClasspathTrailmaps = false`. That escape
   * hatch is intentional; it just isn't the default any more.
   */
  fun resolveRefs(loaded: LoadedTrailblazeProjectConfig): TrailblazeProjectConfig =
    resolveInternal(loaded, includeClasspathTrailmaps = true, scriptedToolEnrichment = null).projectConfig

  /**
   * Binary-compatible shim for the pre-[includeOrphanTrailmaps] public signature.
   *
   * The 4-arg overload below is the canonical form; this 3-arg form delegates with
   * `includeOrphanTrailmaps = false`. Keeping the original signature live preserves the Kotlin
   * metadata + JVM bytecode contract for any external consumer compiled against an earlier
   * `:trailblaze-common` jar — without this, adding a 4th defaulted parameter would silently
   * break with `NoSuchMethodError` for those callers (Kotlin default-args generate a
   * `resolveRuntime$default(...)` synthetic that includes every positional slot, so its
   * descriptor changes shape when a slot is added even with a default). Same pattern as
   * [TrailblazeCompiler.compile]'s 4-arg/5-arg shim.
   */
  fun resolveRuntime(
    loaded: LoadedTrailblazeProjectConfig,
    includeClasspathTrailmaps: Boolean = true,
    scriptedToolEnrichment: ScriptedToolEnrichment? = null,
  ): TrailblazeResolvedConfig = resolveRuntime(loaded, includeClasspathTrailmaps, scriptedToolEnrichment, includeOrphanTrailmaps = false)

  /**
   * Resolves [loaded] into a [TrailblazeResolvedConfig] that includes runtime artifacts
   * surfaced from trailmaps — today: waypoints. Use this when callers need access to
   * trailmap-bundled non-schema data; otherwise [resolveRefs] is sufficient.
   *
   * ## Trailmap discovery sources
   *
   * Two sources contribute trailmap artifacts, in precedence order from base to override:
   *  1. Classpath-bundled trailmaps (auto-discovered under `trails/config/trailmaps/<id>/trailmap.yaml`)
   *  2. Workspace `trailmaps:` entries in `trailblaze.yaml`
   *
   * When the same trailmap `id` appears in both sources, the workspace trailmap wholesale
   * shadows the classpath trailmap — workspace authors can override framework-shipped
   * trailmaps without having to fork them. See [TrailblazeResolvedConfig] for the
   * documented precedence rule.
   *
   * Set [includeClasspathTrailmaps] to `false` to suppress classpath discovery — used by
   * unit tests that need an isolated workspace view.
   *
   * **Blocking semantics.** When [scriptedToolEnrichment] is non-null AND the workspace
   * carries meta-only scripted-tool descriptors (see [TrailmapScriptedToolFile.requiresEnrichment]),
   * this method spawns one Node subprocess per affected trailmap via `runBlocking` (the
   * analyzer subprocess is `suspend` for symmetry with its internals, but the production
   * entry points are all single-shot). Do NOT invoke from a request-serving thread when
   * an analyzer-backed enrichment is wired in.
   *
   * **[includeOrphanTrailmaps]** — off by default, so every existing caller (target/waypoint
   * discovery, CLI `check`/`compile`) is unaffected. Normally [resolvedTrailmaps] only contains
   * target trailmaps plus whatever they pull in transitively via `dependencies:` — a library
   * trailmap nothing declares as a dependency (e.g. one whose tools are only ever referenced by
   * id through the global tool/toolset registry, not a static `dependencies:` edge) is invisible
   * to this method even though it's a perfectly real, authored trailmap. Set this to `true` when
   * a caller's job is enumerating every trailmap that exists (e.g. a catalog/browsing UI), not
   * just what a specific target's dispatch graph reaches — every additional trailmap found this
   * way still goes through the exact same per-trailmap sibling-resolution and analyzer enrichment
   * as any other, it's just not gated on dependency-graph reachability.
   *
   * No default values on this overload — giving both overloads defaulted trailing params would
   * make a 3-arg call ambiguous between "exact match on the shim above" and "default-substituted
   * match on this one". Every internal call site passes all 4 explicitly.
   */
  fun resolveRuntime(
    loaded: LoadedTrailblazeProjectConfig,
    includeClasspathTrailmaps: Boolean,
    scriptedToolEnrichment: ScriptedToolEnrichment?,
    includeOrphanTrailmaps: Boolean,
  ): TrailblazeResolvedConfig = resolveInternal(loaded, includeClasspathTrailmaps, scriptedToolEnrichment, includeOrphanTrailmaps)

  private fun resolveInternal(
    loaded: LoadedTrailblazeProjectConfig,
    includeClasspathTrailmaps: Boolean,
    scriptedToolEnrichment: ScriptedToolEnrichment?,
    includeOrphanTrailmaps: Boolean = false,
  ): TrailblazeResolvedConfig {
    val anchor = loaded.sourceFile.parentFile ?: File(".")
    val raw = loaded.raw
    val resolvedToolsets = raw.toolsets.map { resolveToolsetEntry(it, anchor) }
    val resolvedTools = raw.tools.map { resolveToolEntry(it, anchor) }
    val trailmapArtifacts = resolveTrailmapArtifacts(raw.targets, anchor, includeClasspathTrailmaps, scriptedToolEnrichment, includeOrphanTrailmaps)
    val projectConfig = TrailblazeProjectConfig(
      defaults = raw.defaults,
      targets = trailmapArtifacts.successfulTargetIds,
      toolsets = mergeById(
        base = trailmapArtifacts.toolsets,
        overrides = resolvedToolsets,
        idSelector = ToolSetYamlConfig::id,
      ).map(ToolsetEntry::Inline),
      tools = mergeById(
        base = trailmapArtifacts.tools,
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
      targets = trailmapArtifacts.targets,
      waypoints = trailmapArtifacts.waypoints,
      resolvedTrailmaps = trailmapArtifacts.resolvedTrailmaps,
    )
  }

  /**
   * Convenience: [load] + [resolveRefs] in one call. Returns the resolved
   * [TrailblazeProjectConfig] schema only — classpath-bundled trailmaps are NOT discovered
   * and trailmap-bundled waypoints are NOT surfaced. Use this when you only need the
   * workspace's declared targets/toolsets/tools/providers and no runtime artifacts.
   *
   * Callers that need trailmap-bundled waypoints (or any future trailmap-bundled runtime
   * artifact like routes/trails) should call [loadResolvedRuntime] instead, which
   * returns a [TrailblazeResolvedConfig] wrapper carrying both the schema and the
   * resolved runtime artifacts.
   */
  fun loadResolved(configFile: File): TrailblazeProjectConfig? =
    load(configFile)?.let(::resolveRefs)

  /**
   * Binary-compatible shim for the pre-[includeOrphanTrailmaps] public signature — see the
   * [resolveRuntime] shim above for the full rationale. Delegates with
   * `includeOrphanTrailmaps = false`.
   */
  fun loadResolvedRuntime(
    configFile: File,
    includeClasspathTrailmaps: Boolean = true,
    scriptedToolEnrichment: ScriptedToolEnrichment? = null,
  ): TrailblazeResolvedConfig? = loadResolvedRuntime(configFile, includeClasspathTrailmaps, scriptedToolEnrichment, includeOrphanTrailmaps = false)

  /**
   * Convenience: [load] + [resolveRuntime] in one call. Returns a
   * [TrailblazeResolvedConfig] wrapper that bundles the resolved
   * [TrailblazeProjectConfig] schema together with classpath+workspace trailmap-bundled
   * runtime artifacts (today: waypoints; later: routes/trails).
   *
   * Compared to [loadResolved]: this entry point ALSO discovers classpath-bundled
   * trailmaps (controlled by [includeClasspathTrailmaps]) and surfaces their `waypoints:`
   * resolution on the returned wrapper. Use this for runtime code paths
   * (CLI commands, agent runtime). Use [loadResolved] for tests that need an
   * isolated workspace view, or for legacy callers that only need the schema.
   *
   * [includeOrphanTrailmaps] — see [resolveRuntime]'s kdoc; off by default. No default values on
   * this overload for the same overload-ambiguity reason as [resolveRuntime]'s canonical form.
   */
  fun loadResolvedRuntime(
    configFile: File,
    includeClasspathTrailmaps: Boolean,
    scriptedToolEnrichment: ScriptedToolEnrichment?,
    includeOrphanTrailmaps: Boolean,
  ): TrailblazeResolvedConfig? =
    load(configFile)?.let { resolveRuntime(it, includeClasspathTrailmaps, scriptedToolEnrichment, includeOrphanTrailmaps) }

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
   * Resolves the trailmap pool from the workspace's declared target ids (or via
   * auto-discovery if [declaredTargetIds] is empty), bringing transitive `dependencies:`
   * into scope along the way. Returns the resolved targets, plus the toolsets / tools /
   * waypoints contributed by every trailmap that landed in the pool.
   *
   * Workspace `<anchor>/trailmaps/<id>/trailmap.yaml` is the resolution convention for both the
   * workspace-listed roots and any trailmaps pulled in transitively via `dependencies:`. The
   * classpath is also a discovery source for both purposes (when [includeClasspathTrailmaps]
   * is true): a target listed in workspace `targets:` may be classpath-bundled (e.g. the
   * framework `clock` target), and the same goes for any dependency a trailmap declares.
   *
   * ## Resolution flow
   *
   * 1. Determine the **root set** of trailmap ids — either the explicit [declaredTargetIds]
   *    list, or (when empty) every target trailmap discovered under `<anchor>/trailmaps/` plus
   *    every classpath-bundled target trailmap. Library trailmaps are not auto-discovered as
   *    roots — they only enter scope transitively via `dependencies:`.
   * 2. **Validate roots** are target trailmaps. A workspace listing a library-trailmap id in
   *    `targets:` is rejected with a redirecting error message.
   * 3. **Walk transitive `dependencies:`** to build the full set of trailmap ids that need
   *    to load. Each id resolves via the workspace trailmap dir (`<anchor>/trailmaps/<id>/`) or
   *    the classpath, with workspace shadowing the classpath on id collisions.
   * 4. **Strict dep-graph validation**: every trailmap's `dependencies:` must resolve to a
   *    trailmap id in the loaded pool. Unresolvable deps are aggregated and surfaced as a
   *    single consolidated error.
   * 5. **Resolve siblings** for each loaded trailmap — auto-discover operational tool YAMLs
   *    from `<trailmap>/tools/`, load declared toolsets and waypoints.
   * 6. **Apply per-target defaults inheritance** via `TrailmapDependencyResolver` and emit
   *    the final `AppTargetYamlConfig` list.
   */
  private fun resolveTrailmapArtifacts(
    declaredTargetIds: List<String>,
    anchor: File,
    includeClasspathTrailmaps: Boolean,
    scriptedToolEnrichment: ScriptedToolEnrichment?,
    includeOrphanTrailmaps: Boolean = false,
  ): ResolvedTrailmapArtifacts {
    val workspaceTrailmapDir = File(anchor, TrailblazeConfigPaths.TRAILMAPS_SUBDIR)

    val classpathManifests: List<LoadedTrailblazeTrailmapManifest> = if (includeClasspathTrailmaps) {
      TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
    } else {
      emptyList()
    }
    val classpathById = classpathManifests.associateBy { it.manifest.id }

    // Step 1: figure out the root set of target-trailmap ids that need to load.
    val rootIds: List<String> = if (declaredTargetIds.isNotEmpty()) {
      declaredTargetIds
    } else {
      autoDiscoverTargetTrailmapIds(workspaceTrailmapDir, classpathManifests)
    }

    // Step 2: load trailmap manifests transitively from the root set, walking
    // `dependencies:` and resolving each id via workspace trailmap dir → classpath fallback.
    // Atomic-per-trailmap on resolution failure (logged), strict on dep-graph violations.
    val loadCtx = TrailmapLoadContext(
      workspaceTrailmapDir = workspaceTrailmapDir,
      classpathById = classpathById,
    )
    // Two distinct failure shapes for workspace-listed roots, handled differently:
    //   1. **Parse failure** of an existing trailmap manifest — atomic-per-trailmap drop with
    //      a logged warning (existing behavior, preserved). The trailmap file is there
    //      but malformed; sibling trailmaps continue to load. The author already gets a
    //      console warning naming the offending trailmap and the parse error.
    //   2. **Hard-error** category errors — id refers to a library trailmap (cross-target
    //      reusable tooling, not a runnable target), or id resolves to nothing on
    //      disk and isn't classpath-bundled. These are config-level mistakes, not
    //      content-level mistakes; surface them as a single consolidated error so
    //      the author can fix the workspace declaration.
    val rootValidationErrors = mutableListOf<String>()
    rootIds.forEach { rootId ->
      val rootResult = loadCtx.loadById(rootId)
      when (rootResult) {
        is TrailmapLoadResult.NotFound -> {
          rootValidationErrors += "Workspace target id '$rootId' not found at " +
            "${File(workspaceTrailmapDir, "$rootId/${TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME}").absolutePath}" +
            (if (includeClasspathTrailmaps) " or as a classpath-bundled trailmap" else "") + "."
        }
        is TrailmapLoadResult.ParseFailure -> {
          // Already logged inside loadById; nothing else to do — atomic-per-trailmap drop.
        }
        is TrailmapLoadResult.Found -> {
          val rootManifest = rootResult.manifest
          // Workspace `targets:` only accepts target trailmaps. A library trailmap id in the list is
          // a category error: library trailmaps reach scope only via dependencies. Surface here
          // (rather than silently dropping) so the author understands what to fix.
          if (rootManifest.manifest.target == null) {
            rootValidationErrors += "Workspace target id '$rootId' resolves to a *library* trailmap " +
              "(no `target:` block in ${rootManifest.source.describe()}). The workspace `targets:` " +
              "list only accepts target trailmaps. Library trailmaps come into scope automatically when a " +
              "target trailmap declares them in `dependencies:`."
            return@forEach
          }
          // Walk transitive dependencies. Atomic-per-trailmap on a transitive load failure
          // (e.g. a depended-on trailmap file is malformed) — those are logged and the
          // partial graph continues; missing-dep references are caught by the strict
          // validation pass below.
          try {
            loadCtx.loadTransitively(rootManifest)
          } catch (e: TrailblazeProjectConfigException) {
            Console.log(
              "Warning: Failed to load trailmap '${rootManifest.manifest.id}' " +
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

    // Step 2b (opt-in): pull in every remaining trailmap on disk/classpath that the target +
    // dependency-graph walk above didn't already reach — e.g. a library trailmap nothing
    // declares as a `dependencies:` edge because its tools are only ever called by id through
    // the global registry. These are NOT root candidates (no target-block requirement, no
    // rootValidationErrors on failure) — a missing or malformed one is just skipped with a
    // logged warning, same tolerance as a transitive-dependency load failure above.
    if (includeOrphanTrailmaps) {
      val allKnownIds = linkedSetOf<String>()
      if (workspaceTrailmapDir.isDirectory) {
        workspaceTrailmapDir.listFiles().orEmpty()
          .filter { it.isDirectory }
          .sortedBy { it.name }
          .forEach { allKnownIds += it.name }
      }
      classpathById.keys.sorted().forEach { allKnownIds += it }
      allKnownIds.forEach { id ->
        if (id in loadCtx.loaded) return@forEach
        when (val result = loadCtx.loadById(id)) {
          is TrailmapLoadResult.Found -> {
            // Walk this orphan's transitive `dependencies:` too — same as a root target
            // trailmap — so an orphan that depends on another disk/classpath trailmap doesn't
            // fail Step 3/4's strict dep-graph validation for a dependency that was perfectly
            // loadable, just never reached because nothing declared it as a root.
            try {
              loadCtx.loadTransitively(result.manifest)
            } catch (e: TrailblazeProjectConfigException) {
              Console.log(
                "Warning: Failed to load a dependency of orphan trailmap '$id' " +
                  "(${result.manifest.source.describe()}): ${e.message}",
              )
            }
          }
          is TrailmapLoadResult.ParseFailure -> {} // already logged inside loadById.
          is TrailmapLoadResult.NotFound -> {} // directory listing raced a delete, or classpath id mismatch; not an error.
        }
      }
    }

    // Step 3: strict dependency-graph validation across the full loaded pool. Every trailmap's
    // `dependencies:` must resolve to a trailmap id in `loadedById`. Aggregate failures into a
    // single error so the author sees every broken edge in one shot.
    val loadedById = loadCtx.loaded
    val depErrors = mutableListOf<String>()
    loadedById.values.forEach { loaded ->
      val missing = loaded.manifest.dependencies.filter { it !in loadedById }
      if (missing.isNotEmpty()) {
        depErrors += "  - trailmap '${loaded.manifest.id}' (${loaded.source.describe()}) " +
          "depends on ${missing.joinToString(", ") { "'$it'" }} which ${
            if (missing.size > 1) "are" else "is"
          } not in the resolved pool."
      }
    }
    if (depErrors.isNotEmpty()) {
      val available = loadedById.keys.sorted().joinToString(", ")
      throw TrailblazeProjectConfigException(
        "Trailmap dependency-graph validation failed:\n" +
          depErrors.joinToString("\n") + "\n" +
          "Available trailmap ids in the resolved pool: [$available]. " +
          "Add the missing trailmap(s) to the workspace trailmap directory or to the framework " +
          "classpath, or remove the unresolvable dependency.",
      )
    }

    // Step 4: per-trailmap sibling resolution. A trailmap whose siblings fail to resolve drops
    // out entirely (atomic-per-trailmap), but doesn't take siblings down with it.
    val resolvedTrailmaps = mutableListOf<ResolvedTrailmap>()
    val recoveredWaypoints = mutableListOf<WaypointDefinition>()
    loadedById.values.forEach { loadedManifest ->
      try {
        resolvedTrailmaps += resolveTrailmapSiblings(loadedManifest, scriptedToolEnrichment)
      } catch (e: TrailblazeProjectConfigException) {
        val causeHint = e.cause?.let { " (${it::class.simpleName})" }.orEmpty()
        Console.log(
          "Warning: Failed to resolve trailmap '${loadedManifest.manifest.id}' " +
            "from ${loadedManifest.source.describe()}: ${e.message}$causeHint",
        )
        // Recover waypoints ONLY for the classpath scripted-tool skip — the one failure that's a
        // by-product of the uber-JAR runtime, not an authoring mistake. Every other
        // TrailblazeProjectConfigException (missing system_prompt_file, missing toolset, invalid
        // operational tool YAML, …) keeps the atomic-per-trailmap contract: the trailmap drops
        // whole, waypoints included. The runCatching guard still applies so a malformed-waypoint
        // trailmap recovers nothing.
        if (e is ClasspathScriptedToolUnavailableException) {
          recoveredWaypoints +=
            runCatching { discoverTrailmapWaypoints(loadedManifest) }.getOrDefault(emptyList())
        }
      }
    }

    // Step 5: emit final targets (with defaults inheritance) + pool contributions
    // (toolsets/tools/waypoints from every successfully-resolved trailmap).
    val trailmapsById = resolvedTrailmaps.associateBy { it.manifest.id }
    val targets = mutableListOf<AppTargetYamlConfig>()
    val successfulTargetIds = mutableListOf<String>()
    val toolsets = mutableListOf<ToolSetYamlConfig>()
    val tools = mutableListOf<ToolYamlConfig>()
    val waypoints = mutableListOf<WaypointDefinition>()

    resolvedTrailmaps.forEach { trailmap ->
      toolsets += trailmap.toolsets
      tools += trailmap.tools
      waypoints += trailmap.waypoints

      val ownTarget = trailmap.target ?: return@forEach

      val finalTarget = try {
        TrailmapDependencyResolver.resolveTarget(
          ownTarget = ownTarget,
          ownDependencies = trailmap.manifest.dependencies,
          trailmapsById = trailmapsById,
          rootTrailmapId = trailmap.manifest.id,
        )
      } catch (e: TrailblazeProjectConfigException) {
        Console.log(
          "Warning: Failed dependency resolution for trailmap '${trailmap.manifest.id}' " +
            "from ${trailmap.source.describe()}: ${e.message}",
        )
        return@forEach
      }

      targets += finalTarget
      successfulTargetIds += trailmap.manifest.id
    }
    waypoints += recoveredWaypoints

    return ResolvedTrailmapArtifacts(
      successfulTargetIds = successfulTargetIds,
      targets = targets,
      toolsets = toolsets,
      tools = tools,
      waypoints = waypoints,
      resolvedTrailmaps = resolvedTrailmaps.toList(),
    )
  }

  /**
   * Walks `<workspaceTrailmapDir>` and the classpath trailmap pool, returning every id that
   * corresponds to a target trailmap (one whose `trailmap.yaml` declares a `target:` block).
   * Library trailmaps are not auto-discovered as roots — they reach scope only via
   * transitive `dependencies:`. The auto-discovery branch fires when the workspace's
   * `targets:` list is empty/omitted.
   */
  private fun autoDiscoverTargetTrailmapIds(
    workspaceTrailmapDir: File,
    classpathManifests: List<LoadedTrailblazeTrailmapManifest>,
  ): List<String> {
    val ids = linkedSetOf<String>()
    if (workspaceTrailmapDir.isDirectory) {
      workspaceTrailmapDir.listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .forEach { trailmapDir ->
          val manifestFile = File(trailmapDir, TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME)
          if (!manifestFile.isFile) return@forEach
          val manifest = try {
            TrailblazeTrailmapManifestLoader.load(manifestFile).manifest
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
   * Outcome of a trailmap-id lookup. Distinguishes "id not in scope" (config-level mistake)
   * from "manifest exists but failed to parse" (content-level mistake) so callers can
   * apply different policies — workspace `targets:` resolution wants strict on the
   * former and atomic-per-trailmap on the latter.
   */
  private sealed class TrailmapLoadResult {
    data class Found(val manifest: LoadedTrailblazeTrailmapManifest) : TrailmapLoadResult()
    /** Manifest file existed but failed to parse — already logged by [TrailmapLoadContext]. */
    object ParseFailure : TrailmapLoadResult()
    /** No manifest file at workspace path and not classpath-bundled. */
    object NotFound : TrailmapLoadResult()
  }

  /**
   * Mutable working set for the transitive trailmap-load walk. Resolves each trailmap id via
   * `<workspaceTrailmapDir>/<id>/trailmap.yaml` (workspace shadows classpath on collision),
   * then recurses into the manifest's `dependencies:` until a fixed point is reached.
   * Cycles are prevented by checking [loaded] before recursing.
   */
  private class TrailmapLoadContext(
    private val workspaceTrailmapDir: File,
    private val classpathById: Map<String, LoadedTrailblazeTrailmapManifest>,
  ) {
    val loaded: MutableMap<String, LoadedTrailblazeTrailmapManifest> = linkedMapOf()

    fun loadById(id: String): TrailmapLoadResult {
      loaded[id]?.let { return TrailmapLoadResult.Found(it) }
      val workspaceFile = File(workspaceTrailmapDir, "$id/${TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME}")
      val manifest = if (workspaceFile.isFile) {
        try {
          TrailblazeTrailmapManifestLoader.load(workspaceFile)
        } catch (e: TrailblazeProjectConfigException) {
          Console.log(
            "Warning: Failed to load workspace trailmap '$id' at ${workspaceFile.absolutePath}: ${e.message}",
          )
          return TrailmapLoadResult.ParseFailure
        }
      } else {
        classpathById[id] ?: return TrailmapLoadResult.NotFound
      }
      loaded[id] = manifest
      return TrailmapLoadResult.Found(manifest)
    }

    fun loadTransitively(root: LoadedTrailblazeTrailmapManifest) {
      loaded.putIfAbsent(root.manifest.id, root)
      val frontier = ArrayDeque<LoadedTrailblazeTrailmapManifest>().apply { add(root) }
      while (frontier.isNotEmpty()) {
        val current = frontier.removeFirst()
        current.manifest.dependencies.forEach { depId ->
          if (depId in loaded) return@forEach
          val depResult = loadById(depId)
          if (depResult is TrailmapLoadResult.Found) {
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

  private fun resolveTrailmapSiblings(
    loadedManifest: LoadedTrailblazeTrailmapManifest,
    scriptedToolEnrichment: ScriptedToolEnrichment?,
  ): ResolvedTrailmap {
    // Auto-discover every scripted-tool descriptor under `<trailmap>/tools/` and index it by the
    // `name:` field declared inside (or by each entry's `name:` for multi-tool descriptors).
    // `target.tools:` then names which of those discovered tools the target exposes — names,
    // not paths. The registry also serves the duplicate-name guard so two files declaring the
    // same tool name fail loudly with both file paths in the error.
    val discovery = discoverTrailmapScriptedTools(loadedManifest, scriptedToolEnrichment)
    val scriptedToolRegistry = discovery.registry

    val declaredTargetTools = loadedManifest.manifest.target?.tools.orEmpty()
    // Detect duplicates inside `target.tools:` itself before resolving them; without this guard
    // a `target.tools:` list with the same name twice silently double-registers the resulting
    // [InlineScriptToolConfig] and the runtime tool repo's later collision check then fails
    // with a confusing "tool already registered" message that points away from the manifest.
    // SISTER-IMPL-TAG: trailmap-target-tools-dup-detection. Same per-trailmap dup check lives in
    // TrailblazeTrailmapBundler / DaemonScriptedToolBundler / TrailblazeBundledConfigTasks;
    // grep that tag to find all four sites if you change the wording or behavior.
    val seenTargetToolNames = mutableSetOf<String>()
    declaredTargetTools.forEach { toolName ->
      if (!seenTargetToolNames.add(toolName)) {
        throw TrailblazeProjectConfigException(
          "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
            "`target.tools:` lists '$toolName' more than once. Each scripted-tool name " +
            "must appear at most once in `target.tools:`.",
        )
      }
    }
    // Target-level `target.tools:` — scripted-only by contract, so an unresolved name is a hard
    // error with the rich descriptor-not-found diagnostic.
    val targetLevelScriptedTools: List<InlineScriptToolConfig> =
      declaredTargetTools.flatMap { toolName ->
        resolveScriptedToolConfigsByName(toolName, scriptedToolRegistry, loadedManifest.source)
          ?: run {
            // A name skipped on the classpath enrichment branch is a known by-product of
            // running from the uber JAR, not an authoring mistake — throw the recoverable
            // subtype so the per-trailmap catch keeps the trailmap's waypoints. Any other
            // unresolved name is a genuine author error and keeps the atomic-per-trailmap drop.
            if (toolName in discovery.classpathSkippedNames) {
              throw ClasspathScriptedToolUnavailableException(
                "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
                  "`target.tools:` references '$toolName' but its scripted-tool descriptor needs " +
                  "analyzer enrichment, which can't run for a classpath-loaded trailmap (the " +
                  "analyzer can't walk classpath `.ts` sources). Runtime dispatch is served by the " +
                  "build-time-baked targets/<id>.yaml; this trailmap drops its target here but its " +
                  "waypoints and toolsets are preserved.",
              )
            }
            throw TrailblazeProjectConfigException(
              buildString {
                append("Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): ")
                append("`target.tools:` references '$toolName' but no scripted-tool descriptor ")
                append("with that `name:` was discovered under <trailmap>/tools/. ")
                append(describeAvailableScriptedTools(scriptedToolRegistry))
                append(" Tool names must match the `name:` field inside a `<trailmap>/tools/<file>.yaml` ")
                append("descriptor (or one of its `tools:` entries) — `target.tools:` is a list of ")
                append("names, not file paths.")
                if (toolName.endsWith(".yaml") || toolName.contains('/')) {
                  append(" Hint: '$toolName' looks like a file path; this field used to hold paths ")
                  append("but now holds tool names — open the descriptor at that path and copy its ")
                  append("`name:` field here.")
                }
                // If a descriptor was skipped during discovery whose trailmap-relative path's base
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
                  append("<trailmap>/tools/ were skipped during discovery (see earlier log ")
                  append("warnings); one of them may have been intended to declare '$toolName'.")
                }
              },
            )
          }
      }

    // Per-platform `platforms.<p>.tools:` — mirror of the build-time generator
    // (TrailblazeBundledConfigTasks.buildExpectedTargets). A name that resolves to a scripted tool
    // is hoisted (deduped by name, after validating it's single-platform) into the delivered
    // top-level tools and REMOVED from that platform's `tools:` — the generated/resolved target is
    // loaded as a `YamlBackedHostAppTarget` whose `PlatformConfig.tools` path would otherwise
    // re-resolve the (often descriptor-less `.ts`) name and warn. A non-scripted name (class-/
    // YAML-backed tool) is kept in place. Keep this in lockstep with the build-time generator.
    val mergedScriptedTools = linkedMapOf<String, InlineScriptToolConfig>()
    val toolDeclarationSite = linkedMapOf<String, String>()
    fun recordScriptedTool(cfg: InlineScriptToolConfig, site: String) {
      toolDeclarationSite[cfg.name]?.let { previousSite ->
        throw TrailblazeProjectConfigException(
          "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): scripted " +
            "tool '${cfg.name}' is declared in both $previousSite and $site. Declare each scripted " +
            "tool exactly once — under a single platform's `tools:` (platform-specific) or " +
            "`target.tools:` (cross-platform).",
        )
      }
      toolDeclarationSite[cfg.name] = site
      mergedScriptedTools[cfg.name] = cfg
    }
    targetLevelScriptedTools.forEach { recordScriptedTool(it, "`target.tools:`") }

    val rewrittenPlatforms: Map<String, PlatformConfig>? =
      loadedManifest.manifest.target?.platforms?.mapValues { (platformKey, platformConfig) ->
        val toolNames = platformConfig.tools ?: return@mapValues platformConfig
        val retainedNames = mutableListOf<String>()
        toolNames.forEach { toolName ->
          val resolved = resolveScriptedToolConfigsByName(toolName, scriptedToolRegistry, loadedManifest.source)
          if (resolved == null) {
            retainedNames += toolName
          } else {
            resolved.forEach { cfg ->
              validatePerPlatformScriptedTool(cfg, platformKey, loadedManifest)
              recordScriptedTool(cfg, "`platforms.$platformKey.tools:`")
            }
          }
        }
        // Preserve `emptyList()` (rather than nulling) when the original `tools:` was non-null but
        // every name was a hoisted scripted tool. The downstream [TrailmapDependencyResolver.resolveTarget]
        // (called after this resolver) uses null-as-missing semantics for `closestWinsOverlay`:
        // `null` means "inherit dep defaults", an empty list means "explicitly clear". Nulling here
        // would let an inherited `defaults.<platform>.tools` (class- or YAML-backed) sneak in even
        // though the author declared a tools list that just happened to be entirely scripted.
        platformConfig.copy(tools = retainedNames)
      }

    val resolvedScriptedTools: List<InlineScriptToolConfig> = mergedScriptedTools.values.toList()
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
      ?.let { config -> if (rewrittenPlatforms != null) config.copy(platforms = rewrittenPlatforms) else config }
    val resolvedToolsets = loadedManifest.manifest.toolsets
      .map { path -> loadTrailmapSibling(path, loadedManifest.source, ToolSetYamlConfig.serializer(), "trailmap toolset") }

    // Operational tool YAMLs auto-discovered from three sibling directories, one per
    // operational class:
    //   - `<trailmap>/tools/**.tool.yaml`
    //   - `<trailmap>/shortcuts/**.shortcut.yaml`
    //   - `<trailmap>/trailheads/**.trailhead.yaml`
    // Each dir is walked recursively at any depth — subdirs are organizational only
    // (e.g. `shortcuts/web/`, `trailheads/android/`), matching the existing
    // `<trailmap>/waypoints/` convention. Library-trailmap contract: a trailmap with no `target:`
    // block cannot ship trailhead tools (a trailhead bootstraps to a known waypoint).
    // Enforced here because the trailhead block lives inside the tool YAML, not the
    // manifest, so the check needs the tool decoded first. The waypoints-on-library-trailmap
    // rule is enforced in [TrailblazeTrailmapManifestLoader] where it's visible from the
    // manifest alone.
    val toolPaths = TrailblazeConfigPaths.TRAILMAP_TOOL_LAYOUT.flatMap { (dir, suffix) ->
      loadedManifest.source.listSiblingsRecursive(
        relativeDir = dir,
        suffixes = listOf(suffix),
      )
    }
    val resolvedTools = toolPaths.map { path ->
      val tool = loadTrailmapSibling(path, loadedManifest.source, ToolYamlConfig.serializer(), "trailmap tool")
      if (loadedManifest.manifest.target == null && tool.trailhead != null) {
        throw TrailblazeProjectConfigException(
          "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}) is a library " +
            "trailmap (no target: block) but its tool '$path' declares a trailhead: block. " +
            "Trailheads bootstrap to a known waypoint and only make sense within a target trailmap. " +
            "Move the trailhead tool into a target trailmap, or add a target: block to this trailmap.",
        )
      }
      tool
    }
    val resolvedWaypoints = discoverTrailmapWaypoints(loadedManifest)
    return ResolvedTrailmap(
      manifest = loadedManifest.manifest,
      source = loadedManifest.source,
      target = target,
      toolsets = resolvedToolsets,
      tools = resolvedTools,
      waypoints = resolvedWaypoints,
    )
  }

  /**
   * Waypoint discovery, extracted from [resolveTrailmapSiblings] so a scripted-tool / `target.tools:`
   * resolution failure inside that method can't take a trailmap's waypoints down with it — the Step-4
   * catch in [resolveRuntime] re-runs this best-effort to pool the waypoints anyway.
   *
   * Waypoint YAMLs are auto-discovered from `<trailmap>/waypoints/` (any depth) — the manifest no
   * longer enumerates them. Library-trailmap contract: a trailmap with no `target:` block cannot ship
   * waypoints (a waypoint binds to a target's screen state), so this throws on the library-guard and on
   * malformed waypoint YAML, exactly as the inline block did.
   */
  private fun discoverTrailmapWaypoints(
    loadedManifest: LoadedTrailblazeTrailmapManifest,
  ): List<WaypointDefinition> {
    val waypointPaths = loadedManifest.source.listSiblingsRecursive(
      relativeDir = TRAILMAP_WAYPOINTS_DIR,
      suffixes = TRAILMAP_WAYPOINT_SUFFIXES,
    )
    if (loadedManifest.manifest.target == null && waypointPaths.isNotEmpty()) {
      throw TrailblazeProjectConfigException(
        "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}) is a library " +
          "trailmap (no target: block) but has waypoint files on disk under $TRAILMAP_WAYPOINTS_DIR/: " +
          "${waypointPaths.joinToString(", ")}. " +
          "Library trailmaps cannot own waypoints — waypoints bind to a target's screen state. " +
          "Move the waypoint files into a target trailmap, or add a target: block to this trailmap.",
      )
    }
    return waypointPaths.map { path ->
      loadTrailmapSibling(path, loadedManifest.source, WaypointDefinition.serializer(), "trailmap waypoint")
    }
  }

  /** `_meta` key (post-shortcut-merge) that carries a scripted tool's supported platforms. */
  private const val META_SUPPORTED_PLATFORMS = "trailblaze/supportedPlatforms"

  /**
   * Resolve a single scripted-tool [toolName] against the per-trailmap [registry] to its runtime
   * [InlineScriptToolConfig](s), or `null` if [toolName] is not a scripted tool (no descriptor and
   * no analyzer-enriched config). Mirrors the build-time generator's `resolveScriptedToolByName`.
   *
   * Analyzer-enriched descriptors (meta-only / partial) ride [ScriptedToolRegistryEntry.enrichedConfig];
   * otherwise the typed descriptor is expanded and filtered to the matching entry. Relative `script:`
   * paths are resolved against the descriptor YAML's parent directory for filesystem-backed trailmaps
   * (the only variant the runtime bundler reads today); classpath-backed sources pass through unchanged.
   */
  private fun resolveScriptedToolConfigsByName(
    toolName: String,
    registry: Map<String, ScriptedToolRegistryEntry>,
    source: TrailmapSource,
  ): List<InlineScriptToolConfig>? {
    val match = registry[toolName] ?: return null
    val configs = if (match.enrichedConfig != null) {
      listOf(match.enrichedConfig).filter { it.name == toolName }
    } else {
      match.descriptor.toInlineScriptToolConfigs().filter { it.name == toolName }
    }
    // Defensive invariant: a registry hit means the descriptor declared [toolName] (the registry
    // is keyed by every declared entry's `name:`), so the filter MUST produce at least one entry.
    // An empty result means the descriptor's name index drifted from its declared `name:` fields —
    // a logic bug in `discoverTrailmapScriptedTools` or `toInlineScriptToolConfigs` — and silently
    // returning an empty list lets callers treat it as "resolved", dropping the tool. Fail loudly.
    require(configs.isNotEmpty()) {
      "Internal: scripted-tool descriptor at '${match.relativePath}' is registered under name " +
        "'$toolName' but its decoded config(s) name no entry that matches — registry/descriptor " +
        "name index has drifted. This is a logic bug in the trailmap loader, not an author error."
    }
    return if (source is TrailmapSource.Filesystem) {
      val yamlDir = File(source.trailmapDir, match.relativePath).parentFile ?: source.trailmapDir
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

  /**
   * Validates that a scripted tool declared under `platforms.[platformKey].tools:` is genuinely
   * single-platform: its `supportedPlatforms` (from the resolved `_meta`) must name only
   * [platformKey] (or be absent/empty). Mirrors the build-time generator's
   * `validatePerPlatformScriptedTool` so authoring mistakes fail the same way on both paths.
   */
  private fun validatePerPlatformScriptedTool(
    cfg: InlineScriptToolConfig,
    platformKey: String,
    loadedManifest: LoadedTrailblazeTrailmapManifest,
  ) {
    val rawPlatforms = cfg.meta?.get(META_SUPPORTED_PLATFORMS) as? JsonArray ?: return
    // Non-string entries in `_meta.trailblaze/supportedPlatforms` are caught earlier by the typed
    // `TrailmapScriptedToolFile._meta` decode (which validates that every entry is a YAML string
    // before we get here), so this `mapNotNull` is lenient — analyzer-derived configs that may
    // not flow through that decode and any future construction path are treated leniently
    // (non-string entries silently skip). The build-time generator (which reads the descriptor
    // via the raw kaml tree API, bypassing the typed `_meta` decode) carries its own strict
    // string-only guard at that boundary instead — see `TrailblazeBundledConfigTasks`.
    val supportedPlatforms = rawPlatforms.mapNotNull {
      (it as? JsonPrimitive)?.contentOrNull?.lowercase()
    }
    if (supportedPlatforms.isEmpty()) return
    val platform = platformKey.lowercase()
    if (supportedPlatforms.any { it != platform }) {
      throw TrailblazeProjectConfigException(
        "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): scripted tool " +
          "'${cfg.name}' is declared under `platforms.$platformKey.tools:` but its supportedPlatforms is " +
          "$supportedPlatforms. A scripted tool listed under a single platform must support only that " +
          "platform — declare a cross-platform tool under `target.tools:` instead.",
      )
    }
  }

  private const val TRAILMAP_WAYPOINTS_DIR = "waypoints"
  private val TRAILMAP_WAYPOINT_SUFFIXES = listOf(".waypoint.yaml")

  /**
   * Subdirectory under each trailmap that holds scripted-tool descriptor YAMLs. Same directory
   * the operational tool layout's `*.tool.yaml` entries occupy — the suffix distinguishes
   * the two flavors at discovery time.
   */
  private const val TRAILMAP_SCRIPTED_TOOLS_DIR = "tools"

  /**
   * Line-anchored regex matching the `export const <name> = trailblaze.tool<…>(…)` binding
   * shape the analyzer's [extract-tool-defs.mjs] shim extracts. The `(?m)` flag scopes
   * `^` to line starts, and the optional leading whitespace lets indented modules match —
   * but a `//`-prefixed comment line can't, since `//` doesn't satisfy `\s*export`. The
   * `[<(]` tail accepts both the typed (`<I>(...)`) and the untyped (`(...)`) overloads.
   *
   * Reference-only files (`_example_typescript_tool.ts`) that *mention* the binding shape
   * inside a `//` comment are intentionally excluded — the analyzer wouldn't extract a
   * typed export from them anyway, so admitting them to the deferred bucket would only
   * surface a "no typed declaration found" failure on every workspace compile.
   */
  private val TYPED_TOOL_BINDING_PATTERN =
    Regex("""(?m)^\s*export\s+const\s+(\w+)\s*=\s*trailblaze\.tool\s*[<(]""")

  /**
   * Filename suffixes that mark an operational tool YAML under `<trailmap>/tools/` rather than a
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
   * A scripted-tool descriptor entry in the per-trailmap name registry — the descriptor itself
   * plus the trailmap-relative path it was discovered at. The path is used to resolve relative
   * `script:` references against the descriptor's parent directory and to produce actionable
   * duplicate-name errors that name both contributing files.
   *
   * [enrichedConfig] is non-null when this entry came from a meta-only descriptor that the
   * configured [ScriptedToolEnrichment] already resolved via analyzer extraction. The
   * resolution loop in `resolveTrailmapSiblings` short-circuits to this pre-resolved config
   * instead of re-running `TrailmapScriptedToolFile.toInlineScriptToolConfigs()` — which would
   * throw on the missing top-level `name:`. Null when the descriptor was authored in the
   * legacy full-YAML shape (today's path).
   */
  private data class ScriptedToolRegistryEntry(
    val relativePath: String,
    val descriptor: TrailmapScriptedToolFile,
    val enrichedConfig: InlineScriptToolConfig? = null,
  )

  /**
   * Builds a name-keyed registry of every scripted-tool descriptor under `<trailmap>/tools/`.
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
   *   - `trailblaze-trailmap-bundler/src/main/kotlin/xyz/block/trailblaze/bundle/TrailblazeTrailmapBundler.kt`
   *     `buildScriptedToolRegistry` — emits the typed `.d.ts` augmentation per trailmap.
   *   - `trailblaze-host/src/main/java/xyz/block/trailblaze/scripting/DaemonScriptedToolBundler.kt`
   *     `discoverScriptedToolDescriptors` — esbuild-bundles each script at daemon start.
   *   - `build-logic/src/main/kotlin/TrailblazeBundledConfigTasks.kt`
   *     `buildTrailmapScriptedToolRegistry` — generates the bundled per-target YAMLs under
   *     `dist/targets/`.
   * Build-logic stays independent of `:trailblaze-models` (the Gradle plugin classpath
   * concern documented on `TrailblazeTrailmapBundler`'s class kdoc) so the duplication can't be
   * collapsed into one shared util today; cross-referenced comments are the second-best fix.
   *
   * **Asymmetry as of PR #3338 / #3501**: this loader site is the FIRST to branch on
   * [TrailmapScriptedToolFile.requiresEnrichment] — meta-only descriptors (no top-level `name:`,
   * no `tools:`, just `script:` + `_meta:`) are collected into a `deferred` bucket and
   * resolved via the optional [ScriptedToolEnrichment] hook to recover the typed surface
   * from the sibling `.ts`. PR #3501 widened the same asymmetry to bare `.ts` files — see
   * the second-pass walk below. The three other sister sites still walk `*.yaml` only and
   * hard-fail (or skip-and-log) on meta-only / bare-`.ts` shapes.
   *
   * **Why the asymmetry is safe today.** The auto-discovery + analyzer-enrichment combo is
   * a strict superset of the three sister walks: the loader produces fully-resolved
   * [InlineScriptToolConfig] entries (name + absolute script path + inputSchema + meta)
   * from the analyzer's `.ts` extraction. Production runtime consumers downstream of the
   * loader read those resolved configs — they never re-walk `<trailmap>/tools/`:
   *   - **Daemon session start** ([xyz.block.trailblaze.host.TrailblazeHostYamlRunner])
   *     calls `DaemonScriptedToolBundler.bundleOne(scriptFile, name)` per pre-resolved
   *     [InlineScriptToolConfig]. The sister `discoverScriptedToolDescriptors` /
   *     `bundleAll` path is test-only (see [DaemonScriptedToolBundler.bundleAll]'s
   *     "Test-only entry point" kdoc).
   *   - **`TrailblazeBundlePlugin.bundleTrailblazeTrailmap`** (and therefore
   *     `TrailblazeTrailmapBundler.buildScriptedToolRegistry`) is gated by
   *     `trailblazeBundle { bundleEnabled = ... }`. The example workspaces (wikipedia,
   *     ios-contacts, playwright-native) that adopt bare-`.ts` authoring set
   *     `bundleEnabled = false` — the `compileTrailblazeWorkspace` half (which runs the
   *     loader through `WorkspaceCompileBootstrap`) is the source of truth for them.
   *   - **`TrailblazeBundledConfigPlugin.generateBundledTrailblazeConfig`** (and therefore
   *     `TrailblazeBundledConfigTasks.buildTrailmapScriptedToolRegistry`) is applied only
   *     to closed-source consumer trailmaps that deliberately keep full-YAML descriptors
   *     (no analyzer in that path).
   *
   * If you're maintaining ANY of the four sites and meta-only / bare-`.ts` support has
   * matured into a use case those three sites need to honor (e.g. `bundleEnabled = true`
   * with bare `.ts` tools, or `TrailblazeBundledConfigPlugin` consumers adopting the
   * typed authoring shape), mirror the loader's enrichment branching + `.ts` walk across
   * the rest so the runtime/build-time parity holds end-to-end.
   *
   * Search tag for grepping all four sister implementations at once (resilient against
   * future file moves): `SISTER-IMPL-TAG: trailmap-scripted-tool-discovery`.
   */
  /**
   * Discovery result: the name-keyed registry plus the list of descriptor trailmap-relative paths
   * that were skipped because their decode failed. The skip list is plumbed downstream to the
   * UnknownScriptedToolName diagnostic so an author whose `target.tools:` references the
   * skipped file's intended name gets pointed at it directly rather than just seeing a
   * Console.log warning earlier in the build log.
   *
   * [classpathSkippedNames] holds the declared tool names whose enrichment was skipped on the
   * classpath branch (analyzer can't walk classpath `.ts` sources). A `target.tools:` entry naming
   * one of these throws [ClasspathScriptedToolUnavailableException] (the recoverable subtype) rather
   * than the generic unknown-name error — keeping the trailmap's waypoints/toolsets alive.
   */
  private data class ScriptedToolDiscoveryResult(
    val registry: Map<String, ScriptedToolRegistryEntry>,
    val skipped: List<String>,
    val classpathSkippedNames: Set<String> = emptySet(),
  )

  private fun discoverTrailmapScriptedTools(
    loadedManifest: LoadedTrailblazeTrailmapManifest,
    scriptedToolEnrichment: ScriptedToolEnrichment?,
  ): ScriptedToolDiscoveryResult {
    val candidatePaths = loadedManifest.source
      // Recursive so scripted-tool descriptors organized into `tools/<subdir>/` (e.g. by
      // platform/category) are discovered — matching the `.tool.yaml` walk above and the
      // analyzer/catalog/runtime, which all recurse. `relativePath` carries the subdir prefix.
      .listSiblingsRecursive(relativeDir = TRAILMAP_SCRIPTED_TOOLS_DIR, suffixes = listOf(".yaml"))
      .filter { path -> OPERATIONAL_TOOL_YAML_SUFFIXES.none { suffix -> path.endsWith(suffix) } }
    val registry = linkedMapOf<String, ScriptedToolRegistryEntry>()
    val skipped = mutableListOf<String>()
    // Trailmap-relative `.ts` paths that an existing YAML descriptor's `script:` field
    // already covers — collected so the second-pass `.ts` walk below can skip them
    // without double-registering. Populated as each YAML descriptor decodes; a YAML
    // that fails to decode contributes nothing here, which is the right behavior:
    // the broken YAML's intended `.ts` partner re-enters the candidate pool through
    // the `.ts` walk and surfaces a normal load-time diagnostic instead of being
    // shadowed by the skipped YAML.
    val yamlCoveredScriptPaths = linkedSetOf<String>()
    // Meta-only descriptors (no `name:` and no `tools:` — see `TrailmapScriptedToolFile.requiresEnrichment`)
    // are collected here for a single batched enrichment pass. The analyzer subprocess spawn is
    // amortized: one node invocation per trailmap, not per descriptor. Empty when there are no
    // meta-only descriptors AND `target.tools:` doesn't reference any unknown-yet name —
    // i.e. the common case where every author has authored a full YAML descriptor.
    val deferred = mutableListOf<ScriptedToolEnrichment.DeferredDescriptor>()
    candidatePaths.forEach { path ->
      // Per-descriptor decode wrapped in try/log/skip so a single malformed (or half-written
      // WIP) file under `<trailmap>/tools/` doesn't tank the entire trailmap at load time. The
      // referenced-name resolution downstream surfaces a clear UnknownScriptedToolName error
      // if `target.tools:` actually points at the skipped file's name — so the failure stays
      // visible to anyone who relied on the tool, just not to trailmaps that never referenced it.
      // See lead-dev review #2 (round 2) for the rationale.
      val descriptor = try {
        loadTrailmapSibling(
          path,
          loadedManifest.source,
          TrailmapScriptedToolFile.serializer(),
          "trailmap scripted tool",
        )
      } catch (e: TrailblazeProjectConfigException) {
        Console.log(
          "Note: skipping malformed scripted-tool descriptor '$path' in trailmap " +
            "'${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): ${e.message}. " +
            "Sibling descriptors still register; any `target.tools:` entry that names a tool " +
            "from this file will fail with UnknownScriptedToolName until the file is fixed.",
        )
        skipped += path
        return@forEach
      }
      // Record the trailmap-relative `.ts` (or `.js`) path this YAML claims via `script:`
      // so the auto-discovery walk below treats it as covered. Skip non-relative or odd
      // shapes — the legacy resolution path will surface those as their own errors and
      // we don't want a malformed `script:` field to suppress the same `.ts`'s own
      // auto-discovery entry.
      resolveDescriptorScriptToTrailmapRelative(path, descriptor.script)
        ?.let { yamlCoveredScriptPaths += it }
      val multi = descriptor.tools
      val single = descriptor.name
      val declaredNames: List<String> = when {
        multi != null -> multi.map { it.name }
        single != null -> listOf(single)
        descriptor.requiresEnrichment() -> {
          // Meta-only descriptor — `script:` + `_meta:` only, no `name:` or `tools:`. The
          // `.ts` file's `trailblaze.tool<I, O>({...})` declaration is the source of truth
          // for `name:` and `inputSchema:`; if an enrichment strategy is wired up we'll
          // ask it to recover those from the analyzer. Held in the `deferred` bucket until
          // the discovery walk completes so the analyzer can run once per trailmap rather than
          // once per descriptor.
          deferred += ScriptedToolEnrichment.DeferredDescriptor(
            relativePath = path,
            descriptor = descriptor,
          )
          return@forEach
        }
        else -> {
          // Same skip-and-log treatment for any other shape that decodes successfully but
          // doesn't fit one of the recognized author surfaces. A WIP file with both `name:`
          // and `tools:` typed in by mistake would also land here.
          Console.log(
            "Note: skipping scripted-tool descriptor '$path' in trailmap " +
              "'${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}) — must " +
              "declare either a top-level `name:` (single-tool shape), a `tools:` list " +
              "(multi-tool shape), or `_meta:` with a sibling `.ts` file (meta-only shape). " +
              "Sibling descriptors still register.",
          )
          skipped += path
          return@forEach
        }
      }
      // Partial single-tool / multi-tool descriptors carry their tool names eagerly (handled
      // above) AND need analyzer enrichment to fill in description / inputSchema from the
      // sibling `.ts`. Push them onto the deferred bucket too — the eager registry entry
      // below gets upgraded with the resolved config in `enrichDeferredDescriptors` after
      // the analyzer subprocess returns.
      //
      // SISTER-IMPL-TAG: partial-descriptor-eager-upgrade. The downstream
      // `enrichDeferredDescriptors` short-circuits on `previous.relativePath ==
      // result.relativePath` to upgrade-in-place rather than throw the dup-name error.
      // The two sites form a two-phase commit; edit either one without updating the
      // other and the upgrade collapses into a "two descriptors declare the same name"
      // false positive. Grep this tag to find the matching half.
      if (descriptor.requiresEnrichment()) {
        deferred += ScriptedToolEnrichment.DeferredDescriptor(
          relativePath = path,
          descriptor = descriptor,
        )
      }
      declaredNames.forEach { name ->
        // Reject blank tool names symmetrically with the bundler's `BlankToolName` guard —
        // `name: ""` (or whitespace-only) decodes successfully but would register under the
        // empty key, where a `target.tools: [""]` typo or the runtime tool repo's later
        // registration would silently match it. SISTER-IMPL-TAG: trailmap-scripted-tool-discovery.
        if (name.isBlank()) {
          throw TrailblazeProjectConfigException(
            "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
              "scripted-tool descriptor '$path' declares a blank tool name. Tool names " +
              "must be non-empty and contain at least one non-whitespace character.",
          )
        }
        val previous = registry[name]
        if (previous != null) {
          throw TrailblazeProjectConfigException(
            "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
              "two scripted-tool descriptors under <trailmap>/tools/ declare the same tool name " +
              "'$name': '${previous.relativePath}' and '$path'. Tool names must be unique within " +
              "a trailmap — rename one of the descriptors' `name:` field (or, for a multi-tool " +
              "descriptor, the offending entry under `tools:`).",
          )
        }
        registry[name] = ScriptedToolRegistryEntry(relativePath = path, descriptor = descriptor)
      }
    }
    // Second pass: walk `<trailmap>/tools/*.ts` for bare typed-tool sources that have NO
    // sibling YAML descriptor. Each surviving `.ts` synthesizes a meta-only
    // `TrailmapScriptedToolFile(script = "./<file>.ts")` and rides the same analyzer
    // enrichment path as the YAML-declared meta-only descriptors above. The result: a
    // workspace can author a tool as a single `.ts` file (with `trailblaze.tool<I>(spec,
    // handler)`) and skip the YAML entirely — the YAML stays available as an optional
    // override when an author needs to pin `name:` / `description:` / multi-tool
    // semantics.
    //
    // Skip rules, in order:
    //   1. Type-declaration sidecars (`*.d.ts`) — never executable, never a tool source.
    //   2. Co-located test fixtures (`*.test.ts`) — convention used by the contacts +
    //      playwright-native examples for type-level smoke tests.
    //   3. Any `.ts` an existing YAML's `script:` field already points at — the YAML
    //      already drives the analyzer for that `.ts`; double-registration would either
    //      collide on the tool name or duplicate the analyzer subprocess work.
    //   4. Files that don't carry an `export const X = trailblaze.tool<…>(…)`
    //      binding — helpers / shared modules (`*_shared.ts`) and reference
    //      examples (`_example_*.ts` whose body shows the call shape inside a
    //      `//` comment). The pre-grep is intentionally tight: it matches the
    //      analyzer's own extraction contract (`extract-tool-defs.mjs` only
    //      recognizes `export const <name> = trailblaze.tool(...)`), so any file
    //      that wouldn't surface a typed export to the analyzer also doesn't
    //      enter the deferred bucket. A looser substring match would admit
    //      reference docs that *mention* `trailblaze.tool(...)` in comments and
    //      surface a "no typed declaration found" failure on every workspace
    //      compile — fast to fix but noisy. Keep the regex aligned with the
    //      analyzer's signature recognition; broadening one without the other
    //      reintroduces that false-positive class.
    //
    //      **Silent skip — intentional.** A `.ts` that imports `@trailblaze/scripting`
    //      but uses the legacy `export async function foo(...)` shape (no
    //      `trailblaze.tool` binding) skips silently — no log line. We considered
    //      emitting a sparse "looks like a tool but no binding" diagnostic but every
    //      heuristic either over-fires (any `.ts` mentioning the SDK package — pure
    //      type imports, dev-prose comments) or under-fires (parsing import statements
    //      at the loader layer pulls a TypeScript parser into `:trailblaze-common`,
    //      the wrong module). The right diagnostic for a legacy-shape author is the
    //      explicit `UnknownScriptedToolName` they hit when they list the name in
    //      `target.tools:` — a `.ts` that's silent here AND silent there is
    //      genuinely unused and doesn't deserve a discovery-time warning.
    //
    // Synthetic descriptors take the `.ts` path itself as their [relativePath] (rather
    // than a fabricated `.yaml` sibling) so any downstream diagnostic naming the
    // descriptor file points at the actual source on disk.
    val tsCandidates = loadedManifest.source
      // Recursive so bare `.ts` tools organized into `tools/<subdir>/` are discovered (matching the
      // `.yaml`/`.tool.yaml` walks). The subdir-prefixed `path` is used as the synthetic descriptor's
      // [relativePath] — i.e. the actual `.ts` location on disk — so the analyzer resolves it there.
      .listSiblingsRecursive(relativeDir = TRAILMAP_SCRIPTED_TOOLS_DIR, suffixes = listOf(".ts"))
    tsCandidates.forEach { path ->
      if (path.endsWith(".d.ts")) return@forEach
      if (path.endsWith(".test.ts")) return@forEach
      if (path in yamlCoveredScriptPaths) return@forEach
      val content = loadedManifest.source.readSibling(path) ?: return@forEach
      // A descriptor-less `.ts` is a meta-only tool source only when it declares EXACTLY ONE typed
      // export. Zero = a helper/shared module. Two-or-more = a multi-export file the meta-only path
      // can't disambiguate (it needs a YAML `tools:` descriptor naming the exports), so skip it here
      // rather than letting enrichment throw and fail the whole workspace; a `target.tools:`
      // reference to such a name still surfaces the located UnknownScriptedToolName. Recursive
      // discovery now reaches subdir reference examples the old flat walk never saw (e.g. the
      // android-sample-app `host-tools/` / `quickjs-tools/` multi-export examples).
      if (TYPED_TOOL_BINDING_PATTERN.findAll(content).count() != 1) return@forEach
      val basename = path.substringAfterLast('/')
      deferred += ScriptedToolEnrichment.DeferredDescriptor(
        relativePath = path,
        descriptor = TrailmapScriptedToolFile(script = "./$basename"),
      )
    }
    // Resolve any meta-only descriptors collected above. Batched outside the walk so a
    // wired-in analyzer-backed enrichment can spawn one Node subprocess per trailmap rather
    // than one per descriptor.
    val classpathSkippedNames = if (deferred.isNotEmpty()) {
      enrichDeferredDescriptors(loadedManifest, deferred, scriptedToolEnrichment, registry)
    } else {
      emptySet()
    }
    return ScriptedToolDiscoveryResult(registry, skipped, classpathSkippedNames)
  }

  /**
   * Resolve a YAML descriptor's `script:` field into the trailmap-relative path of the
   * `.ts`/`.js` it points at, or null when the field's shape can't be reduced to a
   * single trailmap-relative path (absolute path, blank, parent-traversing past the
   * trailmap root, etc.). Used by [discoverTrailmapScriptedTools] to dedupe the
   * `.ts` auto-discovery walk against YAML-declared coverage.
   *
   * The resolution mirrors [resolveTrailmapSiblings]'s own `script:` resolution
   * (relative to the YAML's parent directory, then normalized) so a `.ts` path produced
   * here is byte-identical to the path [TrailmapSource.listSiblings] would emit for the
   * same file. Path math only — no I/O — so a `script:` that points at a file that
   * doesn't exist still gets normalized and added to the covered set (the YAML's own
   * enrichment will fail and report the missing file with a directed diagnostic).
   */
  private fun resolveDescriptorScriptToTrailmapRelative(
    descriptorRelativePath: String,
    scriptRef: String,
  ): String? {
    if (scriptRef.isBlank()) return null
    val raw = File(scriptRef)
    if (raw.isAbsolute || scriptRef.startsWith("/")) return null
    val descriptorDir = descriptorRelativePath.substringBeforeLast('/', missingDelimiterValue = "")
    val combined = if (descriptorDir.isEmpty()) scriptRef else "$descriptorDir/$scriptRef"
    val normalized = java.nio.file.Paths.get(combined).normalize().toString()
      .replace(File.separatorChar, '/')
    // Reject any normalized path that escapes the trailmap root (leading `..`). Such a
    // `script:` would surface its own resolution error downstream; we just don't want
    // it polluting the covered-set with an out-of-tree path.
    if (normalized.startsWith("../") || normalized == "..") return null
    return normalized
  }

  /**
   * Resolve meta-only descriptors against the optional [scriptedToolEnrichment] and merge the
   * results into [registry]. Mutates [registry] in place.
   *
   * **Classpath-bundled trailmap carries a meta-only descriptor — logged and SKIPPED, not thrown.**
   * The analyzer can't walk classpath `.ts` sources, but the daemon's runtime scripted-tool dispatch
   * is served by the build-time-baked `targets/<id>.yaml`, not this trailmap-sibling path; throwing
   * here would abort the whole trailmap and silently drop its waypoints/toolsets, so we log and skip
   * the deferred descriptors instead (preserving waypoints/toolsets).
   *
   * **Failure modes — surfaced as `TrailblazeProjectConfigException`:**
   *  - Loader caller didn't wire enrichment: the author opted into meta-only, but the
   *    loading context (e.g., a test fixture, an on-device runtime) can't run an analyzer.
   *    The diagnostic names every meta-only descriptor in the trailmap so the author knows
   *    which files to either fill in or remove.
   *  - The analyzer found no typed declaration in the descriptor's `.ts`: most commonly an
   *    author wrote a meta-only YAML but the `.ts` still uses the legacy
   *    `export async function foo(args, ctx, client)` shape that the analyzer doesn't
   *    extract. The diagnostic points at the descriptor and the analyzer's reason.
   *  - The enrichment produced a duplicate tool name (either against another meta-only
   *    descriptor in the same trailmap, or against a full-YAML descriptor): same "two
   *    descriptors declare the same name" error as the legacy discovery walk emits.
   */
  private fun enrichDeferredDescriptors(
    loadedManifest: LoadedTrailblazeTrailmapManifest,
    deferred: List<ScriptedToolEnrichment.DeferredDescriptor>,
    scriptedToolEnrichment: ScriptedToolEnrichment?,
    registry: MutableMap<String, ScriptedToolRegistryEntry>,
  ): Set<String> {
    val filesystemSource = loadedManifest.source as? TrailmapSource.Filesystem
    if (filesystemSource == null) {
      // Classpath-loaded trailmap (e.g. the daemon running from the compose uber JAR): the
      // analyzer can't walk classpath `.ts` sources, so these enrichment-shape descriptors can't
      // be resolved here. Do NOT throw — throwing aborts the WHOLE trailmap and silently drops its
      // waypoints/toolsets (a single real trailmap can ship 100+ waypoints). The daemon's runtime scripted-tool
      // DISPATCH is served by the build-time-baked `targets/<id>.yaml` (via
      // AppTargetYamlConfig.getInlineScriptTools), NOT this trailmap-sibling path, so skipping the
      // deferred scripted tools here loses nothing the daemon dispatches from — while the
      // trailmap's waypoints/toolsets (which only this path provides) are preserved. Log and skip.
      val files = deferred.joinToString(", ") { "'${it.relativePath}'" }
      Console.log(
        "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
          "scripted-tool descriptor(s) $files need analyzer enrichment but the trailmap is " +
          "classpath-loaded, where the analyzer can't walk `.ts` sources. Skipping these " +
          "scripted-tool descriptors (runtime dispatch is served by the baked targets/<id>.yaml); " +
          "the trailmap's waypoints and toolsets are preserved.",
      )
      // Remove the PARTIAL descriptors' eager registry entries (single-tool `name:` / each
      // multi-tool `tools[].name:`) — a partial entry carries an empty description/inputSchema, so a
      // `target.tools:` that resolved it via `toInlineScriptToolConfigs()` would emit a half-baked
      // target that `AppTargetDiscovery.mergeTargets` could let OVERRIDE the baked `targets/<id>.yaml`.
      // Meta-only / bare-`.ts` descriptors never registered an eager entry (their name comes from the
      // `.ts` export), so there's nothing to remove for those.
      val eagerNames = deferred.flatMap { it.descriptor.declaredScriptedToolNames() }
      eagerNames.forEach { name -> registry.remove(name) }
      // Tool names whose `target.tools:` resolution should throw the recoverable
      // [ClasspathScriptedToolUnavailableException] (so waypoints survive) instead of the generic
      // unknown-name error. For partial descriptors that's the eager `name:`. For meta-only / bare-`.ts`
      // descriptors the name lives in the `.ts`'s `export const <name> = trailblaze.tool(...)` binding —
      // the same shape the analyzer extracts — so we read it straight from the source. A name that
      // isn't one of these is a genuine unknown-name author error and keeps throwing the generic error.
      val exportedNames = deferred.flatMap { descriptorExportedToolNames(loadedManifest.source, it) }
      return (eagerNames + exportedNames).toSet()
    }
    if (scriptedToolEnrichment == null) {
      val files = deferred.joinToString(", ") { "'${it.relativePath}'" }
      throw TrailblazeProjectConfigException(
        "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
          "scripted-tool descriptor(s) $files require analyzer enrichment (meta-only, " +
          "partial single-tool, or partial multi-tool authoring shape). No " +
          "`ScriptedToolEnrichment` was wired into " +
          "`TrailblazeProjectConfigLoader.resolveRuntime(...)`. Either provide one (the " +
          "JVM host's analyzer-backed impl resolves the `.ts` declarations) or fully " +
          "populate each descriptor with `name:` + `description:` + `inputSchema:` so " +
          "enrichment is no longer required.",
      )
    }
    val trailmapDir = filesystemSource.trailmapDir
    val trailmapToolsDir = File(trailmapDir, TRAILMAP_SCRIPTED_TOOLS_DIR)
    val enriched = scriptedToolEnrichment.enrich(
      trailmapId = loadedManifest.manifest.id,
      trailmapDir = trailmapDir,
      trailmapToolsDir = trailmapToolsDir,
      deferredDescriptors = deferred,
    )
    val deferredByPath = deferred.associateBy { it.relativePath }
    enriched.forEach { result ->
      when (result) {
        is ScriptedToolEnrichment.EnrichmentResult.Failed -> {
          // The descriptor path may be either a `.yaml` (full / partial / meta-only
          // descriptor) or a bare `.ts` (auto-discovered with no sibling YAML — `.ts`
          // walk in [discoverTrailmapScriptedTools]'s second pass). The recovery
          // recommendation handles both: write or extend a sibling YAML descriptor
          // OR fix the `.ts` itself. Naming the path verbatim lets the author find
          // either file by clicking through the diagnostic.
          throw TrailblazeProjectConfigException(
            "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
              "scripted-tool descriptor '${result.relativePath}' could not be enriched — " +
              "${result.reason}. Either author a sibling YAML descriptor pinning " +
              "`name:` + `inputSchema:` (full or partial authoring shape), or fix the " +
              "`.ts` to declare a `trailblaze.tool<I, O>({...})` export the analyzer can extract.",
          )
        }
        is ScriptedToolEnrichment.EnrichmentResult.Resolved -> {
          // Enforce the `ScriptedToolEnrichment.enrich` contract: every result's
          // relativePath must be one of the input descriptors'. A violation is a
          // programmer bug in the enrichment impl — fail loud, name the offending path
          // and impl class so the regression is grep-able.
          val originalDescriptor = deferredByPath[result.relativePath]?.descriptor
            ?: error(
              "Enrichment returned a result for an unknown descriptor path " +
                "'${result.relativePath}' (known: ${deferredByPath.keys.sorted()}) — " +
                "implementation bug in ${scriptedToolEnrichment::class.simpleName}: " +
                "EnrichmentResult.relativePath must equal a DeferredDescriptor.relativePath " +
                "from the input.",
            )
          result.configs.forEach { config ->
            val name = config.name
            // Defense in depth: blank name from enrichment is an implementation bug, but
            // surface it like the legacy walk does rather than letting it pollute the registry.
            if (name.isBlank()) {
              throw TrailblazeProjectConfigException(
                "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
                  "descriptor '${result.relativePath}' enriched to a blank tool name. " +
                  "Tool names must be non-empty.",
              )
            }
            val previous = registry[name]
            // Partial descriptors (single-tool or multi-tool with `name:` already set) were
            // registered eagerly in the discovery walk above so the dup-name check + the
            // downstream `target.tools:` resolution see them right away. Enrichment then
            // upgrades the entry with the analyzer-resolved config. The previous entry's
            // [relativePath] matching the result's is the safe signal — same file, same name,
            // just now with analyzer-derived description / inputSchema folded in.
            //
            // SISTER-IMPL-TAG: partial-descriptor-eager-upgrade. Grep this tag to find
            // the matching half — the discovery walk above both registers the eager entry
            // AND pushes the descriptor onto the `deferred` bucket. Removing the
            // eager-register pass without updating this branch breaks the upgrade-in-place
            // contract and produces a confusing "two descriptors declare the same name"
            // error pointing at the same file twice.
            if (previous != null && previous.relativePath == result.relativePath) {
              registry[name] = previous.copy(enrichedConfig = config)
              return@forEach
            }
            if (previous != null) {
              throw TrailblazeProjectConfigException(
                "Trailmap '${loadedManifest.manifest.id}' (${loadedManifest.source.describe()}): " +
                  "two scripted-tool descriptors under <trailmap>/tools/ declare the same tool name " +
                  "'$name': '${previous.relativePath}' and '${result.relativePath}' (the latter " +
                  "via analyzer enrichment from its sibling `.ts`). Tool names must be unique " +
                  "within a trailmap — rename one of the descriptors' `name:` field or the `.ts` " +
                  "file's exported const.",
              )
            }
            registry[name] = ScriptedToolRegistryEntry(
              relativePath = result.relativePath,
              descriptor = originalDescriptor,
              enrichedConfig = config,
            )
          }
        }
      }
    }
    // Filesystem path resolves (or throws) every deferred descriptor — nothing is classpath-skipped.
    return emptySet()
  }

  /**
   * The tool names a descriptor registers EAGERLY during the discovery walk — multi-tool
   * `tools[].name:` (each) or single-tool `name:`. Meta-only / bare-`.ts` descriptors register no
   * eager name (their name lives in the `.ts` export), so they return empty. Matches the eager
   * registration in [discoverTrailmapScriptedTools].
   */
  private fun TrailmapScriptedToolFile.declaredScriptedToolNames(): List<String> {
    // Bind to local vals before the null checks: `tools` / `name` are cross-module public-API
    // properties that can't be smart-cast in place. Mirrors the eager-registration walk in
    // [discoverTrailmapScriptedTools] (`val multi = descriptor.tools` …) exactly.
    val multi = tools
    val single = name
    return when {
      multi != null -> multi.map { it.name }
      single != null -> listOf(single)
      else -> emptyList()
    }
  }

  /**
   * The scripted-tool names a deferred descriptor would resolve `target.tools:` entries to. Uses
   * the descriptor's declared `name:` / `tools[].name:` when present; otherwise (meta-only / bare-`.ts`)
   * reads the names from the sibling `.ts`'s `export const <name> = trailblaze.tool(...)` bindings —
   * the same binding shape the analyzer extracts — so the classpath-skip narrowing matches the exact
   * names an enriched run would have registered. Returns empty when the `.ts` can't be read.
   */
  private fun descriptorExportedToolNames(
    source: TrailmapSource,
    deferred: ScriptedToolEnrichment.DeferredDescriptor,
  ): List<String> {
    val declared = deferred.descriptor.declaredScriptedToolNames()
    if (declared.isNotEmpty()) return declared
    val scriptPath = resolveDescriptorScriptToTrailmapRelative(deferred.relativePath, deferred.descriptor.script)
      ?: return emptyList()
    val content = source.readSibling(scriptPath) ?: return emptyList()
    return TYPED_TOOL_BINDING_PATTERN.findAll(content).map { it.groupValues[1] }.toList()
  }

  private fun describeAvailableScriptedTools(
    registry: Map<String, ScriptedToolRegistryEntry>,
  ): String = if (registry.isEmpty()) {
    "No scripted-tool descriptors discovered under <trailmap>/tools/."
  } else {
    "Available tool names: [${registry.keys.sorted().joinToString(", ")}]."
  }

  private fun <T> loadTrailmapSibling(
    refPath: String,
    source: TrailmapSource,
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
    // From TrailmapSource.readSibling containment validation OR YAML decode constraints.
    // Either is a user-side error against this trailmap and should drop just the trailmap —
    // not crash sibling-trailmap resolution. The atomic-per-trailmap catch in
    // resolveTrailmapArtifacts logs and continues.
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

  private data class ResolvedTrailmapArtifacts(
    /**
     * Target-trailmap ids that successfully resolved (workspace-listed roots and their
     * transitive deps that surfaced runnable targets). Populates
     * [TrailblazeProjectConfig.targets] post-resolution.
     */
    val successfulTargetIds: List<String> = emptyList(),
    val targets: List<AppTargetYamlConfig> = emptyList(),
    val toolsets: List<ToolSetYamlConfig> = emptyList(),
    val tools: List<ToolYamlConfig> = emptyList(),
    val waypoints: List<WaypointDefinition> = emptyList(),
    /** Every trailmap that completed sibling resolution — surfaced via [TrailblazeResolvedConfig]. */
    val resolvedTrailmaps: List<ResolvedTrailmap> = emptyList(),
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
open class TrailblazeProjectConfigException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

/**
 * Thrown when a trailmap's `target.tools:` names a scripted tool whose descriptor was skipped on
 * the classpath enrichment branch (a classpath-loaded trailmap carries an enrichment-shape
 * descriptor the analyzer can't walk — see [TrailblazeProjectConfigLoader] enrichDeferredDescriptors).
 *
 * This is the ONLY [TrailblazeProjectConfigException] subtype whose waypoints are recovered when a
 * trailmap fails to resolve: the failure is a known by-product of the classpath skip, not a genuine
 * authoring mistake, so the trailmap's waypoints/toolsets must survive. Every other
 * [TrailblazeProjectConfigException] keeps the atomic-per-trailmap drop (no recovery).
 */
class ClasspathScriptedToolUnavailableException(message: String, cause: Throwable? = null) :
  TrailblazeProjectConfigException(message, cause)

/**
 * A trailmap manifest after sibling refs are resolved but before dependency-graph defaults
 * are applied. Surfaced on [TrailblazeResolvedConfig.resolvedTrailmaps] so codegen consumers
 * (per-trailmap `client.d.ts` emitter) can read trailmap-local declarations without re-walking
 * the workspace's trailmap manifests.
 *
 * @property manifest The parsed trailmap manifest with its `id`, `dependencies`, optional
 *   `target:` block, optional top-level `platforms:` (library trailmaps), and optional
 *   `exports:` list.
 * @property source Where the trailmap was loaded from — filesystem trailmapDir or classpath
 *   resource prefix. Codegen that writes per-trailmap output (e.g. `client.d.ts`) skips
 *   classpath-backed trailmaps since they can't be written into a JAR.
 * @property target The resolved [AppTargetYamlConfig] for target trailmaps; null for library
 *   trailmaps (those without a `target:` block).
 * @property toolsets Toolset YAML configs declared via the trailmap manifest's `toolsets:` list.
 * @property tools Operational tool YAMLs auto-discovered under the trailmap's `tools/` /
 *   `shortcuts/` / `trailheads/` directories. These are the pure-YAML composed tools
 *   (`.tool.yaml`), not the scripted tools listed under `target.tools:` (those live on
 *   [target] via `AppTargetYamlConfig.tools`).
 * @property waypoints Waypoints auto-discovered under the trailmap's `waypoints/` directory.
 */
data class ResolvedTrailmap(
  val manifest: TrailblazeTrailmapManifest,
  val source: TrailmapSource,
  val target: AppTargetYamlConfig?,
  val toolsets: List<ToolSetYamlConfig>,
  val tools: List<ToolYamlConfig>,
  val waypoints: List<WaypointDefinition>,
)
