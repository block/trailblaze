package xyz.block.trailblaze.docs

import xyz.block.trailblaze.config.AppTargetCompanion
import xyz.block.trailblaze.config.AppTargetYamlLoader
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

  companion object {
    private const val CHECK = "\u2705"
    private const val EXCLUDED = "\u274C"
  }
}
