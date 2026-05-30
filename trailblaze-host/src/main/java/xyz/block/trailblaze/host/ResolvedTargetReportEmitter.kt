package xyz.block.trailblaze.host

import java.io.File
import kotlin.reflect.KClass
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.DriverTypeKey
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.ResolvedTrailmap
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.ResolvedTargetIdempotentWrite
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import xyz.block.trailblaze.util.Console

/**
 * Per-target Markdown report emitter — renders the resolved agent toolbox + resolution
 * trace into a browsable `<workspace>/trails/config/dist/targets/<id>.report.md` next to
 * the resolved YAML, so authors can answer "what is the agent told about for this target
 * and where did each piece come from?" without grepping Kotlin source.
 *
 * One report per app target. Sections mirror the three-layer trailmap-typing model:
 *
 *   1. **Toolset closure (runtime registry).** Transitive union of every trailmap-in-closure's
 *      `platforms.<p>.tool_sets:` declarations, expanded through the toolset catalog into
 *      concrete tool names. Attributed to source toolset + the trailmap(s) that contributed
 *      that toolset.
 *   2. **Agent toolbox (what the LLM sees).** The subset advertised to the LLM at session
 *      start — rendered per (platform, driver) because the live inner-agent provider binds
 *      the toolbox to whichever driver the session is using.
 *   3. **Resolution trace.** Per-platform, per-field attribution: which `trailmap.yaml` field
 *      came from this trailmap's own declaration vs inherited from a dep via closest-wins.
 *   4. **Stats.** Tool counts per layer.
 *
 * ## Sidecar tool-detail files
 *
 * For every tool surfaced in the index report, the emitter also writes a per-tool Markdown
 * detail page to `<outputDir>/<targetId>/tools/<toolName>.md`. Each sidecar carries
 * description, source (class FQN / scripted path / YAML tool id), input schema (required +
 * optional parameters with name / type / description), and the literal current return shape
 * (opaque string). The index report links to each sidecar so the tables stay scannable while
 * readers can drill into any tool. Each sidecar carries
 * [ResolvedTargetToolDetailRenderer.GENERATED_BANNER] so orphan cleanup can prune emitter-
 * owned files without ever touching hand-authored siblings.
 *
 * Idempotent: the rendered text is content-hash compared against the existing file before
 * writing. Unchanged generations don't churn mtimes, so the report doesn't keep showing up
 * in `git status` after running `trailblaze compile` repeatedly.
 */
object ResolvedTargetReportEmitter {

  fun emit(
    resolvedTargets: List<AppTargetYamlConfig>,
    resolvedTrailmaps: List<ResolvedTrailmap>,
    outputDir: File,
  ): List<File> {
    if (resolvedTargets.isEmpty()) {
      deleteOrphanReports(outputDir, keepNames = emptySet())
      deleteOrphanSidecarDirs(outputDir, keepIds = emptySet())
      return emptyList()
    }
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      error("Failed to create resolved-target report output directory: ${outputDir.absolutePath}")
    }
    val trailmapsById = resolvedTrailmaps.associateBy { it.manifest.id }
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    // Discover YAML-defined tools once (classpath scan) and look up by name when rendering
    // sidecars for `yamlOnly` toolbox entries. Fall back to an empty map if discovery throws
    // — sidecars will render the YAML tool as "no description / parameter list available"
    // rather than failing the whole emission for a broken classpath.
    val yamlDefinedToolsByName: Map<String, ToolYamlConfig> = runCatching {
      ToolYamlLoader.discoverYamlDefinedTools()
    }.onFailure { e ->
      Console.error(
        "ResolvedTargetReportEmitter: ToolYamlLoader.discoverYamlDefinedTools() failed — " +
          "sidecars for YAML-defined tools will render without description/parameter " +
          "metadata. ${e::class.simpleName}: ${e.message}",
      )
    }.getOrNull().orEmpty().mapKeys { it.key.toolName }
    val yamlToolConfigsByName = TrailblazeSerializationInitializer.buildYamlDefinedTools()
    val written = mutableListOf<File>()
    val keepNames = mutableSetOf<String>()
    val keepIds = mutableSetOf<String>()
    for (target in resolvedTargets) {
      val trailmap = trailmapsById[target.id]
      if (trailmap == null) {
        Console.error(
          "ResolvedTargetReportEmitter: target '${target.id}' has no matching trailmap in the " +
            "trailmap pool — skipping report emission. The trailmap pool must contain the target's " +
            "own manifest for provenance + trace walks.",
        )
        continue
      }
      val agentToolbox = computeAgentToolbox(
        target = target,
        ownTrailmap = trailmap,
        trailmapsById = trailmapsById,
        catalog = catalog,
        yamlToolConfigsByName = yamlToolConfigsByName,
      )
      val runtimeRegistry = runtimeRegistryWithProvenance(trailmap.manifest.id, trailmapsById)
      val resolutionTrace = traceContributions(trailmap, trailmapsById)
      val toolDetails = collectToolDetails(
        agentToolbox = agentToolbox,
        runtimeRegistry = runtimeRegistry,
        catalog = catalog,
        yamlDefinedToolsByName = yamlDefinedToolsByName,
        trailmapsById = trailmapsById,
      )
      val rendered = renderReport(
        target = target,
        ownTrailmap = trailmap,
        agentToolbox = agentToolbox,
        runtimeRegistry = runtimeRegistry,
        resolutionTrace = resolutionTrace,
        catalog = catalog,
        toolDetails = toolDetails,
      )
      val outFile = File(outputDir, "${target.id}.report.md")
      keepNames += outFile.name
      keepIds += target.id
      if (writeIfChanged(outFile, rendered)) {
        written += outFile
      }
      written += writeSidecars(outputDir, target.id, toolDetails)
    }
    deleteOrphanReports(outputDir, keepNames = keepNames)
    deleteOrphanSidecarDirs(outputDir, keepIds = keepIds)
    return written.sortedBy { it.absolutePath }
  }

  private fun writeSidecars(
    outputDir: File,
    targetId: String,
    toolDetails: Map<String, ResolvedTargetToolDetailRenderer.ToolDetail>,
  ): List<File> {
    val sidecarDir = File(outputDir, "$targetId/tools")
    if (toolDetails.isEmpty()) {
      deleteOrphanSidecarFiles(sidecarDir, keepNames = emptySet())
      return emptyList()
    }
    if (!sidecarDir.exists() && !sidecarDir.mkdirs()) {
      error("Failed to create per-tool sidecar directory: ${sidecarDir.absolutePath}")
    }
    val written = mutableListOf<File>()
    val keepNames = mutableSetOf<String>()
    for ((toolName, detail) in toolDetails) {
      val outFile = File(sidecarDir, "$toolName.md")
      val rendered = ResolvedTargetToolDetailRenderer.renderMarkdown(detail, targetId = targetId)
      keepNames += outFile.name
      if (ResolvedTargetIdempotentWrite.writeSidecarIfChanged(sidecarDir, outFile, rendered)) {
        written += outFile
      }
    }
    deleteOrphanSidecarFiles(sidecarDir, keepNames = keepNames)
    return written
  }

  private fun deleteOrphanSidecarFiles(sidecarDir: File, keepNames: Set<String>) {
    if (!sidecarDir.isDirectory) return
    val candidates = sidecarDir.listFiles { f ->
      f.isFile && f.name.endsWith(".md") && f.name !in keepNames
    } ?: return
    for (file in candidates) {
      val firstLine = runCatching { file.bufferedReader().use { it.readLine().orEmpty() } }.getOrNull()
        ?: continue
      if (firstLine == ResolvedTargetToolDetailRenderer.GENERATED_BANNER) {
        file.delete()
      }
    }
  }

  private fun deleteOrphanSidecarDirs(outputDir: File, keepIds: Set<String>) {
    if (!outputDir.isDirectory) return
    val candidates = outputDir.listFiles { f -> f.isDirectory && f.name !in keepIds } ?: return
    for (dir in candidates) {
      val toolsDir = File(dir, "tools")
      if (!toolsDir.isDirectory) continue
      val mdFiles = toolsDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
      if (mdFiles.isEmpty()) continue
      val allEmitterOwned = mdFiles.all { file ->
        val firstLine = runCatching {
          file.bufferedReader().use { it.readLine().orEmpty() }
        }.getOrNull()
        firstLine == ResolvedTargetToolDetailRenderer.GENERATED_BANNER
      }
      if (!allEmitterOwned) continue
      mdFiles.forEach { it.delete() }
      if (toolsDir.listFiles()?.isEmpty() == true) toolsDir.delete()
      if (dir.listFiles()?.isEmpty() == true) dir.delete()
    }
  }

  /**
   * Builds a `toolName -> ToolDetail` map for every tool that appears in the index report
   * (runtime registry, agent toolbox class-backed/yaml-only/additions across every
   * (platform, driver) slice, scripted tools). Tools the report names but for which no
   * metadata is reachable (e.g. raw `tools:` additions that don't resolve to a known
   * KClass / YAML config) are omitted — the renderer falls back to a plain non-clickable
   * cell.
   */
  private fun collectToolDetails(
    agentToolbox: AgentToolbox,
    runtimeRegistry: Map<String, Map<String, List<String>>>,
    catalog: List<ToolSetCatalogEntry>,
    yamlDefinedToolsByName: Map<String, ToolYamlConfig>,
    trailmapsById: Map<String, ResolvedTrailmap>,
  ): Map<String, ResolvedTargetToolDetailRenderer.ToolDetail> {
    val out = linkedMapOf<String, ResolvedTargetToolDetailRenderer.ToolDetail>()
    val classByName = mutableMapOf<String, KClass<out TrailblazeTool>>()

    for (entry in catalog) {
      for (kclass in entry.toolClasses) {
        val resolvedName = runCatching { kclass.toolName().toolName }.getOrNull() ?: continue
        classByName.putIfAbsent(resolvedName, kclass)
      }
    }

    fun consider(name: String) {
      if (out.containsKey(name)) return
      classByName[name]?.let {
        out[name] = ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked(name = name, kclass = it)
        return
      }
      // Any tool name with a registered YAML config gets a sidecar — the runtime resolver
      // (`ToolNameResolver.resolveYamlNameOrNull`) accepts these via direct `platforms.<p>.
      // tools:` additions too, not just toolset membership. Pre-fix, the gate also required
      // `name in yamlNamesSeen` (i.e. the catalog has to list the name in some toolset's
      // `yamlToolNames`), which dropped direct YAML additions on the floor and left the
      // tool name un-linkified in the matrix. The `yamlDefinedToolsByName` map is the
      // authoritative "this is a YAML-defined tool" check at this layer.
      yamlDefinedToolsByName[name]?.let { yamlConfig ->
        out[name] = ResolvedTargetToolDetailRenderer.ToolDetail.YamlDefined(name = name, config = yamlConfig)
        return
      }
    }

    runtimeRegistry.values.forEach { perToolSet ->
      perToolSet.keys.forEach { toolSetId ->
        catalog.firstOrNull { it.id == toolSetId }?.toolNames?.forEach(::consider)
      }
    }
    agentToolbox.perPlatform.values.forEach { platformBox ->
      platformBox.drivers.forEach { driver ->
        driver.classBacked.forEach(::consider)
        driver.yamlOnly.forEach(::consider)
      }
      platformBox.additions.forEach(::consider)
    }
    agentToolbox.scriptedTools.forEach { entry ->
      val name = entry.tool.name
      val existing = out[name]
      if (existing is ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked ||
        existing is ResolvedTargetToolDetailRenderer.ToolDetail.YamlDefined
      ) {
        val existingKind = if (existing is ResolvedTargetToolDetailRenderer.ToolDetail.ClassBacked) {
          "class-backed (${existing.kclass.qualifiedName ?: existing.kclass.simpleName})"
        } else {
          "YAML-defined"
        }
        Console.error(
          "ResolvedTargetReportEmitter: scripted tool `$name` from trailmap " +
            "`${entry.originTrailmapId}` is shadowed by a $existingKind tool of the same name. " +
            "The sidecar will link to the $existingKind variant; the scripted version is hidden.",
        )
        return@forEach
      }
      val originTrailmapDir = (trailmapsById[entry.originTrailmapId]?.source as? TrailmapSource.Filesystem)?.trailmapDir
      out[name] = ResolvedTargetToolDetailRenderer.ToolDetail.Scripted(
        name = name,
        config = entry.tool,
        originTrailmapId = entry.originTrailmapId,
        consumerTrailmapId = entry.consumerTrailmapId,
        originTrailmapDir = originTrailmapDir,
      )
    }
    return out
  }

  private fun deleteOrphanReports(outputDir: File, keepNames: Set<String>) {
    if (!outputDir.isDirectory) return
    val candidates = outputDir.listFiles { f ->
      f.isFile && f.name.endsWith(".report.md") && f.name !in keepNames
    } ?: return
    for (file in candidates) {
      val firstLine = runCatching { file.bufferedReader().use { it.readLine().orEmpty() } }.getOrNull()
        ?: continue
      if (firstLine == GENERATED_BANNER || firstLine in LEGACY_GENERATED_BANNERS) {
        file.delete()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  private fun toolCell(
    name: String,
    targetId: String,
    toolDetails: Map<String, ResolvedTargetToolDetailRenderer.ToolDetail>,
  ): String =
    if (name in toolDetails) {
      "[`$name`]($targetId/tools/$name.md)"
    } else {
      "`$name`"
    }

  private fun renderReport(
    target: AppTargetYamlConfig,
    ownTrailmap: ResolvedTrailmap,
    agentToolbox: AgentToolbox,
    runtimeRegistry: Map<String, Map<String, List<String>>>,
    resolutionTrace: ResolutionTrace,
    catalog: List<ToolSetCatalogEntry>,
    toolDetails: Map<String, ResolvedTargetToolDetailRenderer.ToolDetail>,
  ): String {
    val platformKeys = (
      target.platforms.orEmpty().keys +
        runtimeRegistry.keys +
        agentToolbox.perPlatform.keys
      ).toSortedSet()

    return buildString {
      appendLine(GENERATED_BANNER)
      appendLine("<!-- Source trailmap: trailmaps/${target.id}/trailmap.yaml -->")
      appendLine("<!-- Regenerate with: trailblaze check -->")
      appendLine()
      appendLine("# ${target.id} — agent toolbox report")
      appendLine()
      appendLine("Display name: ${target.displayName}")
      if (platformKeys.isNotEmpty()) {
        appendLine("Resolved platforms: ${platformKeys.joinToString(", ") { "`$it`" }}")
      } else {
        appendLine("Resolved platforms: _(none declared)_")
      }
      appendLine()

      appendLine("## Toolset closure (runtime registry — transitive union)")
      appendLine()
      appendLine(
        "These tools CAN dispatch at runtime — every tool reachable through any trailmap-in-" +
          "closure's `platforms.<platform>.tool_sets:` declaration. Library trailmaps' internal " +
          "tools land here even when they aren't surfaced to the agent via `exports:`.",
      )
      appendLine()
      renderRuntimeRegistry(runtimeRegistry, catalog, target.id, toolDetails)

      appendLine("## Agent toolbox (what the LLM sees at session start)")
      appendLine()
      appendLine(
        "The subset advertised to the LLM — rendered per (platform, driver) because the live " +
          "inner-agent provider binds the toolbox to whichever driver the session is using. " +
          "Computed with the same primitives `TrailblazeMcpServer` uses at session start " +
          "(`TrailblazeToolSetCatalog.resolveForDriver` plus `surfaceToLlm` filtering), so a " +
          "tool listed here is a tool the LLM will see, and a tool absent here is one it " +
          "won't. Scripted tools (trailmap-local + transitively-inherited `exports:`) are listed " +
          "below the per-driver slices since they're driver-agnostic.",
      )
      appendLine()
      renderAgentToolbox(agentToolbox, target.id, toolDetails)

      renderAvailabilityMatrix(target, agentToolbox, runtimeRegistry, catalog, toolDetails)

      appendLine("## Resolution trace")
      appendLine()
      appendLine(
        "Per-platform attribution: each field shows whether the resolved value was declared " +
          "by this trailmap or filled in from a dep via closest-wins overlay.",
      )
      appendLine()
      renderResolutionTrace(ownTrailmap, resolutionTrace)

      appendLine("## Stats")
      appendLine()
      val catalogById = catalog.associateBy { it.id }
      val runtimeToolNames = runtimeRegistry.values
        .flatMap { perToolSet -> perToolSet.keys }
        .flatMap { toolSetId -> catalogById[toolSetId]?.toolNames.orEmpty() }
        .toSet()
      val netAgentToolNames = agentToolbox.perPlatform.values.flatMap { platformBox ->
        platformBox.drivers.flatMap { driver ->
          val included = driver.classBacked + driver.yamlOnly + platformBox.additions
          included - platformBox.exclusions
        }
      }.toSet()
      val totalAgentTools = netAgentToolNames.size
      val totalExcluded = agentToolbox.perPlatform.values.flatMap { it.exclusions }.toSet().size
      val totalScripted = agentToolbox.scriptedTools.size
      appendLine("- ${runtimeToolNames.size} tool(s) in runtime registry across ${runtimeRegistry.size} platform(s)")
      appendLine("- $totalAgentTools class-backed/YAML tool(s) in agent toolbox (after `tools:` additions + `excluded_tools:` removals)")
      appendLine("- $totalExcluded tool(s) excluded by `excluded_tools:`")
      appendLine("- $totalScripted scripted tool(s) in typed surface (trailmap-local + transitive exports)")
    }
  }

  private fun StringBuilder.renderRuntimeRegistry(
    runtimeRegistry: Map<String, Map<String, List<String>>>,
    catalog: List<ToolSetCatalogEntry>,
    targetId: String,
    toolDetails: Map<String, ResolvedTargetToolDetailRenderer.ToolDetail>,
  ) {
    if (runtimeRegistry.isEmpty()) {
      appendLine("_(no platforms declare `tool_sets:` in this trailmap's closure)_")
      appendLine()
      return
    }
    val catalogById = catalog.associateBy { it.id }
    for ((platform, toolSetsToTrailmaps) in runtimeRegistry.toSortedMap()) {
      appendLine("### Platform `$platform`")
      appendLine()
      val rows = mutableListOf<Triple<String, String, String>>()
      for ((toolSetId, contributorTrailmapIds) in toolSetsToTrailmaps.toSortedMap()) {
        val entry = catalogById[toolSetId]
        val toolNames = entry?.toolNames.orEmpty()
        val originLabel = contributorTrailmapIds.joinToString(", ") { "`$it`" }
        if (toolNames.isEmpty()) {
          rows += Triple("_(no tools in catalog for `$toolSetId`)_", toolSetId, originLabel)
        } else {
          for (toolName in toolNames.sorted()) {
            rows += Triple(toolName, toolSetId, originLabel)
          }
        }
      }
      if (rows.isEmpty()) {
        appendLine("_(no tool_sets declared for this platform)_")
        appendLine()
        continue
      }
      appendLine("| Tool | Source toolset | Origin trailmap(s) |")
      appendLine("|---|---|---|")
      rows.sortedBy { it.first }.forEach { (tool, toolSet, origin) ->
        val cell = if (tool.startsWith("_(")) tool else toolCell(tool, targetId, toolDetails)
        appendLine("| $cell | `$toolSet` | $origin |")
      }
      appendLine()
    }
  }

  private fun StringBuilder.renderAgentToolbox(
    agentToolbox: AgentToolbox,
    targetId: String,
    toolDetails: Map<String, ResolvedTargetToolDetailRenderer.ToolDetail>,
  ) {
    val platforms = agentToolbox.perPlatform.keys.toSortedSet()
    if (platforms.isEmpty() && agentToolbox.scriptedTools.isEmpty()) {
      appendLine("_(empty toolbox — no resolved tool_sets, no scripted tools)_")
      appendLine()
    } else {
      for (platform in platforms) {
        val platformBox = agentToolbox.perPlatform[platform] ?: continue
        appendLine("### Platform `$platform`")
        appendLine()
        val exclusions = platformBox.exclusions
        if (platformBox.drivers.isEmpty()) {
          appendLine(
            "_(no drivers resolved for this platform — the LLM would not be served any tools " +
              "from this platform's `tool_sets:`)_",
          )
          if (exclusions.isNotEmpty()) {
            appendLine()
            appendLine("Excluded by `excluded_tools:`:")
            exclusions.toSortedSet().forEach { appendLine("- `$it` (excluded)") }
          }
          appendLine()
          continue
        }
        for (driver in platformBox.drivers) {
          appendLine("#### Driver `${driver.driverYamlKey}`")
          appendLine()
          val classBacked = (driver.classBacked - exclusions).toSortedSet()
          val yamlOnly = (driver.yamlOnly - exclusions).toSortedSet()
          val additions = (platformBox.additions - exclusions).toSortedSet()
          if (classBacked.isEmpty() && yamlOnly.isEmpty() && additions.isEmpty()) {
            appendLine("_(no class-backed or YAML tools resolved for this driver)_")
            appendLine()
            continue
          }
          classBacked.forEach { appendLine("- ${toolCell(it, targetId, toolDetails)}") }
          yamlOnly.forEach { appendLine("- ${toolCell(it, targetId, toolDetails)} (YAML-defined)") }
          additions.forEach { appendLine("- ${toolCell(it, targetId, toolDetails)} (individual `tools:` addition)") }
          appendLine()
        }
        if (exclusions.isNotEmpty()) {
          appendLine("Excluded by `excluded_tools:`:")
          exclusions.toSortedSet().forEach { appendLine("- `$it` (excluded)") }
          appendLine()
        }
      }

      appendLine("### Scripted tools (trailmap-local + transitive exports)")
      appendLine()
      if (agentToolbox.scriptedTools.isEmpty()) {
        appendLine("_(no scripted tools)_")
        appendLine()
      } else {
        agentToolbox.scriptedTools.sortedBy { it.tool.name }.forEach { entry ->
          val origin = if (entry.originTrailmapId == entry.consumerTrailmapId) {
            "from this trailmap"
          } else {
            "exported from dep `${entry.originTrailmapId}`"
          }
          appendLine("- ${toolCell(entry.tool.name, targetId, toolDetails)} ($origin)")
        }
        appendLine()
      }
    }
  }

  /**
   * Renders the "Tool availability matrix" section — a single dense table with one row per
   * tool and one ✅/❌/blank column per (platform, driver) cell this target supports. This
   * is the at-a-glance "where does this tool work?" view that the docs pipeline
   * has historically emitted as a standalone `TARGET_<id>.md` file; baking it into the
   * workspace report makes `trailblaze check` the single source of truth for every per-
   * target doc surface — no Gradle docs generator required.
   *
   * Cell semantics mirror the legacy matrix:
   *  - ✅ — tool resolves under this (platform, driver) after `surfaceToLlm` + driver-
   *    compatibility filtering (i.e. the LLM would see it on a session bound to that
   *    driver running the target's app).
   *  - ❌ — tool is reachable through some toolset for this driver but the target's
   *    `excluded_tools:` removed it.
   *  - blank — tool is not part of this driver's toolbox at all (driver-incompatible
   *    toolset, or just no overlap).
   *
   * The "Toolset(s)" column walks the runtime registry's catalog membership for each tool
   * name so a reader can see which `tool_sets:` declaration brought it in; scripted tools
   * carry a `script:<filename>` pseudo-toolset label so the source kind is still legible.
   *
   * **Tracked retirement.** `TargetToolBaselineGenerator` (in `:docs:generator`) currently
   * emits the same matrix shape into per-target docs via a Gradle generator task. Once
   * classpath-discovered targets migrate to workspace trailmaps, that generator becomes
   * redundant and the workspace report (this function) is the single source of truth.
   * The data sources this function covers — class-backed toolsets, YAML-defined tools,
   * individual `tools:` additions, exclusions, and `target.tools:` scripted tools (with
   * `supportedPlatforms` scoping) — mirror the legacy generator's coverage so the
   * retirement doesn't lose any tool surface from the matrix.
   */
  private fun StringBuilder.renderAvailabilityMatrix(
    target: AppTargetYamlConfig,
    agentToolbox: AgentToolbox,
    runtimeRegistry: Map<String, Map<String, List<String>>>,
    catalog: List<ToolSetCatalogEntry>,
    toolDetails: Map<String, ResolvedTargetToolDetailRenderer.ToolDetail>,
  ) {
    // Each driver column is (platform, driverYamlKey). Sort by platform alphabetically to
    // match the Agent toolbox section above (which iterates via `toSortedSet()`); driver
    // order within each platform is the resolver's declaration order from
    // `resolveDriversForReport`, which is already ordinal-sorted. Aligning ordering here
    // means a reader scanning both sections sees platforms in the same order — without
    // this, the matrix used insertion order while the bullet list used alphabetical, and
    // a two-platform target rendered them in different sequences (caught by Copilot
    // review on PR #3326).
    val driverColumns: List<Pair<String, String>> = agentToolbox.perPlatform.entries
      .sortedBy { it.key }
      .flatMap { (platform, box) -> box.drivers.map { driver -> platform to driver.driverYamlKey } }
    appendLine("## Tool availability matrix")
    appendLine()

    if (driverColumns.isEmpty()) {
      // No driver columns: the table shape (`| Tool | Toolset(s) |` header with zero
      // driver columns) would render `| | |` trailing-empty cells that don't carry
      // information. Show an empty-state instead, listing any target-root scripted
      // tools so the reader still sees what would dispatch if a driver were configured.
      val rootScripted = agentToolbox.scriptedTools.map { it.tool.name }.toSortedSet()
      if (rootScripted.isEmpty()) {
        appendLine(
          "_(empty matrix — no drivers resolved for any platform and no scripted tools " +
            "to render against)_",
        )
      } else {
        appendLine(
          "_(no drivers resolved for any platform — the matrix needs at least one " +
            "(platform, driver) cell to render. Target-root scripted tools that would " +
            "still dispatch on any driver if one were configured: " +
            rootScripted.joinToString(", ") { "`$it`" } + ".)_",
        )
      }
      appendLine()
      return
    }

    appendLine(
      "At-a-glance view of every tool the agent toolbox surfaces, with ✅/❌/blank cells " +
        "per (platform, driver). The same data the per-driver bullet lists above carry, " +
        "presented as a single matrix for cross-driver comparison — mirrors the legacy " +
        "internal `TARGET_<id>.md` matrix shape so the workspace report is the single " +
        "source of truth.",
    )
    appendLine()

    // Render-local mutable accumulator — diverges from the file's immutable-data-class
    // convention deliberately because each tool name is encountered multiple times during
    // the (platform × driver) walk and getOrPut + in-place set mutation is the cleanest
    // shape. Stays scoped to this function so the mutation surface is bounded.
    data class Entry(
      val toolSets: MutableSet<String> = sortedSetOf(),
      val included: MutableSet<Pair<String, String>> = mutableSetOf(),
      val excluded: MutableSet<Pair<String, String>> = mutableSetOf(),
    )
    val entries = mutableMapOf<String, Entry>()
    val catalogById = catalog.associateBy { it.id }

    /** Shared helper for both included- and excluded-name branches — looks up the per-platform
     *  runtime-registry toolsets and adds the ones that (a) actually contain this tool name
     *  and (b) are compatible with this driver. The driver-compatibility filter mirrors the
     *  legacy `TargetToolBaselineGenerator`'s `toolSet.isCompatibleWith(dt)` check — without
     *  it, a driver-incompatible toolset that happens to list the same tool name would be
     *  wrongly attributed under a driver it can't actually surface for. Defer extracting
     *  this to a file-scope helper until a third caller needs the same lookup — keeping
     *  it local for now avoids over-abstracting for a two-caller pattern. */
    fun attributeToolsetsFor(toolName: String, platform: String, driver: DriverToolbox, entry: Entry) {
      val driverType = runCatching { DriverTypeKey.resolve(driver.driverYamlKey).first() }.getOrNull()
      runtimeRegistry[platform]?.keys?.forEach { toolSetId ->
        val toolSetEntry = catalogById[toolSetId] ?: return@forEach
        if (toolName !in toolSetEntry.toolNames) return@forEach
        // If we couldn't resolve the driver type (malformed key in resolveDriversForReport),
        // be conservative and skip the compatibility filter — better to over-attribute than
        // silently drop the toolset name.
        if (driverType != null && !toolSetEntry.isCompatibleWith(driverType)) return@forEach
        entry.toolSets += toolSetId
      }
    }

    // Class-backed / YAML-only / additions per (platform, driver). Apply exclusions
    // upfront so they only count as ❌ when the tool would otherwise have been included
    // (mirrors `YamlBackedHostAppTarget.getExcludedToolsForDriver` semantics).
    for ((platform, platformBox) in agentToolbox.perPlatform) {
      for (driver in platformBox.drivers) {
        val cell = platform to driver.driverYamlKey
        val classBacked = driver.classBacked
        val yamlOnly = driver.yamlOnly
        val additions = platformBox.additions
        val included = (classBacked + yamlOnly + additions) - platformBox.exclusions
        val excluded = platformBox.exclusions intersect (classBacked + yamlOnly + additions)
        for (toolName in included) {
          val entry = entries.getOrPut(toolName) { Entry() }
          entry.included += cell
          attributeToolsetsFor(toolName, platform, driver, entry)
        }
        for (toolName in excluded) {
          val entry = entries.getOrPut(toolName) { Entry() }
          entry.excluded += cell
          attributeToolsetsFor(toolName, platform, driver, entry)
        }
      }
    }

    // Trailmap-authored scripted tools (`target.tools:` and exported deps). Driver scope is
    // narrowed by the `_meta["trailblaze/supportedPlatforms"]` field when present —
    // mirrors `TargetToolBaselineGenerator.driversForScriptedTool`. Without this filter
    // a scripted tool declared with `supportedPlatforms: [android]` would wrongly show ✅
    // under web drivers (caught by Copilot review on PR #3326).
    for (scripted in agentToolbox.scriptedTools) {
      val entry = entries.getOrPut(scripted.tool.name) { Entry() }
      val scriptFile = File(scripted.tool.script).name
      entry.toolSets += "script:$scriptFile"
      scriptedToolApplicableColumns(scripted.tool, driverColumns).forEach { entry.included += it }
    }

    if (entries.isEmpty()) {
      appendLine("_(no tools resolve under any driver for this target)_")
      appendLine()
      return
    }

    val headers = driverColumns.map { (platform, driver) -> "$driver (${platform.uppercase()})" }
    appendLine("| Tool | Toolset(s) | ${headers.joinToString(" | ")} |")
    appendLine("|------|------------|${headers.joinToString("|") { ":---:" }}|")
    for (toolName in entries.keys.sorted()) {
      val entry = entries[toolName]!!
      val toolSetLabel = entry.toolSets.joinToString(", ").ifEmpty { "-" }
      val cells = driverColumns.map { col ->
        when {
          col in entry.excluded -> "❌"
          col in entry.included -> "✅"
          else -> ""
        }
      }
      appendLine("| ${toolCell(toolName, target.id, toolDetails)} | $toolSetLabel | ${cells.joinToString(" | ")} |")
    }
    appendLine()
  }

  /**
   * Narrows [driverColumns] for a scripted tool by its `_meta["trailblaze/supportedPlatforms"]`
   * field, falling back to the full set when the metadata is missing or malformed. Mirrors
   * `TargetToolBaselineGenerator.driversForScriptedTool`. A scripted tool declared with
   * `supportedPlatforms: [android, ios]` only resolves under those platforms' driver columns;
   * without this filter the matrix would over-report availability under e.g. web drivers.
   */
  private fun scriptedToolApplicableColumns(
    tool: InlineScriptToolConfig,
    driverColumns: List<Pair<String, String>>,
  ): List<Pair<String, String>> {
    val raw = tool.meta?.get("trailblaze/supportedPlatforms") ?: return driverColumns
    val array = runCatching { (raw as kotlinx.serialization.json.JsonArray) }.getOrNull()
      ?: return driverColumns
    val supportedPlatforms = array.mapNotNullTo(linkedSetOf()) { element ->
      runCatching { (element as kotlinx.serialization.json.JsonPrimitive).content.lowercase() }.getOrNull()
    }
    if (supportedPlatforms.isEmpty()) return driverColumns
    return driverColumns.filter { (platform, _) -> platform.lowercase() in supportedPlatforms }
  }

  private fun StringBuilder.renderResolutionTrace(
    ownTrailmap: ResolvedTrailmap,
    trace: ResolutionTrace,
  ) {
    if (trace.perPlatform.isEmpty()) {
      appendLine("_(no platforms declared on this target)_")
      appendLine()
    } else {
      for ((platform, fields) in trace.perPlatform.toSortedMap()) {
        appendLine("### Platform `$platform`")
        appendLine()
        if (fields.isEmpty()) {
          appendLine("_(empty platform block — no fields resolved)_")
          appendLine()
          continue
        }
        for (field in fields) {
          val value = field.renderedValue
          val attribution = when (val source = field.source) {
            FieldSource.OwnDeclaration ->
              "declared by `${ownTrailmap.manifest.id}/trailmap.yaml`"
            is FieldSource.InheritedFromDep ->
              "inherited from `${source.depId}` trailmap defaults at depth ${source.depth} " +
                "(consumer left null)"
            FieldSource.Unset ->
              "_(unset — no contributor in closure)_"
          }
          appendLine("- `${field.fieldName} = $value` — $attribution")
        }
        appendLine()
      }
    }

    appendLine("### Dependencies")
    appendLine()
    if (ownTrailmap.manifest.dependencies.isEmpty()) {
      appendLine("_(no `dependencies:` declared — target relies only on its own trailmap manifest)_")
    } else {
      appendLine(
        "- `dependencies: [${ownTrailmap.manifest.dependencies.joinToString(", ")}]` " +
          "— each dep contributes `defaults:` via closest-wins overlay",
      )
    }
    appendLine()
  }

  // ---------------------------------------------------------------------------
  // Agent-toolbox computation
  // ---------------------------------------------------------------------------

  private data class AgentToolbox(
    val perPlatform: Map<String, PlatformToolbox>,
    val scriptedTools: List<ScriptedToolEntry>,
  )

  private data class PlatformToolbox(
    val drivers: List<DriverToolbox>,
    val additions: Set<String>,
    val exclusions: Set<String>,
  )

  private data class DriverToolbox(
    val driverYamlKey: String,
    val classBacked: Set<String>,
    val yamlOnly: Set<String>,
  )

  private data class ScriptedToolEntry(
    val tool: InlineScriptToolConfig,
    val originTrailmapId: String,
    val consumerTrailmapId: String,
  )

  private fun computeAgentToolbox(
    target: AppTargetYamlConfig,
    ownTrailmap: ResolvedTrailmap,
    trailmapsById: Map<String, ResolvedTrailmap>,
    catalog: List<ToolSetCatalogEntry>,
    yamlToolConfigsByName: Map<ToolName, ToolYamlConfig>,
  ): AgentToolbox {
    val perPlatform = mutableMapOf<String, PlatformToolbox>()
    target.platforms.orEmpty().forEach { (platformKey, platformConfig) ->
      val additions = platformConfig.tools.orEmpty().toSet()
      val exclusions = platformConfig.excludedTools.orEmpty().toSet()
      val toolSets = platformConfig.toolSets.orEmpty()
      val driverTypes = resolveDriversForReport(platformKey, platformConfig)
      val drivers = driverTypes.map { driverType ->
        val resolved = TrailblazeToolSetCatalog.resolveForDriver(
          driverType = driverType,
          requestedIds = toolSets,
          catalog = catalog,
        )
        val classBacked = resolved.toolClasses
          .filter { it.trailblazeToolClassAnnotation().surfaceToLlm }
          .mapNotNull { kclass -> runCatching { kclass.toolName().toolName }.getOrNull() }
          .toSet()
        val yamlOnly = resolved.yamlToolNames
          .filter { name ->
            val config = yamlToolConfigsByName[name]
            when {
              config == null -> {
                Console.log(
                  "ResolvedTargetReportEmitter: no YAML config registered for tool " +
                    "'${name.toolName}' on platform=$platformKey driver=${driverType.yamlKey}; " +
                    "will be skipped at LLM registration.",
                )
                false
              }
              config.surfaceToLlm == false -> false
              else -> true
            }
          }
          .map { it.toolName }
          .toSet()
        DriverToolbox(
          driverYamlKey = driverType.yamlKey,
          classBacked = classBacked,
          yamlOnly = yamlOnly,
        )
      }
      perPlatform[platformKey] = PlatformToolbox(
        drivers = drivers,
        additions = additions,
        exclusions = exclusions,
      )
    }
    val scriptedTools = collectScriptedTools(ownTrailmap, trailmapsById)
    return AgentToolbox(perPlatform = perPlatform, scriptedTools = scriptedTools)
  }

  private fun resolveDriversForReport(
    platformKey: String,
    platformConfig: PlatformConfig,
  ): List<TrailblazeDriverType> {
    val driverTypes = runCatching { platformConfig.resolveDriverTypes(platformKey) }
      .onFailure { e ->
        Console.error(
          "ResolvedTargetReportEmitter: platform '$platformKey' drivers resolution failed: " +
            "${e::class.simpleName}: ${e.message ?: ""}",
        )
      }
      .getOrDefault(emptySet())
    val explicitDrivers = platformConfig.drivers
    return if (explicitDrivers != null) {
      explicitDrivers
        .flatMap { token ->
          runCatching { DriverTypeKey.resolve(token) }
            .onFailure { e ->
              Console.error(
                "ResolvedTargetReportEmitter: platform '$platformKey' drivers entry '$token' " +
                  "is not a known driver-type key: ${e.message ?: ""}",
              )
            }
            .getOrDefault(emptySet())
            .sortedBy { it.ordinal }
        }
        .filter { it in driverTypes }
        .distinct()
    } else {
      driverTypes.toList().sortedBy { it.ordinal }
    }
  }

  private fun collectScriptedTools(
    ownTrailmap: ResolvedTrailmap,
    trailmapsById: Map<String, ResolvedTrailmap>,
  ): List<ScriptedToolEntry> {
    val byName = linkedMapOf<String, ScriptedToolEntry>()
    ownTrailmap.target?.tools.orEmpty().forEach { tool ->
      byName.putIfAbsent(
        tool.name,
        ScriptedToolEntry(
          tool = tool,
          originTrailmapId = ownTrailmap.manifest.id,
          consumerTrailmapId = ownTrailmap.manifest.id,
        ),
      )
    }
    val visited = mutableSetOf(ownTrailmap.manifest.id)
    val frontier = ArrayDeque<String>()
    ownTrailmap.manifest.dependencies.forEach { frontier.add(it) }
    while (frontier.isNotEmpty()) {
      val depId = frontier.removeFirst()
      if (!visited.add(depId)) continue
      val dep = trailmapsById[depId] ?: continue
      val depExports = dep.manifest.exports?.toSet().orEmpty()
      if (depExports.isNotEmpty()) {
        val depToolsByName = dep.target?.tools.orEmpty().associateBy { it.name }
        depExports.forEach { exportName ->
          val tool = depToolsByName[exportName] ?: return@forEach
          if (byName.containsKey(tool.name)) return@forEach
          byName[tool.name] = ScriptedToolEntry(
            tool = tool,
            originTrailmapId = dep.manifest.id,
            consumerTrailmapId = ownTrailmap.manifest.id,
          )
        }
      }
      dep.manifest.dependencies.forEach { frontier.add(it) }
    }
    return byName.values.toList()
  }

  // ---------------------------------------------------------------------------
  // Runtime registry (transitive union with provenance)
  // ---------------------------------------------------------------------------

  private fun runtimeRegistryWithProvenance(
    rootTrailmapId: String,
    trailmapsById: Map<String, ResolvedTrailmap>,
  ): Map<String, Map<String, List<String>>> {
    data class PendingContribution(val platform: String, val toolSet: String, val trailmapId: String)
    val out = mutableListOf<PendingContribution>()
    val mentionedPlatforms = linkedSetOf<String>()
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()

    fun walk(trailmapId: String) {
      if (trailmapId in visiting || trailmapId in visited) return
      val trailmap = trailmapsById[trailmapId] ?: return
      visiting += trailmapId
      val platforms = trailmap.manifest.platforms ?: trailmap.manifest.target?.platforms
      platforms?.forEach { (platformKey, platformConfig) ->
        val toolSets = platformConfig.toolSets ?: return@forEach
        mentionedPlatforms += platformKey
        toolSets.forEach { toolSetId ->
          out += PendingContribution(platform = platformKey, toolSet = toolSetId, trailmapId = trailmapId)
        }
      }
      trailmap.manifest.dependencies.forEach { childDep -> walk(childDep) }
      visiting -= trailmapId
      visited += trailmapId
    }
    walk(rootTrailmapId)

    val result = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
    mentionedPlatforms.forEach { platform -> result.getOrPut(platform) { mutableMapOf() } }
    out.forEach { contribution ->
      val platformBucket = result.getOrPut(contribution.platform) { mutableMapOf() }
      val trailmapList = platformBucket.getOrPut(contribution.toolSet) { mutableListOf() }
      if (contribution.trailmapId !in trailmapList) trailmapList += contribution.trailmapId
    }
    return result
  }

  // ---------------------------------------------------------------------------
  // Resolution trace (closest-wins per-field provenance)
  // ---------------------------------------------------------------------------

  private data class ResolutionTrace(val perPlatform: Map<String, List<FieldTrace>>)

  private data class FieldTrace(
    val fieldName: String,
    val renderedValue: String,
    val source: FieldSource,
  )

  private sealed class FieldSource {
    object OwnDeclaration : FieldSource()
    data class InheritedFromDep(val depId: String, val depth: Int) : FieldSource()
    object Unset : FieldSource()
  }

  private fun traceContributions(
    ownTrailmap: ResolvedTrailmap,
    trailmapsById: Map<String, ResolvedTrailmap>,
  ): ResolutionTrace {
    val ownPlatforms = ownTrailmap.manifest.target?.platforms.orEmpty()
    if (ownPlatforms.isEmpty()) return ResolutionTrace(emptyMap())

    val contributions = mutableListOf<Contribution>()
    val visiting = mutableSetOf(ownTrailmap.manifest.id)

    fun walk(depId: String, depth: Int) {
      if (depId in visiting) return
      val dep = trailmapsById[depId] ?: return
      visiting += depId
      dep.manifest.defaults?.let {
        contributions += Contribution(
          depth = depth,
          encounterOrder = contributions.size,
          depId = depId,
          defaults = it,
        )
      }
      dep.manifest.dependencies.forEach { walk(it, depth + 1) }
      visiting -= depId
    }
    ownTrailmap.manifest.dependencies.forEach { walk(it, 1) }

    val closestFirst = contributions.sortedWith(
      compareBy<Contribution> { it.depth }.thenByDescending { it.encounterOrder },
    )

    val perPlatform = mutableMapOf<String, List<FieldTrace>>()
    for ((platformKey, ownConfig) in ownPlatforms) {
      perPlatform[platformKey] = traceFields(platformKey, ownConfig, closestFirst)
    }
    return ResolutionTrace(perPlatform)
  }

  private fun traceFields(
    platformKey: String,
    own: PlatformConfig,
    contributions: List<Contribution>,
  ): List<FieldTrace> {
    val fields: List<Triple<String, Any?, (PlatformConfig) -> Any?>> = listOf(
      Triple("app_ids", own.appIds) { it.appIds },
      Triple("tool_sets", own.toolSets) { it.toolSets },
      Triple("tools", own.tools) { it.tools },
      Triple("excluded_tools", own.excludedTools) { it.excludedTools },
      Triple("drivers", own.drivers) { it.drivers },
      Triple("base_url", own.baseUrl) { it.baseUrl },
      Triple("min_build_version", own.minBuildVersion) { it.minBuildVersion },
    )
    val result = mutableListOf<FieldTrace>()
    for ((fieldName, ownValue, reader) in fields) {
      if (ownValue != null) {
        result += FieldTrace(
          fieldName = fieldName,
          renderedValue = renderFieldValue(ownValue),
          source = FieldSource.OwnDeclaration,
        )
        continue
      }
      val match = contributions.firstNotNullOfOrNull { contribution ->
        val depPlatform = contribution.defaults[platformKey] ?: return@firstNotNullOfOrNull null
        val value = reader(depPlatform) ?: return@firstNotNullOfOrNull null
        contribution to value
      }
      if (match == null) {
        result += FieldTrace(
          fieldName = fieldName,
          renderedValue = "null",
          source = FieldSource.Unset,
        )
      } else {
        val (contribution, value) = match
        result += FieldTrace(
          fieldName = fieldName,
          renderedValue = renderFieldValue(value),
          source = FieldSource.InheritedFromDep(depId = contribution.depId, depth = contribution.depth),
        )
      }
    }
    return result
  }

  private data class Contribution(
    val depth: Int,
    val encounterOrder: Int,
    val depId: String,
    val defaults: Map<String, PlatformConfig>,
  )

  private fun renderFieldValue(value: Any?): String = when (value) {
    null -> "null"
    is List<*> -> value.joinToString(prefix = "[", postfix = "]")
    is String -> "\"$value\""
    else -> value.toString()
  }

  // ---------------------------------------------------------------------------
  // Idempotency
  // ---------------------------------------------------------------------------

  private fun writeIfChanged(file: File, content: String): Boolean =
    ResolvedTargetIdempotentWrite.writeIfChanged(file, content)

  internal const val GENERATED_BANNER = "<!-- GENERATED BY trailblaze check. DO NOT EDIT. -->"

  private val LEGACY_GENERATED_BANNERS = setOf(
    "<!-- GENERATED BY trailblaze compile. DO NOT EDIT. -->",
  )
}
