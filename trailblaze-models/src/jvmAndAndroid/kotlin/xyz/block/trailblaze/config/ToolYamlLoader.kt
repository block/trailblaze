package xyz.block.trailblaze.config

import xyz.block.trailblaze.config.project.TrailblazePackManifest
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * Loads per-tool YAML files from `trailblaze-config/tools/` and resolves each tool's class via
 * reflection (for class-backed tools) or returns the parsed [ToolYamlConfig] (for YAML-defined
 * `tools:` mode tools).
 *
 * Files in `tools/` use one of three suffixes that signal the tool's operational class:
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
    // Walk for `.yaml` (the resource-source contract strips this exact suffix from the
    // returned map keys). Filenames like `eraseText.tool.yaml` become map keys
    // `eraseText.tool` — the trailing word identifies the operational class.
    val yamlContents = resourceSource.discoverAndLoad(
      directoryPath = TrailblazeConfigPaths.TOOLS_DIR,
      suffix = ".yaml",
    )
    val packBundled = discoverPackBundledToolContents(resourceSource)
    return parseAllConfigs(yamlContents + packBundled)
  }

  /**
   * Discovers tool YAMLs bundled inside library / target packs under three sibling
   * directories, one per operational class:
   *
   * - `trailblaze-config/packs/<id>/tools/[<subdir>/...]<name>.tool.yaml`
   * - `trailblaze-config/packs/<id>/shortcuts/[<subdir>/...]<name>.shortcut.yaml`
   * - `trailblaze-config/packs/<id>/trailheads/[<subdir>/...]<name>.trailhead.yaml`
   *
   * Each suffix is permitted in exactly one directory. A `.shortcut.yaml` dropped under
   * `tools/` (or vice versa) is dropped by discovery and logged at warning level so the
   * author sees a signal — misfiled YAMLs never reach [parseAllConfigs], so the in-parser
   * suffix-content contract can't fire on them.
   *
   * Subdirectories under each top-level dir are allowed at any depth so authors can
   * organize a pack's surface by platform, sub-flow, or any other grouping that fits
   * the pack (e.g. `shortcuts/web/`, `shortcuts/android/checkout/`). This mirrors the
   * existing `<pack>/waypoints/<...>/<name>.waypoint.yaml` layout. The constraint is
   * **structural, not organizational**: a YAML must live somewhere under the matching
   * top-level directory for its operational class — a stray YAML elsewhere in the pack
   * doesn't accidentally register.
   *
   * Why this lives next to the flat-`tools/`-dir scan: pack-bundled tools must surface
   * in the same global tool registry that the flat scan populates so toolset YAMLs and
   * trail YAMLs can reference them by bare id, regardless of which pack ships them.
   * Without this step, moving a tool YAML from `trailblaze-config/tools/` into a pack's
   * subdirectory would silently drop it from the resolver — toolsets would log
   * "unknown tool" warnings and trails would fail to decode.
   *
   * Per-target scripted-tool descriptors (`PackScriptedToolFile`) live under `tools/`
   * but use a plain `.yaml` extension and are deliberately excluded — they flow
   * through the per-target `target.tools:` resolution path instead of the global
   * registry.
   *
   * Map keys are the **full pack-relative path** with `.yaml` stripped (e.g.
   * `<pack-id>/shortcuts/<name>.shortcut`). Keying by basename only would silently
   * collapse two packs that ship a same-named file (e.g. `packs/a/shortcuts/login.shortcut.yaml`
   * vs `packs/b/shortcuts/login.shortcut.yaml`) into one entry before the YAML is even
   * parsed, losing one tool from the registry without any duplicate-id warning. Keying
   * by full path keeps both files in the discovery map and lets [warnOnDuplicateIds]
   * catch the collision at the *id* level instead.
   *
   * **Library-pack trailhead guard.** A pack with no `target:` block cannot legitimately
   * ship a `*.trailhead.yaml` file (a trailhead bootstraps to a known waypoint, which
   * only makes sense within a target). The manifest-side rule lives in
   * `TrailblazeProjectConfigLoader.resolvePackSiblings` and only fires for tools that
   * are referenced by `pack.yaml`'s `tools:` list. To prevent a library pack from
   * silently registering a trailhead tool *just by dropping it on disk*, this discovery
   * pass also classifies each pack and skips `*.trailhead.yaml` files inside library
   * packs (with a loud warning naming the offending file).
   *
   * Routes through [ConfigResourceSource.discoverAndLoadRecursive] so the same logic works
   * on JVM (classpath URL scan) and on Android (AssetManager walk). The pack-bundled hook
   * was JVM-only originally — on-device tests now resolve pack-bundled tools the same way.
   */
  private fun discoverPackBundledToolContents(
    resourceSource: ConfigResourceSource,
  ): Map<String, String> {
    val libraryPackIds = discoverLibraryPackIds(resourceSource)
    val result = mutableMapOf<String, String>()
    TrailblazeConfigPaths.PACK_TOOL_LAYOUT.forEach { (expectedDir, suffix) ->
      val matches = resourceSource.discoverAndLoadRecursive(
        directoryPath = TrailblazeConfigPaths.PACKS_DIR,
        suffix = suffix,
      )
      matches.forEach { (relPath, content) ->
        // Layout: `<pack-id>/<expectedDir>/[<subdir>/...]<name><suffix>` (the
        // resource-source contract strips the leading `trailblaze-config/packs/`
        // prefix from `relPath`). Require segments[1] == expectedDir so each
        // operational class lives under its own top-level directory; subdirs at
        // any depth below that are permitted.
        val segments = relPath.split('/')
        if (segments.size < 3) return@forEach
        if (segments[1] != expectedDir) {
          // A YAML with a recognized suffix landed under the wrong sibling directory
          // (e.g. `<pack>/tools/foo.shortcut.yaml`). The author almost certainly meant
          // it to register, so log loudly rather than dropping silently — the sibling
          // library-pack-trailhead branch below uses the same shape for the same
          // reason. The suffix-content contract in `parseAllConfigs` only fires for
          // YAMLs that survive discovery, so misfiled YAMLs would otherwise produce
          // no signal at all.
          Console.log(
            "Warning: Skipping '$relPath' — file with suffix '$suffix' must live under " +
              "'<pack>/$expectedDir/' but was found under '<pack>/${segments[1]}/'. " +
              "Move the file or rename its suffix.",
          )
          return@forEach
        }
        val packId = segments[0]
        if (suffix == ".trailhead.yaml" && packId in libraryPackIds) {
          Console.log(
            "Warning: Skipping trailhead tool '$relPath' — pack '$packId' is a library " +
              "pack (no target: block) and library packs cannot ship trailhead tools. " +
              "Trailheads bootstrap to a known waypoint, which only makes sense within a " +
              "target pack. Move the tool into a target pack or add a target: block to " +
              "the owning pack.",
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
   * Returns the ids of classpath-discovered packs that have no `target:` block (library
   * packs). Used by [discoverPackBundledToolContents] to skip `*.trailhead.yaml` files
   * inside library packs at discovery time. A best-effort decode: a malformed
   * `pack.yaml` is silently skipped here (it'll fail with a better message in
   * `TrailblazePackManifestLoader` during the host-side path).
   */
  private fun discoverLibraryPackIds(resourceSource: ConfigResourceSource): Set<String> {
    val packManifests = resourceSource.discoverAndLoadRecursive(
      directoryPath = TrailblazeConfigPaths.PACKS_DIR,
      suffix = "/pack.yaml",
    )
    val ids = mutableSetOf<String>()
    packManifests.forEach { (relPath, content) ->
      // Only direct `<pack-id>/pack.yaml` entries count — same convention as
      // TrailblazePackManifestLoader.discoverAndLoadFromClasspath.
      val packDirectoryName = relPath.removeSuffix("/pack.yaml")
      if (packDirectoryName.isEmpty() || packDirectoryName.contains('/')) return@forEach
      val manifest = try {
        TrailblazeConfigYaml.instance.decodeFromString(
          TrailblazePackManifest.serializer(),
          content,
        )
      } catch (_: Exception) {
        return@forEach
      }
      if (manifest.target == null) ids.add(packDirectoryName)
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
          "silently dropped from the registry. Rename the duplicates so each tool has a " +
          "unique id.",
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
