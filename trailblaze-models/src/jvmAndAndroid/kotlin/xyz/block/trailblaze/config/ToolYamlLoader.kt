package xyz.block.trailblaze.config

import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * Loads per-tool YAML files from `trails/config/trailmaps/<id>/tools/` and resolves each
 * tool's class via reflection (for class-backed tools) or returns the parsed [ToolYamlConfig]
 * (for YAML-defined `tools:` mode tools).
 *
 * Files in a trailmap's `tools/` directory use one of three suffixes that signal the tool's
 * operational class:
 *
 * - `*.tool.yaml` — regular tool. Must NOT declare a `shortcut:` or `trailhead:` block.
 * - `*.shortcut.yaml` — shortcut tool. Must declare a `shortcut:` block; must not declare
 *   `trailhead:`.
 * - `*.trailhead.yaml` — trailhead tool. Must declare a `trailhead:` block; must not
 *   declare `shortcut:`.
 *
 * The loader enforces the suffix-content contract — files with one of these suffixes whose
 * content disagrees produce a load-time error (caught and logged via the standard
 * lenient-load path). Files whose name doesn't end with any of the three recognized
 * suffixes are skipped with a warning.
 *
 * The data class itself ([ToolYamlConfig]) stays loader-agnostic — it has no idea which
 * file produced it, only what the parsed content says. This keeps the validation rules
 * inside the data class composable with non-file-backed test fixtures.
 */
object ToolYamlLoader {

  /**
   * Discovers and loads class-backed tool YAMLs. YAMLs in tools-mode (no `class:` field) are
   * silently skipped — use [discoverYamlDefinedTools] to retrieve those.
   *
   * @param resourceSource where to discover YAML files; defaults to [platformConfigResourceSource]
   *   (classpath scan on JVM, AssetManager on Android).
   */
  fun discoverAndLoadAll(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Map<ToolName, KClass<out TrailblazeTool>> =
    resolveClassBackedConfigsLeniently(discoverAllConfigs(resourceSource))

  /**
   * Loads tool definitions from pre-read YAML content strings. Returns a map of [ToolName] to
   * resolved [KClass] — tools-mode YAMLs are skipped.
   */
  fun loadFromYamlContents(
    yamlContents: Map<String, String>,
  ): Map<ToolName, KClass<out TrailblazeTool>> =
    resolveClassBackedConfigsLeniently(parseAllConfigs(yamlContents))

  /**
   * Loads tool definitions from already-parsed configs. Class-backed tools are returned;
   * YAML-defined (`tools:` mode) entries are skipped.
   */
  fun loadFromConfigs(
    configs: List<ToolYamlConfig>,
  ): Map<ToolName, KClass<out TrailblazeTool>> =
    resolveClassBackedConfigsLeniently(validateConfigsLeniently(configs))

  private fun validateConfigsLeniently(
    configs: List<ToolYamlConfig>,
  ): List<ToolYamlConfig> = buildList {
    configs.forEach { config ->
      try {
        config.validate()
        add(config)
      } catch (e: Exception) {
        Console.log(
          "Warning: Skipping invalid tool config '${config.id}': " +
            "${e::class.simpleName}: ${e.message}",
        )
      }
    }
  }

  /**
   * Resolves class-backed configs one at a time, isolating each `Class.forName` failure so a
   * single bad tool YAML (stale FQCN, module dropped from classpath) doesn't abort discovery of
   * every other tool. Mirrors the per-file error containment already provided by
   * `loadAllYamlWithErrorHandling` during parse.
   */
  private fun resolveClassBackedConfigsLeniently(
    configs: List<ToolYamlConfig>,
  ): Map<ToolName, KClass<out TrailblazeTool>> {
    val out = mutableMapOf<ToolName, KClass<out TrailblazeTool>>()
    configs
      .filter { it.mode == ToolYamlConfig.Mode.CLASS }
      .forEach { config ->
        try {
          out[ToolName(config.id)] = resolveToolClass(config.toolClass!!)
        } catch (e: Exception) {
          Console.log(
            "Warning: Failed to resolve tool class for '${config.id}' " +
              "(class=${config.toolClass}): ${e::class.simpleName}: ${e.message}",
          )
        }
      }
    return out
  }

  /**
   * Discovers only the YAML-defined (`tools:` mode) tool configs on the classpath. Class-backed
   * YAMLs are skipped. These configs are expanded into executable tools at runtime by
   * `YamlDefinedTrailblazeTool` (in `trailblaze-common`).
   */
  fun discoverYamlDefinedTools(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Map<ToolName, ToolYamlConfig> =
    discoverAllConfigs(resourceSource)
      .filter { it.mode == ToolYamlConfig.Mode.TOOLS }
      .associate { ToolName(it.id) to it }

  /**
   * Discovers all `*.shortcut.yaml` and `*.trailhead.yaml` configs — i.e. every config whose
   * parsed content carries either a [ToolYamlConfig.shortcut] or [ToolYamlConfig.trailhead]
   * metadata block. **Both `Mode.CLASS` and `Mode.TOOLS` bodies are returned** because the
   * navigation-graph view cares about the edge metadata, not whether the body is implemented
   * as Kotlin or as a YAML composition. ([discoverYamlDefinedTools] filters out class-backed
   * configs and so silently drops class-bodied trailheads / shortcuts; this method is the
   * graph view's loader of record.)
   *
   * Returned [Map] keys are [ToolName]s; the same id never appears twice because the parser's
   * duplicate-id detection runs in [discoverAllConfigs] / [parseAllConfigs] before we reach
   * here. The order of entries is the discovery order — callers that want stable rendering
   * (e.g. the Map view) should sort downstream.
   */
  fun discoverShortcutsAndTrailheads(
    resourceSource: ConfigResourceSource = platformConfigResourceSource(),
  ): Map<ToolName, ToolYamlConfig> =
    discoverAllConfigs(resourceSource)
      .filter { it.shortcut != null || it.trailhead != null }
      .associate { ToolName(it.id) to it }

  private fun discoverAllConfigs(
    resourceSource: ConfigResourceSource,
  ): List<ToolYamlConfig> {
    // Single walk: tools live at `trails/config/trailmaps/<id>/tools/<name>.tool.yaml`
    // (and shortcuts/trailheads at their sibling directories). The composite source has
    // already collapsed the workspace + classpath layers at the same relPath before we
    // get here — workspace-authored tools at
    // `<workspace>/trails/config/trailmaps/<id>/tools/<name>.tool.yaml` win over
    // classpath-bundled tools at the same relPath.
    return parseAllConfigs(discoverTrailmapBundledToolContents(resourceSource))
  }

  /**
   * Discovers tool YAMLs bundled inside library / target trailmaps under three sibling
   * directories, one per operational class:
   *
   * - `trails/config/trailmaps/<id>/tools/[<subdir>/...]<name>.tool.yaml`
   * - `trails/config/trailmaps/<id>/shortcuts/[<subdir>/...]<name>.shortcut.yaml`
   * - `trails/config/trailmaps/<id>/trailheads/[<subdir>/...]<name>.trailhead.yaml`
   *
   * Each suffix is permitted in exactly one directory. A `.shortcut.yaml` dropped under
   * `tools/` (or vice versa) is dropped by discovery and logged at warning level so the
   * author sees a signal — misfiled YAMLs never reach [parseAllConfigs], so the in-parser
   * suffix-content contract can't fire on them.
   *
   * Subdirectories under each top-level dir are allowed at any depth so authors can
   * organize a trailmap's surface by platform, sub-flow, or any other grouping that fits
   * the trailmap (e.g. `shortcuts/web/`, `shortcuts/android/checkout/`). This mirrors the
   * existing `<trailmap>/waypoints/<...>/<name>.waypoint.yaml` layout. The constraint is
   * **structural, not organizational**: a YAML must live somewhere under the matching
   * top-level directory for its operational class — a stray YAML elsewhere in the trailmap
   * doesn't accidentally register.
   *
   * This is the only discovery path for class-backed and YAML-defined tools: every tool
   * YAML lives under its owning trailmap's `tools/` directory, and toolset YAMLs / trail
   * YAMLs reference them by bare id regardless of which trailmap ships them. The
   * trailmap-scoped layout is also what users author at
   * `<workspace>/trails/config/trailmaps/<id>/tools/<name>.tool.yaml`; the classpath and
   * workspace layers merge at the same relPath via [CompositeConfigResourceSource]
   * (workspace wins on collision) before discovery sees them.
   *
   * Per-target scripted-tool descriptors (`TrailmapScriptedToolFile`) live under `tools/`
   * but use a plain `.yaml` extension and are deliberately excluded — they flow
   * through the per-target `target.tools:` resolution path instead of the global
   * registry.
   *
   * Map keys are the **full trailmap-relative path** with `.yaml` stripped (e.g.
   * `<trailmap-id>/shortcuts/<name>.shortcut`). Keying by basename only would silently
   * collapse two trailmaps that ship a same-named file (e.g. `trailmaps/a/shortcuts/login.shortcut.yaml`
   * vs `trailmaps/b/shortcuts/login.shortcut.yaml`) into one entry before the YAML is even
   * parsed, losing one tool from the registry without any duplicate-id warning. Keying
   * by full path keeps both files in the discovery map and lets [warnOnDuplicateIds]
   * catch the collision at the *id* level instead.
   *
   * **Library-trailmap trailhead guard.** A trailmap with no `target:` block cannot legitimately
   * ship a `*.trailhead.yaml` file (a trailhead bootstraps to a known waypoint, which
   * only makes sense within a target). The manifest-side rule lives in
   * `TrailblazeProjectConfigLoader.resolveTrailmapSiblings` and only fires for tools that
   * are referenced by `trailmap.yaml`'s `tools:` list. To prevent a library trailmap from
   * silently registering a trailhead tool *just by dropping it on disk*, this discovery
   * pass also classifies each trailmap and skips `*.trailhead.yaml` files inside library
   * trailmaps (with a loud warning naming the offending file).
   *
   * Routes through [ConfigResourceSource.discoverAndLoadRecursive] so the same logic works
   * on JVM (classpath URL scan) and on Android (AssetManager walk). The trailmap-bundled hook
   * was JVM-only originally — on-device tests now resolve trailmap-bundled tools the same way.
   *
   * **Multi-module contribution.** A single trailmap id can have artifacts contributed from
   * more than one Gradle module — e.g. the `trailblaze` trailmap's `trailmap.yaml` ships in
   * `:trailblaze-models` while its `tools/` directory ships in `:trailblaze-common`. Both
   * land at the same classpath path (`trails/config/trailmaps/trailblaze/`) and the
   * recursive walk above merges them transparently. Safe-to-co-contribute artifact types
   * are the operational ones in this loader (`tools/`, `shortcuts/`, `trailheads/`) and
   * toolsets (handled by `ToolSetYamlLoader`). The `trailmap.yaml` manifest must stay
   * singular per id — only one owning module declares it. Two modules attempting to ship
   * `trailmaps/<id>/trailmap.yaml` would collapse to whichever jar resolves first on the
   * classpath; the resolution is undefined order.
   */
  private fun discoverTrailmapBundledToolContents(
    resourceSource: ConfigResourceSource,
  ): Map<String, String> {
    val libraryTrailmapIds = discoverLibraryTrailmapIds(resourceSource)
    val result = mutableMapOf<String, String>()
    TrailblazeConfigPaths.TRAILMAP_TOOL_LAYOUT.forEach { (expectedDir, suffix) ->
      val matches = resourceSource.discoverAndLoadRecursive(
        directoryPath = TrailblazeConfigPaths.TRAILMAPS_DIR,
        suffix = suffix,
      )
      matches.forEach { (relPath, content) ->
        // Layout: `<trailmap-id>/<expectedDir>/[<subdir>/...]<name><suffix>` (the
        // resource-source contract strips the leading `trails/config/trailmaps/`
        // prefix from `relPath`). Require segments[1] == expectedDir so each
        // operational class lives under its own top-level directory; subdirs at
        // any depth below that are permitted.
        val segments = relPath.split('/')
        if (segments.size < 3) return@forEach
        if (segments[1] != expectedDir) {
          // A YAML with a recognized suffix landed under the wrong sibling directory
          // (e.g. `<trailmap>/tools/foo.shortcut.yaml`). The author almost certainly meant
          // it to register, so log loudly rather than dropping silently — the sibling
          // library-trailmap-trailhead branch below uses the same shape for the same
          // reason. The suffix-content contract in `parseAllConfigs` only fires for
          // YAMLs that survive discovery, so misfiled YAMLs would otherwise produce
          // no signal at all.
          Console.log(
            "Warning: Skipping '$relPath' — file with suffix '$suffix' must live under " +
              "'<trailmap>/$expectedDir/' but was found under '<trailmap>/${segments[1]}/'. " +
              "Move the file or rename its suffix.",
          )
          return@forEach
        }
        val trailmapId = segments[0]
        if (suffix == ".trailhead.yaml" && trailmapId in libraryTrailmapIds) {
          Console.log(
            "Warning: Skipping trailhead tool '$relPath' — trailmap '$trailmapId' is a library " +
              "trailmap (no target: block) and library trailmaps cannot ship trailhead tools. " +
              "Trailheads bootstrap to a known waypoint, which only makes sense within a " +
              "target trailmap. Move the tool into a target trailmap or add a target: block to " +
              "the owning trailmap.",
          )
          return@forEach
        }
        val key = relPath.removeSuffix(".yaml")
        result[key] = content
      }
    }
    return result
  }

  /**
   * Returns the ids of classpath-discovered trailmaps that have no `target:` block (library
   * trailmaps). Used by [discoverTrailmapBundledToolContents] to skip `*.trailhead.yaml` files
   * inside library trailmaps at discovery time. A best-effort decode: a malformed
   * `trailmap.yaml` is silently skipped here (it'll fail with a better message in
   * `TrailblazeTrailmapManifestLoader` during the host-side path).
   */
  private fun discoverLibraryTrailmapIds(resourceSource: ConfigResourceSource): Set<String> {
    val trailmapManifests = resourceSource.discoverAndLoadRecursive(
      directoryPath = TrailblazeConfigPaths.TRAILMAPS_DIR,
      suffix = "/trailmap.yaml",
    )
    val ids = mutableSetOf<String>()
    trailmapManifests.forEach { (relPath, content) ->
      // Only direct `<trailmap-id>/trailmap.yaml` entries count — same convention as
      // TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath.
      val trailmapDirectoryName = relPath.removeSuffix("/trailmap.yaml")
      if (trailmapDirectoryName.isEmpty() || trailmapDirectoryName.contains('/')) return@forEach
      val manifest = try {
        TrailblazeConfigYaml.instance.decodeFromString(
          TrailblazeTrailmapManifest.serializer(),
          content,
        )
      } catch (_: Exception) {
        return@forEach
      }
      if (manifest.target == null) ids.add(trailmapDirectoryName)
    }
    return ids
  }

  internal fun parseAllConfigs(yamlContents: Map<String, String>): List<ToolYamlConfig> {
    val parsed = loadAllYamlWithErrorHandling(yamlContents, "Tool") { name, content ->
      val expectedKind = expectedKindOrNull(name)
        ?: error(
          "Tool file '$name.yaml' does not have a recognized type suffix. Expected one of: " +
            ".tool.yaml, .shortcut.yaml, .trailhead.yaml",
        )
      TrailblazeConfigYaml.instance
        .decodeFromString(ToolYamlConfig.serializer(), content)
        .also { config ->
          config.validate()
          enforceSuffixContentMatch(expectedKind, config, name)
        }
    }
    warnOnDuplicateIds(parsed)
    return parsed
  }

  /**
   * Logs a warning for every tool id that appears in more than one config. Downstream
   * consumers ([discoverYamlDefinedTools], [discoverAndLoadAll]) collapse same-id entries
   * via `.associate { ToolName(it.id) to it }` — the second occurrence silently
   * overwrites the first. Without this warning, an author who creates two files with
   * the same `id:` (e.g. a `.tool.yaml` and a `.shortcut.yaml` they forgot to rename
   * the id on) gets no signal that one of the two is being dropped.
   *
   * Idempotent at the data-class level: validation already ensures every config is
   * structurally well-formed by the time we get here. The duplicate-id check is a
   * cross-config invariant, not a per-config one, which is why it lives at the
   * collection-level rather than inside [ToolYamlConfig.validate].
   */
  private fun warnOnDuplicateIds(configs: List<ToolYamlConfig>) {
    val byId = configs.groupBy { it.id }
    byId.filterValues { it.size > 1 }.forEach { (id, dupes) ->
      Console.log(
        "Warning: Tool id '$id' is declared by ${dupes.size} different tool YAML files. " +
          "Downstream consumers keep only one (last-wins by file order); the others are " +
          "silently dropped from the registry. If this is an accidental collision, rename " +
          "the duplicates so each tool has a unique id; if it's an intentional workspace " +
          "override of a framework tool, this warning is informational only — the workspace " +
          "body wins.",
      )
    }
  }

  /**
   * Identifies the operational class of a tool YAML by the trailing word of its
   * (already-`.yaml`-stripped) basename. Returns `null` for files that don't match any
   * recognized suffix — the caller treats that as an error so unrecognized files fail
   * loudly rather than parsing as ambiguous defaults.
   */
  private fun expectedKindOrNull(strippedName: String): ToolFileKind? = when {
    strippedName.endsWith(".tool") -> ToolFileKind.TOOL
    strippedName.endsWith(".shortcut") -> ToolFileKind.SHORTCUT
    strippedName.endsWith(".trailhead") -> ToolFileKind.TRAILHEAD
    else -> null
  }

  /**
   * Validates that the content of a tool YAML matches the operational class declared by
   * its filename suffix. Catches mismatches like "a `.tool.yaml` file with a `shortcut:`
   * block" — which the data class's own [ToolYamlConfig.validate] can't detect because
   * it doesn't know what filename it came from.
   */
  private fun enforceSuffixContentMatch(
    kind: ToolFileKind,
    config: ToolYamlConfig,
    name: String,
  ) {
    when (kind) {
      ToolFileKind.TOOL -> {
        require(config.shortcut == null) {
          "Tool file '$name.yaml' uses '.tool.yaml' suffix but declares a 'shortcut:' block. " +
            "Rename the file to '*.shortcut.yaml' or remove the block."
        }
        require(config.trailhead == null) {
          "Tool file '$name.yaml' uses '.tool.yaml' suffix but declares a 'trailhead:' block. " +
            "Rename the file to '*.trailhead.yaml' or remove the block."
        }
      }
      ToolFileKind.SHORTCUT -> {
        requireNotNull(config.shortcut) {
          "Tool file '$name.yaml' uses '.shortcut.yaml' suffix but is missing a 'shortcut:' " +
            "block. Add the block, or rename the file to '*.tool.yaml' if it isn't a shortcut."
        }
        // Mutual-exclusion is enforced by ToolYamlConfig.validate() — no need to re-check
        // for trailhead presence here.
      }
      ToolFileKind.TRAILHEAD -> {
        requireNotNull(config.trailhead) {
          "Tool file '$name.yaml' uses '.trailhead.yaml' suffix but is missing a 'trailhead:' " +
            "block. Add the block, or rename the file to '*.tool.yaml' if it isn't a trailhead."
        }
      }
    }
  }

  private enum class ToolFileKind { TOOL, SHORTCUT, TRAILHEAD }

  @Suppress("UNCHECKED_CAST")
  private fun resolveToolClass(fqcn: String): KClass<out TrailblazeTool> {
    val clazz = Class.forName(fqcn)
    require(TrailblazeTool::class.java.isAssignableFrom(clazz)) {
      "$fqcn is not a TrailblazeTool"
    }
    return clazz.kotlin as KClass<out TrailblazeTool>
  }
}
