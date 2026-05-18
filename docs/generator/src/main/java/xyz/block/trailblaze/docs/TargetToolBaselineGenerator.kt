package xyz.block.trailblaze.docs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.config.AppTargetCompanion
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ResolvedToolSet
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolSetYamlLoader
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.toolName
import java.io.File

/**
 * Generates one markdown baseline file per app target showing a single table of all resolved
 * tools in alphabetical order, with driver-type columns and toolset membership. These baselines
 * are checked into the repo and diffed in CI so that any toolset or target YAML change that
 * affects the resolved tool surface is immediately visible.
 */
class TargetToolBaselineGenerator(
  private val generatedDir: File,
  private val companions: Map<String, AppTargetCompanion> = emptyMap(),
  /**
   * Base directory for resolving relative `mcp_servers[].script` paths so the doc can list
   * MCP-provided tool names alongside Kotlin ones. Defaults to the JVM's current working
   * directory, matching the runtime contract for bundled / legacy target YAMLs (see
   * [McpServerConfig.script]).
   */
  private val scriptBaseDir: File = File(System.getProperty("user.dir")),
) {

  fun generate() {
    val targetsDir = File(generatedDir, "targets").apply { mkdirs() }

    val customToolClasses = ToolYamlLoader.discoverAndLoadAll()
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools(customToolClasses)
    val toolSets = ToolSetYamlLoader.discoverAndLoadAll(resolver)
    val configs = AppTargetYamlLoader.discoverConfigs()
    val targets = AppTargetYamlLoader.discoverAndLoadAll(
      toolNameResolver = resolver,
      availableToolSets = toolSets,
      companions = companions,
    )

    val configById = configs.associateBy { it.id }

    for (target in targets.sortedBy { it.id }) {
      val config = configById[target.id]
      val markdown = generateTargetMarkdown(target, toolSets, config)
      File(targetsDir, "TARGET_${target.id}.md").writeText(markdown)
    }
  }

  private fun generateTargetMarkdown(
    target: TrailblazeHostAppTarget,
    allToolSets: Map<String, ResolvedToolSet>,
    config: xyz.block.trailblaze.config.AppTargetYamlConfig?,
  ): String = buildString {
    appendLine("# Target: ${target.displayName}")
    appendLine()

    val platforms = config?.platforms ?: return@buildString

    // Collect all driver types this target uses
    val allDrivers = platforms.flatMap { (platformKey, platformConfig) ->
      platformConfig.resolveDriverTypes(platformKey)
    }.toSortedSet(compareBy { it.name })

    if (allDrivers.isEmpty()) return@buildString

    // Build per-driver excluded sets
    val excluded = allDrivers.associateWith { driverType ->
      target.getExcludedToolsForDriver(driverType)
        .map { it.toolName().toolName }
        .toSet()
    }

    // Build: which drivers each toolset applies to (from platform section scope)
    val toolSetDriverScope = mutableMapOf<String, MutableSet<TrailblazeDriverType>>()
    for ((platformKey, platformConfig) in platforms) {
      val drivers = platformConfig.resolveDriverTypes(platformKey)
      platformConfig.toolSets?.forEach { tsId ->
        toolSetDriverScope.getOrPut(tsId) { mutableSetOf() }.addAll(drivers)
      }
    }

    // Build: for each tool name → which drivers it's available on + which toolsets it belongs to
    data class ToolEntry(
      val availableOn: MutableSet<TrailblazeDriverType> = mutableSetOf(),
      val excludedOn: MutableSet<TrailblazeDriverType> = mutableSetOf(),
      val toolSets: MutableSet<String> = mutableSetOf(),
    )

    val toolEntries = mutableMapOf<String, ToolEntry>()

    // From toolsets — both class-backed and YAML-defined tools are addressed by bare name.
    for ((tsId, scopedDrivers) in toolSetDriverScope) {
      val toolSet = allToolSets[tsId] ?: continue
      val namesFromToolSet =
        toolSet.resolvedToolClasses.map { it.toolName().toolName } +
          toolSet.resolvedYamlToolNames.map { it.toolName }
      for (toolName in namesFromToolSet) {
        val entry = toolEntries.getOrPut(toolName) { ToolEntry() }
        entry.toolSets.add(tsId)
        for (dt in scopedDrivers) {
          if (!toolSet.isCompatibleWith(dt)) continue
          if (toolName in (excluded[dt] ?: emptySet())) {
            entry.excludedOn.add(dt)
          } else {
            entry.availableOn.add(dt)
          }
        }
      }
    }

    // From individual tools
    for ((platformKey, platformConfig) in platforms) {
      val drivers = platformConfig.resolveDriverTypes(platformKey)
      platformConfig.tools?.forEach { toolName ->
        val entry = toolEntries.getOrPut(toolName) { ToolEntry() }
        for (dt in drivers) {
          if (toolName in (excluded[dt] ?: emptySet())) {
            entry.excludedOn.add(dt)
          } else {
            entry.availableOn.add(dt)
          }
        }
      }
    }

    // From `mcp_servers:` script files (target-root scope — applies to every driver this
    // target supports). Tool names are read statically from the script source via
    // [McpToolNameExtractor] so doc generation doesn't need bun / node_modules on the host.
    //
    // Label as `script:<filename>` (not `mcp:<filename>`) to keep the user-facing baseline
    // transport-agnostic — per the "trailblaze-tool-is-an-rpc-request" devlog, MCP is the
    // transport we ship for scripted tools today, not part of the authoring surface.
    config.mcpServers?.forEach { mcp ->
      val scriptPath = mcp.script ?: return@forEach
      val scriptFile = McpToolNameExtractor.resolveScript(scriptPath, scriptBaseDir)
      val tsLabel = "script:${scriptFile.name}"
      for (toolName in McpToolNameExtractor.extractToolNames(scriptFile)) {
        val entry = toolEntries.getOrPut(toolName) { ToolEntry() }
        entry.toolSets.add(tsLabel)
        for (dt in allDrivers) {
          if (toolName in (excluded[dt] ?: emptySet())) {
            entry.excludedOn.add(dt)
          } else {
            entry.availableOn.add(dt)
          }
        }
      }
    }

    // From target-level scripted tools (`target.tools:` — the `List<InlineScriptToolConfig>`).
    // This is the pack-authoring path: each `<pack>/tools/<name>.yaml` descriptor is
    // resolved by the pack loader into one [InlineScriptToolConfig] entry, with the tool
    // name already extracted from the descriptor's `name:` field — no need to grep the
    // script source like [McpToolNameExtractor] does for `mcp_servers:`.
    //
    // Scope: target-root by default, narrowed to the drivers matching the descriptor's
    // `supportedPlatforms:` list (carried through to runtime as
    // `_meta["trailblaze/supportedPlatforms"]`). A descriptor that declares
    // `supportedPlatforms: [android]` is restricted to Android drivers in the table;
    // omitting the field defaults to every driver the target supports.
    //
    // Label as `script:<filename>` so the source format matches the `mcp_servers:` rows —
    // either path emits "this tool came from a JS/TS module," with the filename being the
    // `.ts`/`.js` script for pack-authored tools or the bundle's `.js` for `mcp_servers:`.
    config.tools?.forEach { inlineScript ->
      val toolName = inlineScript.name
      val scriptFile = File(inlineScript.script)
      val tsLabel = "script:${scriptFile.name}"
      val supportedDrivers = driversForScriptedTool(inlineScript, allDrivers)
      val entry = toolEntries.getOrPut(toolName) { ToolEntry() }
      entry.toolSets.add(tsLabel)
      for (dt in supportedDrivers) {
        if (toolName in (excluded[dt] ?: emptySet())) {
          entry.excludedOn.add(dt)
        } else {
          entry.availableOn.add(dt)
        }
      }
    }

    // Column headers
    val driverHeaders = allDrivers.map { "${it.yamlKey} (${it.platform.name})" }

    appendLine("| Tool | Toolset(s) | ${driverHeaders.joinToString(" | ")} |")
    appendLine("|------|------------|${driverHeaders.joinToString("|") { ":---:" }}|")

    for (toolName in toolEntries.keys.sorted()) {
      val entry = toolEntries[toolName]!!
      val tsLabel = entry.toolSets.sorted().joinToString(", ").ifEmpty { "-" }
      val cells = allDrivers.map { dt ->
        when {
          dt in entry.excludedOn -> EXCLUDED
          dt in entry.availableOn -> CHECK
          else -> ""
        }
      }
      appendLine("| $toolName | $tsLabel | ${cells.joinToString(" | ")} |")
    }
    appendLine()

    appendLine(DocsGenerator.THIS_DOC_IS_GENERATED_MESSAGE)
  }

  /**
   * Drivers this scripted tool applies to, derived from `_meta["trailblaze/supportedPlatforms"]`.
   *
   * The metadata key is set by the pack loader from the descriptor's `supportedPlatforms:`
   * field. Values are platform names \u2014 `"android"`, `"ios"`, `"web"`, or `"compose"` \u2014
   * matching the lowercase enum names on [xyz.block.trailblaze.devices.TrailblazeDevicePlatform].
   *
   * When the metadata is missing or empty, scope falls back to [allDrivers] (every driver
   * the target supports) \u2014 same default the `mcp_servers:` path uses. When present, the
   * returned set is the subset of [allDrivers] whose `platform` is named in the list. If
   * the list contains a platform the target doesn't actually support, that platform is just
   * silently absent from the result \u2014 the doc accurately reflects "where this tool could
   * fire given the target's driver matrix" rather than what the descriptor wishfully claims.
   */
  private fun driversForScriptedTool(
    inlineScript: InlineScriptToolConfig,
    allDrivers: Set<TrailblazeDriverType>,
  ): Set<TrailblazeDriverType> {
    val supportedPlatforms = readSupportedPlatforms(inlineScript.meta) ?: return allDrivers
    if (supportedPlatforms.isEmpty()) return allDrivers
    return allDrivers.filterTo(linkedSetOf()) { dt ->
      dt.platform.name.lowercase() in supportedPlatforms
    }
  }

  /**
   * Parses `meta["trailblaze/supportedPlatforms"]` as a list of lowercase platform names,
   * or returns null when the key is absent / malformed. Malformed in-line entries (non-
   * string array members) get silently dropped; the doc still renders against the
   * remaining valid entries rather than failing the whole generator run on a typo in one
   * descriptor's metadata.
   */
  private fun readSupportedPlatforms(meta: JsonObject?): Set<String>? {
    val raw = meta?.get("trailblaze/supportedPlatforms") ?: return null
    val array: JsonArray = runCatching { raw.jsonArray }.getOrNull() ?: return null
    return array.mapNotNullTo(linkedSetOf()) { element ->
      runCatching { element.jsonPrimitive.content.lowercase() }.getOrNull()
    }
  }

  companion object {
    private const val CHECK = "\u2705"
    private const val EXCLUDED = "\u274C"
  }
}
