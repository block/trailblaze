package xyz.block.trailblaze.config.project

import java.io.File
import java.io.IOException
import java.util.WeakHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.llm.config.ClasspathResourceDiscovery
import xyz.block.trailblaze.util.Console

/**
 * Resilient trailmap-manifest loader. Each trailmap file is isolated so one malformed trailmap never aborts
 * discovery of the rest of the workspace's trailmaps.
 *
 * Two entry points:
 * - [loadAllResilient] — workspace `trailmaps:` entries (filesystem refs anchored at `trailblaze.yaml`)
 * - [discoverAndLoadFromClasspath] — framework-bundled trailmaps shipped on the classpath
 *
 * Both produce [LoadedTrailblazeTrailmapManifest] objects that carry a [TrailmapSource] so downstream
 * sibling-file reads (waypoints, toolset/tool refs, etc.) work uniformly regardless of origin.
 */
object TrailblazeTrailmapManifestLoader {

  private const val TRAILMAPS_RESOURCE_DIR = "trails/config/trailmaps"
  private const val TRAILMAP_FILENAME = "trailmap.yaml"

  /**
   * Manifest top-level fields removed in favour of the unified `dependencies:`
   * field. Detected by string scan in [warnOnRemovedComposeFields] so authors who
   * upgrade and still have these fields get a one-shot deprecation warning rather than
   * silent data loss (kaml `strictMode = false` drops unknown keys without complaint).
   */
  private val REMOVED_COMPOSE_FIELDS = listOf("use", "extend", "replace")

  /**
   * Diagnostic prefix for the advisory trailmap-scoping check (see [warnOnTrailmapScoping]).
   * `grep "\[TrailmapScopingCheck\]"` isolates these warnings from the rest of the loader's
   * log surface.
   */
  private const val SCOPING_CHECK_PREFIX = "[TrailmapScopingCheck]"

  /**
   * Identifier-shape rule for trailmap ids. Single lowerCamelCase token: no underscores,
   * no dashes. The underscore restriction is load-bearing — see the 2026-05-27 devlog
   * decision section: if both `foo` and `foo_bar` could be trailmap ids, the wire name
   * `foo_bar_X` would split ambiguously as `<foo>_bar_X` vs `<foo_bar>_X`. Pinning ids
   * to a single token removes that ambiguity by construction, so the resolver can split
   * an `X_<localName>` wire name at the first underscore without context.
   */
  private val TRAILMAP_ID_REGEX = Regex("^[a-z][a-zA-Z0-9]*$")

  /**
   * Framework library trailmap id. Exempt from the trailmap-scoping check because its
   * primitives (`tap`, `tapOnElementBySelector`, `web_evaluate`, `mobile_*`, `android_*`
   * etc.) ship in every recording and renaming them would force a repo-wide rewrite of
   * every `.trail.yaml` for no real win. The framework trailmap is the implicit
   * "unscoped" namespace — a tool name with no `<trailmapId>_` prefix resolves there
   * via the existing flat registry.
   */
  private const val FRAMEWORK_TRAILMAP_ID = "trailblaze"

  /**
   * Manifest top-level fields removed in 2026-04-28 as part of the shortcuts-as-tools
   * unification. Detected by string scan in [warnOnRemovedNavFields] for the same
   * "kaml drops unknown keys silently" reason as [REMOVED_COMPOSE_FIELDS].
   *
   * `routes:` was the reserved slot for multi-hop navigation paths; it was dropped
   * because a multi-hop path is just a shortcut whose body invokes other shortcut tools.
   */
  private val REMOVED_NAV_FIELDS = listOf("routes")

  /**
   * Per-classloader cache for [discoverAndLoadFromClasspath].
   *
   * Trailmap discovery walks the entire classpath (file + jar protocols) and parses every
   * matching `<id>/trailmap.yaml`, which is too expensive to repeat on every target/tool
   * resolution. The cache key is the [ClassLoader] in effect at the time of the lookup,
   * so tests that swap [Thread.currentThread]'s context classloader (via
   * `ClasspathFixture.withClasspathRoot`) naturally miss the cache and rediscover from the
   * new classpath. [WeakHashMap] keys ensure dead classloaders can be GC'd.
   *
   * Mirrors the cache pattern in `TrailblazeSerializationInitializer.buildYamlDefinedTools`.
   */
  private val classpathTrailmapCache = WeakHashMap<ClassLoader, List<LoadedTrailblazeTrailmapManifest>>()
  private val classpathTrailmapCacheLock = Any()

  /**
   * Trailmap identifiers (`<trailmap id>@<source identifier>`) we've already emitted a reserved-field
   * warning for. The schema-permissive design accepts populated `use:` / `extend:` / etc.
   * fields with no runtime effect, and warns once per trailmap so the operator knows runtime
   * composition is deferred. Without this dedup, the warning re-emits on every load — every
   * classpath rediscovery, every workspace reload — drowning out everything else.
   */
  private val warnedReservedFieldKeys = mutableSetOf<String>()
  private val warnedReservedFieldKeysLock = Any()

  /** Visible for tests that need to clear the cache between scenarios in a single JVM. */
  internal fun clearClasspathTrailmapCacheForTesting() {
    synchronized(classpathTrailmapCacheLock) {
      classpathTrailmapCache.clear()
    }
    synchronized(warnedReservedFieldKeysLock) {
      warnedReservedFieldKeys.clear()
    }
  }

  /**
   * Result of [loadAllResilient]: successfully parsed manifests (each carrying the
   * requesting `trailmaps:` ref string that produced it) plus per-file failures. Tracking
   * the ref alongside the manifest — rather than as a field on the manifest itself —
   * keeps [LoadedTrailblazeTrailmapManifest] focused on representing the trailmap and avoids
   * leaking workspace-vs-classpath origin context into the manifest data class.
   */
  data class LoadResult(
    val definitions: List<WorkspaceTrailmapEntry>,
    val failures: List<Failure>,
  ) {
    /** A successfully loaded workspace trailmap paired with the ref string that requested it. */
    data class WorkspaceTrailmapEntry(
      /** The `trailmaps:` ref string from `trailblaze.yaml` that produced this manifest. */
      val requestedRef: String,
      val manifest: LoadedTrailblazeTrailmapManifest,
    )

    data class Failure(
      val requestedPath: String,
      val source: TrailmapSource,
      val cause: Throwable,
    )
  }

  fun loadAllResilient(
    trailmapRefs: List<String>,
    anchor: File,
  ): LoadResult {
    val definitions = mutableListOf<LoadResult.WorkspaceTrailmapEntry>()
    val failures = mutableListOf<LoadResult.Failure>()
    trailmapRefs.forEach { trailmapRef ->
      val trailmapFile = TrailblazeProjectConfigLoader.resolveRefFile(trailmapRef, anchor)
      val source = TrailmapSource.Filesystem(trailmapDir = trailmapFile.parentFile ?: anchor)
      try {
        definitions += LoadResult.WorkspaceTrailmapEntry(
          requestedRef = trailmapRef,
          manifest = loadFromFile(trailmapFile, source),
        )
      } catch (e: TrailblazeProjectConfigException) {
        failures += LoadResult.Failure(
          requestedPath = trailmapRef,
          source = source,
          cause = e,
        )
      }
    }
    return LoadResult(
      definitions = definitions,
      failures = failures,
    )
  }

  /**
   * Discovers trailmap manifests from the JVM classpath under `trails/config/trailmaps/<id>/trailmap.yaml`.
   *
   * The classpath layout convention mirrors the workspace one: each trailmap lives in its own
   * subdirectory containing a `trailmap.yaml`. Failed parses are logged and skipped — a single
   * malformed bundled trailmap must not break discovery of the rest. Only direct `<id>/trailmap.yaml`
   * entries are accepted; deeper nesting is ignored to keep the convention crisp.
   *
   * ## Why this bypasses the `ConfigResourceSource` abstraction
   *
   * The flat config loaders for tools/toolsets/targets go through the
   * `ConfigResourceSource` abstraction so they can be backed by either a JVM classpath
   * or an Android `AssetManager`. That abstraction's API
   * (`discoverAndLoad(directoryPath, suffix): Map<String, String>`) assumes a flat
   * directory layout — every match collapses to a `name → content` map keyed by
   * filename, which loses the subdirectory structure that trailmap discovery requires
   * (`<id>/trailmap.yaml` vs `<id>/sub/trailmap.yaml`). Adding a hierarchical variant to
   * `ConfigResourceSource` would also require an Android `AssetManager` implementation
   * we don't currently need (trailmap discovery is a JVM-host CLI concern). Calling
   * [ClasspathResourceDiscovery] directly is the smaller surface change for now.
   * If on-device trailmap discovery becomes a real requirement, the right move is to
   * extend `ConfigResourceSource` with a recursive method and migrate this call to it.
   */
  fun discoverAndLoadFromClasspath(): List<LoadedTrailblazeTrailmapManifest> {
    val classLoader = Thread.currentThread().contextClassLoader
      ?: TrailblazeTrailmapManifestLoader::class.java.classLoader
    // Hold the lock through compute so two threads with the same classloader can't both
    // walk the classpath redundantly (and so a concurrent
    // `clearClasspathTrailmapCacheForTesting` can't race a store-after-clear). The compute is
    // ~once per classloader per JVM lifetime; serializing competing waiters is the right
    // trade-off vs the redundant-work alternative.
    synchronized(classpathTrailmapCacheLock) {
      classpathTrailmapCache[classLoader]?.let { return it }
      val computed = computeClasspathTrailmaps()
      classpathTrailmapCache[classLoader] = computed
      return computed
    }
  }

  private fun computeClasspathTrailmaps(): List<LoadedTrailblazeTrailmapManifest> {
    val discovered = ClasspathResourceDiscovery.discoverAndLoadRecursive(
      directoryPath = TRAILMAPS_RESOURCE_DIR,
      suffix = "/$TRAILMAP_FILENAME",
    )
    val definitions = mutableListOf<LoadedTrailblazeTrailmapManifest>()
    // Track first-occurrence path per manifest id so duplicate-id collisions across
    // jars produce a single, actionable warning instead of silent last-wins.
    val seenById = mutableMapOf<String, String>()
    discovered.forEach { (relativeKey, content) ->
      // Accept only "<id>/trailmap.yaml" — `<id>` is the segment between TRAILMAPS_RESOURCE_DIR and
      // the trailing `/trailmap.yaml`. Deeper paths (e.g. `<id>/subdir/trailmap.yaml`) are skipped.
      val trailmapDirectoryName = relativeKey.removeSuffix("/$TRAILMAP_FILENAME")
      if (trailmapDirectoryName.isEmpty() || trailmapDirectoryName.contains('/')) return@forEach
      val resourceDir = "$TRAILMAPS_RESOURCE_DIR/$trailmapDirectoryName"
      val source = TrailmapSource.Classpath(resourceDir = resourceDir)
      val identifier = "classpath:$resourceDir/$TRAILMAP_FILENAME"
      try {
        val loaded = parseManifest(content, source, identifier = identifier)
        val priorIdentifier = seenById[loaded.manifest.id]
        if (priorIdentifier != null) {
          Console.log(
            "Warning: Trailmap id '${loaded.manifest.id}' discovered in multiple classpath " +
              "locations: $priorIdentifier (kept) and $identifier (skipped). Resolve by " +
              "ensuring only one bundled jar declares this trailmap id.",
          )
          return@forEach
        }
        seenById[loaded.manifest.id] = identifier
        definitions += loaded
      } catch (e: TrailblazeProjectConfigException) {
        Console.log(
          "Warning: Failed to load classpath trailmap '$trailmapDirectoryName': ${e.message}",
        )
      }
    }
    return definitions
  }

  /** Loads a single trailmap manifest from a filesystem file. Throws on parse failure. */
  fun load(trailmapFile: File): LoadedTrailblazeTrailmapManifest {
    val source = TrailmapSource.Filesystem(trailmapDir = trailmapFile.parentFile ?: trailmapFile.absoluteFile.parentFile)
    return loadFromFile(trailmapFile, source)
  }

  private fun loadFromFile(trailmapFile: File, source: TrailmapSource): LoadedTrailblazeTrailmapManifest {
    if (!trailmapFile.exists()) {
      throw TrailblazeProjectConfigException(
        "Trailmap manifest not found at ${trailmapFile.absolutePath}",
      )
    }
    val content = try {
      trailmapFile.readText()
    } catch (e: IOException) {
      throw TrailblazeProjectConfigException(
        "Failed to read ${trailmapFile.absolutePath}: ${e.message}",
        e,
      )
    }
    return parseManifest(content, source, identifier = trailmapFile.absolutePath)
  }

  private fun parseManifest(
    content: String,
    source: TrailmapSource,
    identifier: String,
  ): LoadedTrailblazeTrailmapManifest {
    if (content.isBlank()) {
      throw TrailblazeProjectConfigException(
        "Trailmap manifest $identifier must not be empty",
      )
    }
    errorOnLegacyInlineSystemPrompt(content, identifier)
    val manifest = try {
      TrailblazeProjectConfigLoader.yaml.decodeFromString(
        TrailblazeTrailmapManifest.serializer(),
        content,
      )
    } catch (e: SerializationException) {
      throw TrailblazeProjectConfigException(
        "Failed to parse $identifier: ${e.message}",
        e,
      )
    } catch (e: IllegalArgumentException) {
      throw TrailblazeProjectConfigException(
        "Invalid $identifier: ${e.message}",
        e,
      )
    }
    warnOnReservedFields(manifest, identifier)
    warnOnRemovedComposeFields(manifest, content, identifier)
    warnOnRemovedNavFields(manifest, content, identifier)
    warnOnRemovedMcpServersField(manifest, content, identifier)
    warnOnTrailmapScoping(manifest, identifier)
    enforceLibraryTrailmapContract(manifest, identifier)
    return LoadedTrailblazeTrailmapManifest(
      manifest = manifest,
      source = source,
    )
  }

  /**
   * Warns once per trailmap when a manifest still declares the removed `mcp_servers:` field
   * (either top-level on a legacy flat target YAML or nested under `target:` in a trailmap
   * manifest). The field was removed when the on-device MCP bundle path was retired in
   * favour of the in-process QuickJS runtime and the host-subprocess `tools:` path.
   *
   * Same string-scan + dedup pattern as [warnOnRemovedComposeFields] / [warnOnRemovedNavFields]
   * — kaml's `strictMode = false` decoder silently drops unknown keys, so without this
   * scan a stale `mcp_servers:` block would parse fine while losing every scripted tool it
   * declared with no actionable signal. Matching tolerates any leading whitespace because
   * the field used to live nested under `target:` (two-space indent) in trailmap manifests
   * while flat target YAMLs declared it at column zero.
   */
  private fun warnOnRemovedMcpServersField(
    manifest: TrailblazeTrailmapManifest,
    content: String,
    identifier: String,
  ) {
    val populated = content.lineSequence().any { line ->
      val trimmed = line.trimStart()
      trimmed.startsWith("mcp_servers:") || trimmed.startsWith("\"mcp_servers\":")
    }
    if (!populated) return
    val dedupKey = "removed-mcp-servers:${manifest.id}@$identifier"
    val isFirstWarning = synchronized(warnedReservedFieldKeysLock) {
      warnedReservedFieldKeys.add(dedupKey)
    }
    if (!isFirstWarning) return
    Console.log(
      "Warning: Trailmap '${manifest.id}' ($identifier) declares removed field `mcp_servers:` " +
        "— the declarative on-device MCP bundle path was retired. The field is silently " +
        "ignored at parse time, so any scripted tools it referenced will not register. " +
        "Migrate each entry to a `<trailmap>/tools/<name>.yaml` descriptor referenced from " +
        "the target's `tools:` block. For tools that need Node/Bun APIs, set " +
        "`runtime: subprocess` on the descriptor (or use a `.js`/`.mjs`/`.cjs` entrypoint).",
    )
  }

  /**
   * Enforces the library-trailmap contract from [TrailblazeTrailmapManifest]'s kdoc.
   *
   * Two rules pinned here:
   *
   * 1. **Library trailmaps** (no `target:` block) cannot declare `waypoints:` (bind to a
   *    target's screen state) or `mcp_servers:` (need a target session to spawn into).
   *    Declaring either in a target-less trailmap is a category error caught at parse time
   *    so the failure points at the exact trailmap and field rather than showing up later
   *    as "no target found".
   *
   * 2. **Target trailmaps** (have a `target:` block) cannot declare top-level `platforms:`.
   *    Per-platform configuration on a target trailmap belongs under `target.platforms:`;
   *    the top-level `platforms:` field is reserved for *library* trailmaps to declare their
   *    runtime-registry needs without a target wrapper. Without this guard, an author who
   *    moved declarations to the wrong place would see their `tool_sets` silently dropped
   *    from the target shape (closest-wins overlay reads `target.platforms`) while still
   *    contributing to the runtime-registry union — confusing and asymmetric.
   *
   * The library-trailmap trailhead-tools rule is enforced separately in
   * `TrailblazeProjectConfigLoader.resolveTrailmapSiblings` because it requires reading each
   * referenced tool YAML to inspect its `trailhead:` block — information not available
   * from the manifest alone.
   */
  private fun enforceLibraryTrailmapContract(
    manifest: TrailblazeTrailmapManifest,
    identifier: String,
  ) {
    if (manifest.target != null) {
      val topLevelPlatforms = manifest.platforms
      if (topLevelPlatforms != null) {
        throw TrailblazeProjectConfigException(
          "Trailmap manifest $identifier declares top-level platforms: but also has a target: " +
            "block. Target trailmaps MUST place per-platform configuration under " +
            "target.platforms:, not at the manifest top level. The top-level platforms: " +
            "field is reserved for library trailmaps (no target) to declare runtime-registry " +
            "needs. Move the declarations under target.platforms:, or remove the target: " +
            "block if this was intended to be a library trailmap. Offending platform keys: " +
            "${topLevelPlatforms.keys.joinToString(", ")}",
        )
      }
      return
    }
    if (manifest.waypoints.isNotEmpty()) {
      throw TrailblazeProjectConfigException(
        "Trailmap manifest $identifier declares waypoints: but has no target: block. " +
          "Library trailmaps (no target) cannot own waypoints — waypoints bind to a target's " +
          "screen state. Move the waypoint files into a target trailmap, or add a target: " +
          "block to this trailmap. Offending entries: ${manifest.waypoints.joinToString(", ")}",
      )
    }
  }

  /**
   * Warns once per trailmap when a manifest still uses the legacy composition slots
   * `use:` / `extend:` / `replace:` that were removed in favour of the unified
   * `dependencies:` field. kaml is `strictMode = false` so the loader silently drops
   * unknown keys; without this string-level scan, an upgraded fork's trailmap.yaml would
   * lose those fields with no signal at all.
   *
   * The check is intentionally a top-level YAML key sniff, not a structural decode —
   * the `TrailblazeTrailmapManifest` data class no longer has those fields, so structured
   * detection isn't possible. **Match is restricted to column 0** (no leading
   * whitespace) so a nested key like `target.platforms.android.use:` doesn't falsely
   * trip the warning; only true top-level keys count. Trailmap manifests in this
   * repository universally indent nested fields, so column-0 matching is reliable
   * without parsing YAML structure ourselves.
   */
  private fun warnOnRemovedComposeFields(
    manifest: TrailblazeTrailmapManifest,
    content: String,
    identifier: String,
  ) {
    val populated = REMOVED_COMPOSE_FIELDS.filter { fieldName ->
      content.lineSequence().any { line ->
        line.startsWith("$fieldName:") || line.startsWith("\"$fieldName\":")
      }
    }
    if (populated.isEmpty()) return
    val dedupKey = "removed-compose:${manifest.id}@$identifier"
    val isFirstWarning = synchronized(warnedReservedFieldKeysLock) {
      warnedReservedFieldKeys.add(dedupKey)
    }
    if (!isFirstWarning) return
    Console.log(
      "Warning: Trailmap '${manifest.id}' ($identifier) declares legacy composition " +
        "field(s) ${populated.joinToString(", ")} — these fields were removed in favour " +
        "of `dependencies:`. The fields are silently ignored at parse time; " +
        "migrate to `dependencies:` to restore composition.",
    )
  }

  /**
   * Warns once per trailmap when a manifest still declares the removed top-level `routes:`
   * field. Same string-scan + dedup pattern as [warnOnRemovedComposeFields] — see that
   * function's kdoc for why string scanning is the right tool here (kaml drops unknown
   * keys silently with `strictMode = false`, and the data class no longer carries the
   * field so structured detection isn't possible).
   *
   * Kept as a separate function from the legacy-compose warning because the migration
   * advice differs: legacy compose fields migrate to `dependencies:`, whereas `routes:`
   * is genuinely gone and the navigation logic should be expressed as shortcut tools.
   * Routing two distinct migration messages through one function would force a
   * conditional-message switch and obscure both intents.
   */
  private fun warnOnRemovedNavFields(
    manifest: TrailblazeTrailmapManifest,
    content: String,
    identifier: String,
  ) {
    val populated = REMOVED_NAV_FIELDS.filter { fieldName ->
      content.lineSequence().any { line ->
        line.startsWith("$fieldName:") || line.startsWith("\"$fieldName\":")
      }
    }
    if (populated.isEmpty()) return
    val dedupKey = "removed-nav:${manifest.id}@$identifier"
    val isFirstWarning = synchronized(warnedReservedFieldKeysLock) {
      warnedReservedFieldKeys.add(dedupKey)
    }
    if (!isFirstWarning) return
    Console.log(
      "Warning: Trailmap '${manifest.id}' ($identifier) declares removed navigation " +
        "field(s) ${populated.joinToString(", ")} — these fields were removed as part of " +
        "the shortcuts-as-tools unification. Multi-hop paths are now expressed as " +
        "shortcuts whose body invokes other shortcut tools — see " +
        "docs/trailmaps.md (Shortcut tools section) for the authoring shape. " +
        "The fields are silently ignored at parse time; remove them or migrate the " +
        "navigation logic into shortcut tools under <trailmap>/tools/shortcuts/.",
    )
  }

  /**
   * Warns once per trailmap-identifier-pair on which reserved-but-unwired manifest fields the
   * author populated. Without this, a typo in a real field name (`tools` -> `toosl`)
   * silently parses into a non-existent slot. Schema is permissive by design; the
   * loud-but-deduped warning compensates.
   *
   * Dedup is keyed on `<trailmap id>@<source identifier>` so two distinct sources advertising
   * the same id (workspace + classpath, two classpath jars) each get one warning, but a
   * given source warning doesn't re-fire on every load.
   */
  /**
   * Typed shape for the legacy-inline-system-prompt guard. The `system_prompt:` field used to
   * live on `TrailmapTargetConfig` as an inline string; it's been replaced by `system_prompt_file:`
   * (file-only authoring). Because the YAML loader runs in lenient mode and silently drops
   * unknown keys, a legacy trailmap manifest that still declares `target.system_prompt:` would lose
   * its prompt content with no diagnostic. We decode trailmap manifests into this minimal shape
   * BEFORE the typed `TrailblazeTrailmapManifest` decode so we can detect the legacy field and fail
   * the load with a clear migration message instead of silently dropping it.
   *
   * A regex on the raw text was tried first but false-positives on block-scalar content that
   * happens to contain the literal text `system_prompt:` (e.g. a `display_name:` block scalar
   * mentioning the field by name). The typed decode is bullet-proof: kaml only populates
   * `target.systemPrompt` when it's a real YAML key under `target:`.
   */
  @Serializable
  private data class LegacyInlineSystemPromptShape(
    val target: LegacyTargetShape? = null,
  ) {
    @Serializable
    data class LegacyTargetShape(
      @SerialName("system_prompt") val systemPrompt: String? = null,
    )
  }

  private fun errorOnLegacyInlineSystemPrompt(content: String, identifier: String) {
    val legacy =
      try {
        TrailblazeProjectConfigLoader.yaml.decodeFromString(
          LegacyInlineSystemPromptShape.serializer(),
          content,
        )
      } catch (e: Exception) {
        // Malformed YAML will surface from the real typed decode below. Don't pre-fail here —
        // we only want to flag legacy fields, not duplicate the parse-error reporting. Catching
        // Exception (rather than just SerializationException / IllegalArgumentException) covers
        // kaml's full surface — MissingFieldException, YamlException, and any future subtype —
        // so a malformed input can never leak through the guard with a confusing stack trace.
        // Logged so the path stays observable when developers wonder why the legacy detector
        // didn't fire on their test fixture.
        Console.log(
          "[legacy-prompt-guard] skipped pre-decode on $identifier (${e::class.simpleName}: ${e.message}) — real decode will report the underlying error"
        )
        return
      }
    if (legacy.target?.systemPrompt != null) {
      throw TrailblazeProjectConfigException(
        "Trailmap manifest $identifier declares the removed inline `system_prompt:` field. " +
          "Inline prompts are no longer supported on TrailmapTargetConfig — move the content to a " +
          "sibling file and reference it as `system_prompt_file: <relative-path>`. The trailmap " +
          "loader will inline the file content into the generated target YAML at build time. " +
          "See TrailmapTargetConfig kdoc for the file-only authoring contract.",
      )
    }
  }

  /**
   * Advisory load-time check for the 2026-05-27 trailmap-scoped tool naming decision.
   *
   * Emits a `[TrailmapScopingCheck]` warning via `Console.log` (no exception, no behavior
   * change) when either of the following holds for a non-framework trailmap:
   *
   * 1. The trailmap id doesn't match [TRAILMAP_ID_REGEX] — i.e. it contains an underscore,
   *    a dash, or otherwise deviates from the single lowerCamelCase token shape. The
   *    underscore restriction is what makes the wire form `<trailmapId>_<localName>`
   *    deterministically parseable at the first underscore.
   * 2. A tool name listed in `target.tools:` doesn't start with `<trailmapId>_`. The
   *    per-tool warning names the trailmap id, the offending tool, and the expected
   *    prefix so the author can rename without further investigation.
   *
   * The framework `trailblaze` library trailmap is exempt — its primitives keep their
   * flat names (see [FRAMEWORK_TRAILMAP_ID] kdoc for the rationale).
   *
   * Dedup uses [warnedReservedFieldKeys] keyed on `trailmap-scoping:<id>@<identifier>` so
   * repeated loads of the same manifest (workspace reload, classpath rediscovery in a
   * test) don't double-fire. Same pattern as [warnOnRemovedComposeFields] /
   * [warnOnRemovedNavFields] / [warnOnRemovedMcpServersField].
   *
   * **Advisory only in this chip.** A later chip will flip the warning into a hard
   * [TrailblazeProjectConfigException] once existing trailmaps have been brought into
   * compliance. Keep the warning surface stable until then so authors aren't surprised
   * by a sudden load-time failure.
   */
  private fun warnOnTrailmapScoping(manifest: TrailblazeTrailmapManifest, identifier: String) {
    if (manifest.id == FRAMEWORK_TRAILMAP_ID) return
    val violations = buildList {
      if (!TRAILMAP_ID_REGEX.matches(manifest.id)) {
        add(
          "trailmap id '${manifest.id}' does not match ${TRAILMAP_ID_REGEX.pattern} " +
            "(single lowerCamelCase token, no underscores or dashes). " +
            "The underscore restriction lets a wire name '<trailmapId>_<localName>' split " +
            "deterministically at the first underscore; allowing underscores in ids would " +
            "reintroduce the parsing ambiguity this convention is meant to remove.",
        )
      }
      val expectedPrefix = "${manifest.id}_"
      manifest.target?.tools.orEmpty().forEach { toolName ->
        if (!toolName.startsWith(expectedPrefix)) {
          add(
            "tool '$toolName' listed in target.tools: does not start with '$expectedPrefix' — " +
              "rename to '$expectedPrefix<localName>' so the wire-scoped naming rule applies.",
          )
        }
      }
    }
    if (violations.isEmpty()) return
    val dedupKey = "trailmap-scoping:${manifest.id}@$identifier"
    val isFirstWarning = synchronized(warnedReservedFieldKeysLock) {
      warnedReservedFieldKeys.add(dedupKey)
    }
    if (!isFirstWarning) return
    val body = violations.joinToString("\n  - ", prefix = "\n  - ")
    Console.log(
      "$SCOPING_CHECK_PREFIX Trailmap '${manifest.id}' ($identifier) does not yet follow " +
        "the trailmap-scoped tool naming convention (see docs/devlog/" +
        "2026-05-27-trailmap-scoped-tool-naming.md). Advisory only — load proceeds.$body",
    )
  }

  private fun warnOnReservedFields(manifest: TrailblazeTrailmapManifest, identifier: String) {
    val populated = buildList {
      if (manifest.trails.isNotEmpty()) add("trails")
    }
    if (populated.isEmpty()) return
    val dedupKey = "${manifest.id}@$identifier"
    val isFirstWarning = synchronized(warnedReservedFieldKeysLock) {
      warnedReservedFieldKeys.add(dedupKey)
    }
    if (!isFirstWarning) return
    Console.log(
      "Note: Trailmap '${manifest.id}' ($identifier) declares reserved field(s) " +
        "${populated.joinToString(", ")} — runtime loading for these is deferred " +
        "(see TrailblazeTrailmapManifest kdoc). " +
        "Field accepted but ignored at runtime today.",
    )
  }
}

/**
 * A successfully loaded trailmap manifest paired with the source that produced it.
 *
 * Workspace-declared trailmaps additionally carry their requesting ref string in
 * [TrailblazeTrailmapManifestLoader.LoadResult.WorkspaceTrailmapEntry]; that information is
 * deliberately kept out of this class so the manifest stays a pure description of the
 * trailmap itself, independent of how it got loaded.
 */
data class LoadedTrailblazeTrailmapManifest(
  val manifest: TrailblazeTrailmapManifest,
  val source: TrailmapSource,
)
