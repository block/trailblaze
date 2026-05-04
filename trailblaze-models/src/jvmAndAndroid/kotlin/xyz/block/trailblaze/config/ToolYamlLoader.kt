package xyz.block.trailblaze.config

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
    return parseAllConfigs(yamlContents)
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
