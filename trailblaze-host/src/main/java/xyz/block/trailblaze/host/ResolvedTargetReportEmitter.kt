package xyz.block.trailblaze.host

import java.io.File
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig
import xyz.block.trailblaze.config.project.PackSource
import xyz.block.trailblaze.config.project.ResolvedPack
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console

/**
 * Per-target Markdown report emitter — renders the resolved agent toolbox + resolution
 * trace into a browsable `<workspace>/trails/config/dist/targets/<id>.report.md` next to
 * the resolved YAML, so authors can answer "what is the agent told about for this target
 * and where did each piece come from?" without grepping Kotlin source.
 *
 * One report per app target. Sections mirror the three-layer pack-typing model:
 *
 *   1. **Toolset closure (runtime registry).** Transitive union of every pack-in-closure's
 *      `platforms.<p>.tool_sets:` declarations, expanded through the toolset catalog into
 *      concrete tool names. Attributed to source toolset + the pack(s) that contributed
 *      that toolset.
 *   2. **Agent toolbox (what the LLM sees).** The subset advertised to the LLM at session
 *      start — the resolved target's per-platform `tool_sets:` (post closest-wins overlay)
 *      plus the pack-local scripted tools and transitively-inherited `exports:`.
 *   3. **Resolution trace.** Per-platform, per-field attribution: which `pack.yaml` field
 *      came from this pack's own declaration vs inherited from a dep via closest-wins.
 *   4. **Stats.** Tool counts per layer.
 *
 * Idempotent: the rendered text is content-hash compared against the existing file before
 * writing. Unchanged generations don't churn mtimes, so the report doesn't keep showing up
 * in `git status` after running `trailblaze compile` repeatedly.
 *
 * Pure rendering step — no new data sources. Reads the already-resolved
 * [AppTargetYamlConfig] + [ResolvedPack] list produced by the compile pipeline, plus the
 * workspace's [TrailblazeToolSetCatalog]. Failure handling is the caller's responsibility:
 * this helper either writes a report or throws.
 *
 * The walks inside `runtimeRegistryWithProvenance` and `traceContributions` mirror
 * [xyz.block.trailblaze.config.project.PackRuntimeRegistryResolver] and
 * [xyz.block.trailblaze.config.project.PackDependencyResolver] respectively. They are
 * inlined here so the report layer doesn't depend on `internal` resolver internals.
 * The semantics MUST stay in lockstep — if either resolver's composition rule changes,
 * update the corresponding walk here so the report doesn't drift from runtime behavior.
 */
object ResolvedTargetReportEmitter {

  /**
   * Emit one `<id>.report.md` per [resolvedTargets] entry into [outputDir]. Returns the
   * paths of every file written (excludes content-hash no-op skips).
   *
   * @param resolvedTargets the fully-resolved targets produced by the compiler.
   * @param resolvedPacks the full pack pool (target + library, filesystem + classpath) used
   *   to walk dep closures for provenance + trace. The pack with id == target.id must be
   *   present.
   * @param outputDir the directory to write reports into. Created if missing.
   */
  fun emit(
    resolvedTargets: List<AppTargetYamlConfig>,
    resolvedPacks: List<ResolvedPack>,
    outputDir: File,
  ): List<File> {
    if (resolvedTargets.isEmpty()) {
      deleteOrphanReports(outputDir, keepNames = emptySet())
      return emptyList()
    }
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      error("Failed to create resolved-target report output directory: ${outputDir.absolutePath}")
    }
    val packsById = resolvedPacks.associateBy { it.manifest.id }
    val catalog = TrailblazeToolSetCatalog.defaultEntries()
    val written = mutableListOf<File>()
    val keepNames = mutableSetOf<String>()
    for (target in resolvedTargets) {
      val pack = packsById[target.id]
      if (pack == null) {
        Console.error(
          "ResolvedTargetReportEmitter: target '${target.id}' has no matching pack in the " +
            "pack pool — skipping report emission. The pack pool must contain the target's " +
            "own manifest for provenance + trace walks.",
        )
        continue
      }
      val rendered = renderReport(target = target, ownPack = pack, packsById = packsById, catalog = catalog)
      val outFile = File(outputDir, "${target.id}.report.md")
      keepNames += outFile.name
      if (writeIfChanged(outFile, rendered)) {
        written += outFile
      }
    }
    deleteOrphanReports(outputDir, keepNames = keepNames)
    return written.sortedBy { it.name }
  }

  /**
   * Deletes any `<id>.report.md` files in [outputDir] that this emitter wrote on a previous
   * run (identified by the [GENERATED_BANNER] header) but no longer correspond to a current
   * target. Files without the banner are left alone — the emitter only manages files it
   * owns. Mirrors `TrailblazeCompiler.deleteOrphanOutputs` for the YAML side.
   */
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

  private fun renderReport(
    target: AppTargetYamlConfig,
    ownPack: ResolvedPack,
    packsById: Map<String, ResolvedPack>,
    catalog: List<ToolSetCatalogEntry>,
  ): String {
    val runtimeRegistry = runtimeRegistryWithProvenance(ownPack.manifest.id, packsById)
    val agentToolbox = computeAgentToolbox(target, ownPack, packsById, catalog)
    val resolutionTrace = traceContributions(ownPack, packsById)
    val platformKeys = (
      target.platforms.orEmpty().keys +
        runtimeRegistry.keys +
        agentToolbox.classBacked.keys
      ).toSortedSet()

    return buildString {
      appendLine(GENERATED_BANNER)
      appendLine("<!-- Source pack: packs/${target.id}/pack.yaml -->")
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
        "These tools CAN dispatch at runtime — every tool reachable through any pack-in-" +
          "closure's `platforms.<platform>.tool_sets:` declaration. Library packs' internal " +
          "tools land here even when they aren't surfaced to the agent via `exports:`.",
      )
      appendLine()
      renderRuntimeRegistry(runtimeRegistry, catalog)

      appendLine("## Agent toolbox (what the LLM sees at session start)")
      appendLine()
      appendLine(
        "The subset advertised to the LLM — the resolved target's per-platform `tool_sets:` " +
          "(post closest-wins overlay) plus pack-local scripted tools and transitively-" +
          "inherited `exports:` from deps.",
      )
      appendLine()
      renderAgentToolbox(agentToolbox)

      appendLine("## Resolution trace")
      appendLine()
      appendLine(
        "Per-platform attribution: each field shows whether the resolved value was declared " +
          "by this pack or filled in from a dep via closest-wins overlay.",
      )
      appendLine()
      renderResolutionTrace(ownPack, resolutionTrace)

      appendLine("## Stats")
      appendLine()
      val catalogById = catalog.associateBy { it.id }
      val runtimeToolNames = runtimeRegistry.values
        .flatMap { perToolSet -> perToolSet.keys }
        .flatMap { toolSetId -> catalogById[toolSetId]?.toolNames.orEmpty() }
        .toSet()
      // Net agent-toolbox size = toolset closure + individual `tools:` additions − `excluded_tools:`
      // exclusions, applied per platform then unioned across platforms.
      val netAgentToolNamesPerPlatform = (agentToolbox.classBacked.keys + agentToolbox.yamlOnly.keys + agentToolbox.additions.keys).map { platform ->
        val included = agentToolbox.classBacked[platform].orEmpty() +
          agentToolbox.yamlOnly[platform].orEmpty() +
          agentToolbox.additions[platform].orEmpty()
        included - agentToolbox.exclusions[platform].orEmpty()
      }
      val totalAgentTools = netAgentToolNamesPerPlatform.flatten().toSet().size
      val totalExcluded = agentToolbox.exclusions.values.flatten().toSet().size
      val totalScripted = agentToolbox.scriptedTools.size
      appendLine("- ${runtimeToolNames.size} tool(s) in runtime registry across ${runtimeRegistry.size} platform(s)")
      appendLine("- $totalAgentTools class-backed/YAML tool(s) in agent toolbox (after `tools:` additions + `excluded_tools:` removals)")
      appendLine("- $totalExcluded tool(s) excluded by `excluded_tools:`")
      appendLine("- $totalScripted scripted tool(s) in typed surface (pack-local + transitive exports)")
    }
  }

  private fun StringBuilder.renderRuntimeRegistry(
    runtimeRegistry: Map<String, Map<String, List<String>>>,
    catalog: List<ToolSetCatalogEntry>,
  ) {
    if (runtimeRegistry.isEmpty()) {
      appendLine("_(no platforms declare `tool_sets:` in this pack's closure)_")
      appendLine()
      return
    }
    val catalogById = catalog.associateBy { it.id }
    for ((platform, toolSetsToPacks) in runtimeRegistry.toSortedMap()) {
      appendLine("### Platform `$platform`")
      appendLine()
      val rows = mutableListOf<Triple<String, String, String>>()
      for ((toolSetId, contributorPackIds) in toolSetsToPacks.toSortedMap()) {
        val entry = catalogById[toolSetId]
        val toolNames = entry?.toolNames.orEmpty()
        val originLabel = contributorPackIds.joinToString(", ") { "`$it`" }
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
      appendLine("| Tool | Source toolset | Origin pack(s) |")
      appendLine("|---|---|---|")
      rows.sortedBy { it.first }.forEach { (tool, toolSet, origin) ->
        appendLine("| `$tool` | `$toolSet` | $origin |")
      }
      appendLine()
    }
  }

  private fun StringBuilder.renderAgentToolbox(agentToolbox: AgentToolbox) {
    val platforms = (
      agentToolbox.classBacked.keys +
        agentToolbox.yamlOnly.keys +
        agentToolbox.additions.keys +
        agentToolbox.exclusions.keys
      ).toSortedSet()
    if (platforms.isEmpty() && agentToolbox.scriptedTools.isEmpty()) {
      appendLine("_(empty toolbox — no resolved tool_sets, no scripted tools)_")
      appendLine()
    } else {
      for (platform in platforms) {
        appendLine("### Platform `$platform`")
        appendLine()
        val exclusions = agentToolbox.exclusions[platform].orEmpty()
        // Apply name-based subtraction — `excluded_tools:` removes by tool name from both
        // the toolset closure and any individual `tools:` additions. Mirrors
        // `YamlBackedHostAppTarget.getExcludedToolsForDriver` semantics at the report layer.
        val classBacked = (agentToolbox.classBacked[platform].orEmpty() - exclusions).toSortedSet()
        val yamlOnly = (agentToolbox.yamlOnly[platform].orEmpty() - exclusions).toSortedSet()
        val additions = (agentToolbox.additions[platform].orEmpty() - exclusions).toSortedSet()
        if (classBacked.isEmpty() && yamlOnly.isEmpty() && additions.isEmpty()) {
          appendLine("_(no class-backed or YAML tools resolved for this platform)_")
          if (exclusions.isNotEmpty()) {
            appendLine()
            appendLine("Excluded by `excluded_tools:`:")
            exclusions.toSortedSet().forEach { appendLine("- `$it` (excluded)") }
          }
          appendLine()
          continue
        }
        classBacked.forEach { appendLine("- `$it`") }
        yamlOnly.forEach { appendLine("- `$it` (YAML-defined)") }
        additions.forEach { appendLine("- `$it` (individual `tools:` addition)") }
        if (exclusions.isNotEmpty()) {
          appendLine()
          appendLine("Excluded by `excluded_tools:`:")
          exclusions.toSortedSet().forEach { appendLine("- `$it` (excluded)") }
        }
        appendLine()
      }

      appendLine("### Scripted tools (pack-local + transitive exports)")
      appendLine()
      if (agentToolbox.scriptedTools.isEmpty()) {
        appendLine("_(no scripted tools)_")
        appendLine()
      } else {
        agentToolbox.scriptedTools.sortedBy { it.tool.name }.forEach { entry ->
          val origin = if (entry.originPackId == entry.consumerPackId) {
            "from this pack"
          } else {
            "exported from dep `${entry.originPackId}`"
          }
          appendLine("- `${entry.tool.name}` ($origin)")
        }
        appendLine()
      }
    }
  }

  private fun StringBuilder.renderResolutionTrace(
    ownPack: ResolvedPack,
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
              "declared by `${ownPack.manifest.id}/pack.yaml`"
            is FieldSource.InheritedFromDep ->
              "inherited from `${source.depId}` pack defaults at depth ${source.depth} " +
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
    if (ownPack.manifest.dependencies.isEmpty()) {
      appendLine("_(no `dependencies:` declared — target relies only on its own pack manifest)_")
    } else {
      appendLine(
        "- `dependencies: [${ownPack.manifest.dependencies.joinToString(", ")}]` " +
          "— each dep contributes `defaults:` via closest-wins overlay",
      )
    }
    appendLine()
  }

  // ---------------------------------------------------------------------------
  // Agent-toolbox computation
  // ---------------------------------------------------------------------------

  private data class AgentToolbox(
    val classBacked: Map<String, Set<String>>,
    val yamlOnly: Map<String, Set<String>>,
    /**
     * Per-platform names from `platforms.<p>.tools:` — additional tools the consumer adds
     * on top of the toolset closure. Stored as raw names; we don't classify them as class-
     * backed vs YAML-defined here (would require the runtime `ToolNameResolver`), so the
     * renderer surfaces them in a dedicated "individual tools:" line. The runtime applies
     * `excluded_tools:` to these too, so the renderer subtracts before listing.
     */
    val additions: Map<String, Set<String>>,
    /**
     * Per-platform names from `platforms.<p>.excluded_tools:`. Applied by name to the union
     * of the toolset closure + individual additions before rendering, mirroring the runtime
     * `YamlBackedHostAppTarget` exclusion path.
     */
    val exclusions: Map<String, Set<String>>,
    val scriptedTools: List<ScriptedToolEntry>,
  )

  private data class ScriptedToolEntry(
    val tool: InlineScriptToolConfig,
    val originPackId: String,
    val consumerPackId: String,
  )

  private fun computeAgentToolbox(
    target: AppTargetYamlConfig,
    ownPack: ResolvedPack,
    packsById: Map<String, ResolvedPack>,
    catalog: List<ToolSetCatalogEntry>,
  ): AgentToolbox {
    val classBacked = mutableMapOf<String, Set<String>>()
    val yamlOnly = mutableMapOf<String, Set<String>>()
    val additions = mutableMapOf<String, Set<String>>()
    val exclusions = mutableMapOf<String, Set<String>>()
    target.platforms.orEmpty().forEach { (platform, platformConfig) ->
      additions[platform] = platformConfig.tools.orEmpty().toSet()
      exclusions[platform] = platformConfig.excludedTools.orEmpty().toSet()
      val toolSets = platformConfig.toolSets.orEmpty()
      if (toolSets.isEmpty()) {
        classBacked[platform] = emptySet()
        yamlOnly[platform] = emptySet()
        return@forEach
      }
      val resolved = TrailblazeToolSetCatalog.resolve(requestedIds = toolSets, catalog = catalog)
      classBacked[platform] = resolved.toolClasses.mapNotNull { kclass ->
        runCatching { kclass.toolName().toolName }.getOrNull()
      }.toSet()
      yamlOnly[platform] = resolved.yamlToolNames.map { it.toolName }.toSet()
    }
    val scriptedTools = collectScriptedTools(ownPack, packsById)
    return AgentToolbox(
      classBacked = classBacked,
      yamlOnly = yamlOnly,
      additions = additions,
      exclusions = exclusions,
      scriptedTools = scriptedTools,
    )
  }

  /**
   * Pack-local scripted tools + transitively-inherited tools from deps' `exports:`. Same
   * shape as [PerPackClientDtsEmitter.collectPackTypedScriptedTools] — pack-local first
   * (overrides dep-exported tools by name), then dep-exported tools in dependency-closure
   * encounter order (BFS via the `ArrayDeque` frontier, not depth-first).
   *
   * Unlike the typed-surface emitter, this method does NOT throw on cross-dep collisions
   * or unresolved-export typos — those throw at codegen time in [PerPackClientDtsEmitter]
   * and the report runs AFTER codegen in the compile pipeline, so we never observe a bad
   * pack pool here. Defensive skip + log if either case ever surfaces.
   */
  private fun collectScriptedTools(
    ownPack: ResolvedPack,
    packsById: Map<String, ResolvedPack>,
  ): List<ScriptedToolEntry> {
    val byName = linkedMapOf<String, ScriptedToolEntry>()
    ownPack.target?.tools.orEmpty().forEach { tool ->
      byName.putIfAbsent(
        tool.name,
        ScriptedToolEntry(
          tool = tool,
          originPackId = ownPack.manifest.id,
          consumerPackId = ownPack.manifest.id,
        ),
      )
    }
    val visited = mutableSetOf(ownPack.manifest.id)
    val frontier = ArrayDeque<String>()
    ownPack.manifest.dependencies.forEach { frontier.add(it) }
    while (frontier.isNotEmpty()) {
      val depId = frontier.removeFirst()
      if (!visited.add(depId)) continue
      val dep = packsById[depId] ?: continue
      val depExports = dep.manifest.exports?.toSet().orEmpty()
      if (depExports.isNotEmpty()) {
        val depToolsByName = dep.target?.tools.orEmpty().associateBy { it.name }
        depExports.forEach { exportName ->
          val tool = depToolsByName[exportName] ?: return@forEach
          if (byName.containsKey(tool.name)) return@forEach
          byName[tool.name] = ScriptedToolEntry(
            tool = tool,
            originPackId = dep.manifest.id,
            consumerPackId = ownPack.manifest.id,
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

  /**
   * Mirrors `PackRuntimeRegistryResolver.resolveRuntimeToolSets` but records which pack(s)
   * in the closure contributed each toolset to each platform — that's what the report's
   * "Origin pack" column needs. Missing-dep / cycle conditions are silently skipped: the
   * compile pipeline already validated the graph before we got here.
   *
   * Mirrors the resolver's `contribute(pack, accumulator)` rule for an explicit empty
   * `tool_sets: []`: a non-null but empty list still seeds the platform key in the output
   * map (with an empty inner map) so callers can distinguish "platform declared but empty"
   * from "platform never mentioned." A null `tool_sets:` (field absent) does not.
   *
   * Returns `platform → toolSetId → list of contributor pack ids (in DFS visit order)`.
   */
  private fun runtimeRegistryWithProvenance(
    rootPackId: String,
    packsById: Map<String, ResolvedPack>,
  ): Map<String, Map<String, List<String>>> {
    data class PendingContribution(val platform: String, val toolSet: String, val packId: String)
    val out = mutableListOf<PendingContribution>()
    val mentionedPlatforms = linkedSetOf<String>()
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()

    fun walk(packId: String) {
      if (packId in visiting || packId in visited) return
      val pack = packsById[packId] ?: return
      visiting += packId
      val platforms = pack.manifest.platforms ?: pack.manifest.target?.platforms
      platforms?.forEach { (platformKey, platformConfig) ->
        val toolSets = platformConfig.toolSets ?: return@forEach
        // Materialize the platform key even when tool_sets is `[]` — mirrors
        // PackRuntimeRegistryResolver.contribute's `getOrPut(platformKey) { mutableSetOf() }`.
        mentionedPlatforms += platformKey
        toolSets.forEach { toolSetId ->
          out += PendingContribution(platform = platformKey, toolSet = toolSetId, packId = packId)
        }
      }
      pack.manifest.dependencies.forEach { childDep -> walk(childDep) }
      visiting -= packId
      visited += packId
    }
    walk(rootPackId)

    val result = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
    mentionedPlatforms.forEach { platform -> result.getOrPut(platform) { mutableMapOf() } }
    out.forEach { contribution ->
      val platformBucket = result.getOrPut(contribution.platform) { mutableMapOf() }
      val packList = platformBucket.getOrPut(contribution.toolSet) { mutableListOf() }
      if (contribution.packId !in packList) packList += contribution.packId
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

  /**
   * Builds the resolution trace by replaying the dep walk: for each platform field, decide
   * if the consumer's own manifest set it (`OwnDeclaration`), or if it came from a dep's
   * `defaults:` via closest-wins overlay (`InheritedFromDep` with depth + dep id). Mirrors
   * [xyz.block.trailblaze.config.project.PackDependencyResolver.resolveTarget]'s semantics:
   *
   *  - Own non-null fields win unconditionally.
   *  - For unset (null) consumer fields, take the closest depth's contributor in DFS order
   *    (later siblings at the same depth win — recorded via encounter index).
   */
  private fun traceContributions(
    ownPack: ResolvedPack,
    packsById: Map<String, ResolvedPack>,
  ): ResolutionTrace {
    val ownPlatforms = ownPack.manifest.target?.platforms.orEmpty()
    if (ownPlatforms.isEmpty()) return ResolutionTrace(emptyMap())

    val contributions = mutableListOf<Contribution>()
    val visiting = mutableSetOf(ownPack.manifest.id)

    fun walk(depId: String, depth: Int) {
      if (depId in visiting) return
      val dep = packsById[depId] ?: return
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
    ownPack.manifest.dependencies.forEach { walk(it, 1) }

    // Sort closest-first (ascending depth), then by DESCENDING encounter order so the
    // FIRST hit wins for each field. PackDependencyResolver applies overlays farthest-
    // first with later-overwrites-earlier semantics; at the same depth, the
    // later-declared sibling wins (its overlay runs last). To pick that same winner via
    // `firstNotNullOfOrNull`, we read in the opposite direction — closest depth, latest
    // encounter — so the first match we see is the one the resolver would end up with.
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
    // Each field gets a (name, ownValue, accessor) tuple. The accessor reads the same
    // field off a dep's `defaults:` PlatformConfig — used to walk the closest-first list
    // and find the first contributor.
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
      // Find closest dep contributor that set this field on this platform.
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

  // Internal alias so the trace function's signature stays Any-free above.
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

  /**
   * Writes [content] to [file] only if the current on-disk bytes don't match. Returns true
   * if a write happened, false on a no-op skip. Keeps mtimes stable across repeated runs.
   */
  private fun writeIfChanged(file: File, content: String): Boolean {
    val finalText = if (content.endsWith("\n")) content else content + "\n"
    if (file.isFile) {
      val existing = runCatching { file.readText() }.getOrNull()
      if (existing == finalText) return false
    }
    file.parentFile?.mkdirs()
    file.writeText(finalText)
    return true
  }

  /**
   * First line of every report. Markdown comment so the doc renders without a banner
   * heading polluting the visible output, while still being grep-able + recognizable as a
   * generated artifact.
   */
  internal const val GENERATED_BANNER = "<!-- GENERATED BY trailblaze check. DO NOT EDIT. -->"

  /**
   * Banners written by previous versions of this emitter. [deleteOrphanReports] treats
   * files starting with any of these as emitter-owned for cleanup purposes, so renaming
   * the CLI verb (e.g. the `trailblaze compile` → `trailblaze check` unification) doesn't
   * strand stale `.report.md` files in users' working trees. Same migration shape
   * [xyz.block.trailblaze.compile.TrailblazeCompiler] uses for its YAML banner.
   */
  private val LEGACY_GENERATED_BANNERS = setOf(
    "<!-- GENERATED BY trailblaze compile. DO NOT EDIT. -->",
  )
}
