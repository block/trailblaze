package xyz.block.trailblaze.config.project

import java.io.File
import java.io.IOException
import java.util.WeakHashMap
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.llm.config.ClasspathResourceDiscovery
import xyz.block.trailblaze.util.Console

/**
 * Resilient pack-manifest loader. Each pack file is isolated so one malformed pack never aborts
 * discovery of the rest of the workspace's packs.
 *
 * Two entry points:
 * - [loadAllResilient] — workspace `packs:` entries (filesystem refs anchored at `trailblaze.yaml`)
 * - [discoverAndLoadFromClasspath] — framework-bundled packs shipped on the classpath
 *
 * Both produce [LoadedTrailblazePackManifest] objects that carry a [PackSource] so downstream
 * sibling-file reads (waypoints, toolset/tool refs, etc.) work uniformly regardless of origin.
 */
object TrailblazePackManifestLoader {

  private const val PACKS_RESOURCE_DIR = "trailblaze-config/packs"
  private const val PACK_FILENAME = "pack.yaml"

  /**
   * Manifest top-level fields removed in favour of the unified `dependencies:`
   * field. Detected by string scan in [warnOnRemovedComposeFields] so authors who
   * upgrade and still have these fields get a one-shot deprecation warning rather than
   * silent data loss (kaml `strictMode = false` drops unknown keys without complaint).
   */
  private val REMOVED_COMPOSE_FIELDS = listOf("use", "extend", "replace")

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
   * Pack discovery walks the entire classpath (file + jar protocols) and parses every
   * matching `<id>/pack.yaml`, which is too expensive to repeat on every target/tool
   * resolution. The cache key is the [ClassLoader] in effect at the time of the lookup,
   * so tests that swap [Thread.currentThread]'s context classloader (via
   * `ClasspathFixture.withClasspathRoot`) naturally miss the cache and rediscover from the
   * new classpath. [WeakHashMap] keys ensure dead classloaders can be GC'd.
   *
   * Mirrors the cache pattern in `TrailblazeSerializationInitializer.buildYamlDefinedTools`.
   */
  private val classpathPackCache = WeakHashMap<ClassLoader, List<LoadedTrailblazePackManifest>>()
  private val classpathPackCacheLock = Any()

  /**
   * Pack identifiers (`<pack id>@<source identifier>`) we've already emitted a reserved-field
   * warning for. The schema-permissive design accepts populated `use:` / `extend:` / etc.
   * fields with no runtime effect, and warns once per pack so the operator knows runtime
   * composition is deferred. Without this dedup, the warning re-emits on every load — every
   * classpath rediscovery, every workspace reload — drowning out everything else.
   */
  private val warnedReservedFieldKeys = mutableSetOf<String>()
  private val warnedReservedFieldKeysLock = Any()

  /** Visible for tests that need to clear the cache between scenarios in a single JVM. */
  internal fun clearClasspathPackCacheForTesting() {
    synchronized(classpathPackCacheLock) {
      classpathPackCache.clear()
    }
    synchronized(warnedReservedFieldKeysLock) {
      warnedReservedFieldKeys.clear()
    }
  }

  /**
   * Result of [loadAllResilient]: successfully parsed manifests (each carrying the
   * requesting `packs:` ref string that produced it) plus per-file failures. Tracking
   * the ref alongside the manifest — rather than as a field on the manifest itself —
   * keeps [LoadedTrailblazePackManifest] focused on representing the pack and avoids
   * leaking workspace-vs-classpath origin context into the manifest data class.
   */
  data class LoadResult(
    val definitions: List<WorkspacePackEntry>,
    val failures: List<Failure>,
  ) {
    /** A successfully loaded workspace pack paired with the ref string that requested it. */
    data class WorkspacePackEntry(
      /** The `packs:` ref string from `trailblaze.yaml` that produced this manifest. */
      val requestedRef: String,
      val manifest: LoadedTrailblazePackManifest,
    )

    data class Failure(
      val requestedPath: String,
      val source: PackSource,
      val cause: Throwable,
    )
  }

  fun loadAllResilient(
    packRefs: List<String>,
    anchor: File,
  ): LoadResult {
    val definitions = mutableListOf<LoadResult.WorkspacePackEntry>()
    val failures = mutableListOf<LoadResult.Failure>()
    packRefs.forEach { packRef ->
      val packFile = TrailblazeProjectConfigLoader.resolveRefFile(packRef, anchor)
      val source = PackSource.Filesystem(packDir = packFile.parentFile ?: anchor)
      try {
        definitions += LoadResult.WorkspacePackEntry(
          requestedRef = packRef,
          manifest = loadFromFile(packFile, source),
        )
      } catch (e: TrailblazeProjectConfigException) {
        failures += LoadResult.Failure(
          requestedPath = packRef,
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
   * Discovers pack manifests from the JVM classpath under `trailblaze-config/packs/<id>/pack.yaml`.
   *
   * The classpath layout convention mirrors the workspace one: each pack lives in its own
   * subdirectory containing a `pack.yaml`. Failed parses are logged and skipped — a single
   * malformed bundled pack must not break discovery of the rest. Only direct `<id>/pack.yaml`
   * entries are accepted; deeper nesting is ignored to keep the convention crisp.
   *
   * ## Why this bypasses the `ConfigResourceSource` abstraction
   *
   * The flat config loaders for tools/toolsets/targets go through the
   * `ConfigResourceSource` abstraction so they can be backed by either a JVM classpath
   * or an Android `AssetManager`. That abstraction's API
   * (`discoverAndLoad(directoryPath, suffix): Map<String, String>`) assumes a flat
   * directory layout — every match collapses to a `name → content` map keyed by
   * filename, which loses the subdirectory structure that pack discovery requires
   * (`<id>/pack.yaml` vs `<id>/sub/pack.yaml`). Adding a hierarchical variant to
   * `ConfigResourceSource` would also require an Android `AssetManager` implementation
   * we don't currently need (pack discovery is a JVM-host CLI concern). Calling
   * [ClasspathResourceDiscovery] directly is the smaller surface change for now.
   * If on-device pack discovery becomes a real requirement, the right move is to
   * extend `ConfigResourceSource` with a recursive method and migrate this call to it.
   */
  fun discoverAndLoadFromClasspath(): List<LoadedTrailblazePackManifest> {
    val classLoader = Thread.currentThread().contextClassLoader
      ?: TrailblazePackManifestLoader::class.java.classLoader
    // Hold the lock through compute so two threads with the same classloader can't both
    // walk the classpath redundantly (and so a concurrent
    // `clearClasspathPackCacheForTesting` can't race a store-after-clear). The compute is
    // ~once per classloader per JVM lifetime; serializing competing waiters is the right
    // trade-off vs the redundant-work alternative.
    synchronized(classpathPackCacheLock) {
      classpathPackCache[classLoader]?.let { return it }
      val computed = computeClasspathPacks()
      classpathPackCache[classLoader] = computed
      return computed
    }
  }

  private fun computeClasspathPacks(): List<LoadedTrailblazePackManifest> {
    val discovered = ClasspathResourceDiscovery.discoverAndLoadRecursive(
      directoryPath = PACKS_RESOURCE_DIR,
      suffix = "/$PACK_FILENAME",
    )
    val definitions = mutableListOf<LoadedTrailblazePackManifest>()
    // Track first-occurrence path per manifest id so duplicate-id collisions across
    // jars produce a single, actionable warning instead of silent last-wins.
    val seenById = mutableMapOf<String, String>()
    discovered.forEach { (relativeKey, content) ->
      // Accept only "<id>/pack.yaml" — `<id>` is the segment between PACKS_RESOURCE_DIR and
      // the trailing `/pack.yaml`. Deeper paths (e.g. `<id>/subdir/pack.yaml`) are skipped.
      val packDirectoryName = relativeKey.removeSuffix("/$PACK_FILENAME")
      if (packDirectoryName.isEmpty() || packDirectoryName.contains('/')) return@forEach
      val resourceDir = "$PACKS_RESOURCE_DIR/$packDirectoryName"
      val source = PackSource.Classpath(resourceDir = resourceDir)
      val identifier = "classpath:$resourceDir/$PACK_FILENAME"
      try {
        val loaded = parseManifest(content, source, identifier = identifier)
        val priorIdentifier = seenById[loaded.manifest.id]
        if (priorIdentifier != null) {
          Console.log(
            "Warning: Pack id '${loaded.manifest.id}' discovered in multiple classpath " +
              "locations: $priorIdentifier (kept) and $identifier (skipped). Resolve by " +
              "ensuring only one bundled jar declares this pack id.",
          )
          return@forEach
        }
        seenById[loaded.manifest.id] = identifier
        definitions += loaded
      } catch (e: TrailblazeProjectConfigException) {
        Console.log(
          "Warning: Failed to load classpath pack '$packDirectoryName': ${e.message}",
        )
      }
    }
    return definitions
  }

  /** Loads a single pack manifest from a filesystem file. Throws on parse failure. */
  fun load(packFile: File): LoadedTrailblazePackManifest {
    val source = PackSource.Filesystem(packDir = packFile.parentFile ?: packFile.absoluteFile.parentFile)
    return loadFromFile(packFile, source)
  }

  private fun loadFromFile(packFile: File, source: PackSource): LoadedTrailblazePackManifest {
    if (!packFile.exists()) {
      throw TrailblazeProjectConfigException(
        "Pack manifest not found at ${packFile.absolutePath}",
      )
    }
    val content = try {
      packFile.readText()
    } catch (e: IOException) {
      throw TrailblazeProjectConfigException(
        "Failed to read ${packFile.absolutePath}: ${e.message}",
        e,
      )
    }
    return parseManifest(content, source, identifier = packFile.absolutePath)
  }

  private fun parseManifest(
    content: String,
    source: PackSource,
    identifier: String,
  ): LoadedTrailblazePackManifest {
    if (content.isBlank()) {
      throw TrailblazeProjectConfigException(
        "Pack manifest $identifier must not be empty",
      )
    }
    errorOnLegacyInlineSystemPrompt(content, identifier)
    val manifest = try {
      TrailblazeProjectConfigLoader.yaml.decodeFromString(
        TrailblazePackManifest.serializer(),
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
    return LoadedTrailblazePackManifest(
      manifest = manifest,
      source = source,
    )
  }

  /**
   * Warns once per pack when a manifest still uses the legacy composition slots
   * `use:` / `extend:` / `replace:` that were removed in favour of the unified
   * `dependencies:` field. kaml is `strictMode = false` so the loader silently drops
   * unknown keys; without this string-level scan, an upgraded fork's pack.yaml would
   * lose those fields with no signal at all.
   *
   * The check is intentionally a top-level YAML key sniff, not a structural decode —
   * the `TrailblazePackManifest` data class no longer has those fields, so structured
   * detection isn't possible. **Match is restricted to column 0** (no leading
   * whitespace) so a nested key like `target.platforms.android.use:` doesn't falsely
   * trip the warning; only true top-level keys count. Pack manifests in this
   * repository universally indent nested fields, so column-0 matching is reliable
   * without parsing YAML structure ourselves.
   */
  private fun warnOnRemovedComposeFields(
    manifest: TrailblazePackManifest,
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
      "Warning: Pack '${manifest.id}' ($identifier) declares legacy composition " +
        "field(s) ${populated.joinToString(", ")} — these fields were removed in favour " +
        "of `dependencies:`. The fields are silently ignored at parse time; " +
        "migrate to `dependencies:` to restore composition.",
    )
  }

  /**
   * Warns once per pack when a manifest still declares the removed top-level `routes:`
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
    manifest: TrailblazePackManifest,
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
      "Warning: Pack '${manifest.id}' ($identifier) declares removed navigation " +
        "field(s) ${populated.joinToString(", ")} — these fields were removed as part of " +
        "the shortcuts-as-tools unification. Multi-hop paths are now expressed as " +
        "shortcuts whose body invokes other shortcut tools — see " +
        "docs/packs.md (Shortcut tools section) for the authoring shape. " +
        "The fields are silently ignored at parse time; remove them or migrate the " +
        "navigation logic into shortcut tools under <pack>/tools/shortcuts/.",
    )
  }

  /**
   * Warns once per pack-identifier-pair on which reserved-but-unwired manifest fields the
   * author populated. Without this, a typo in a real field name (`tools` -> `toosl`)
   * silently parses into a non-existent slot. Schema is permissive by design; the
   * loud-but-deduped warning compensates.
   *
   * Dedup is keyed on `<pack id>@<source identifier>` so two distinct sources advertising
   * the same id (workspace + classpath, two classpath jars) each get one warning, but a
   * given source warning doesn't re-fire on every load.
   */
  /**
   * The `system_prompt:` field used to live on `PackTargetConfig` as an inline string. It's been
   * replaced by `system_prompt_file:` (file-only authoring). Because the YAML loader runs in
   * lenient mode and silently drops unknown keys, a legacy pack manifest that still declares
   * `system_prompt:` would lose its prompt content with no diagnostic. This pre-decode regex
   * catches that case and fails the load with a clear migration message before the silent-drop
   * can happen. Matches the field name as a YAML key (start-of-line, optional indent, the literal
   * `system_prompt:` followed by a value) — `system_prompt_file:` is not matched because the
   * trailing colon must come directly after `system_prompt`.
   */
  private val LEGACY_INLINE_SYSTEM_PROMPT_PATTERN =
    Regex("""^\s*system_prompt:\s*\S""", RegexOption.MULTILINE)

  private fun errorOnLegacyInlineSystemPrompt(content: String, identifier: String) {
    if (LEGACY_INLINE_SYSTEM_PROMPT_PATTERN.containsMatchIn(content)) {
      throw TrailblazeProjectConfigException(
        "Pack manifest $identifier declares the removed inline `system_prompt:` field. " +
          "Inline prompts are no longer supported on PackTargetConfig — move the content to a " +
          "sibling file and reference it as `system_prompt_file: <relative-path>`. The pack " +
          "loader will inline the file content into the generated target YAML at build time. " +
          "See PackTargetConfig kdoc for the file-only authoring contract.",
      )
    }
  }

  private fun warnOnReservedFields(manifest: TrailblazePackManifest, identifier: String) {
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
      "Note: Pack '${manifest.id}' ($identifier) declares reserved field(s) " +
        "${populated.joinToString(", ")} — runtime loading for these is deferred " +
        "(see TrailblazePackManifest kdoc). " +
        "Field accepted but ignored at runtime today.",
    )
  }
}

/**
 * A successfully loaded pack manifest paired with the source that produced it.
 *
 * Workspace-declared packs additionally carry their requesting ref string in
 * [TrailblazePackManifestLoader.LoadResult.WorkspacePackEntry]; that information is
 * deliberately kept out of this class so the manifest stays a pure description of the
 * pack itself, independent of how it got loaded.
 */
data class LoadedTrailblazePackManifest(
  val manifest: TrailblazePackManifest,
  val source: PackSource,
)
