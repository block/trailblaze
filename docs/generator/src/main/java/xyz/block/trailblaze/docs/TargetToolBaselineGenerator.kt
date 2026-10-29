package xyz.block.trailblaze.docs

import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.config.AppTargetCompanion
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ResolvedToolSet
import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolSetYamlConfig
import xyz.block.trailblaze.config.ToolSetYamlLoader
import xyz.block.trailblaze.config.TrailblazeConfigYaml
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration
import xyz.block.trailblaze.toolcalls.ResolvedTargetIdempotentWrite
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer.ToolDetail
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor as toKoogToolDescriptorForTrailblazeDescriptor
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.allToolNameStrings
import xyz.block.trailblaze.toolcalls.allToolNames
import xyz.block.trailblaze.toolcalls.getExcludedToolSurfaceForDriver
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.toolName
import java.io.File

/**
 * Generates `docs/internal/targets/`: one file per trailmap saying what agents are told about,
 * plus a `README.md` index. Committed and diffed in CI, so a PR that changes what an agent sees
 * changes these files — and a change to one shared tool changes one file, not one page per target.
 *
 * Each fact is stated once:
 *
 *   - **A target's file** — the toolsets it loads, the tools it adds itself, and the tools it
 *     excludes, each with the drivers it applies to. A driver's tool list is its toolsets' tools
 *     plus its own tools, minus its excluded ones. Each item links to the file that declares it.
 *   - **The declaring trailmap's file** — each toolset with its tools, and each tool's
 *     description, contract (including whether it is hidden from the LLM) and parameters,
 *     rendered by [ResolvedTargetToolDetailRenderer.renderSection] — the same renderer the
 *     workspace `trailblaze check` sidecars and the framework reference pages use. A tool whose
 *     rendering differs between targets gets one entry per variant, labelled with its targets.
 *   - **The index** — every file, and per target and driver how many LLM-visible tools it sends
 *     and the description tokens they cost, so a PR that grows a description shows the delta.
 */
class TargetToolBaselineGenerator(
  private val generatedDir: File,
  private val companions: Map<String, AppTargetCompanion> = emptyMap(),
  /**
   * Pre-resolved targets contributed by callers that already have an
   * [AppTargetYamlConfig] in hand from a non-classpath source — typically opensource
   * example trailmaps that compile in-memory via
   * `TrailblazeProjectConfigLoader.resolveRuntime(...)`. Each entry's
   * [AdditionalTarget.trailmapDir] is the filesystem trailmap root used to render
   * scripted-tool `script:` paths as repo-relative strings; without it the runtime-resolved
   * absolute path would leak into the committed file and churn across machines.
   *
   * **Merge semantics.** Additional targets are unioned with the classpath-discovered
   * set; a duplicate `id` is a hard error (matches the classpath loader's contract).
   * The generator's other classpath-driven inputs — [ToolYamlLoader.discoverAndLoadAll],
   * [ToolNameResolver], [ToolSetYamlLoader.discoverAndLoadAll] — still come from the
   * classpath, so example-trailmap targets can reference framework toolsets (`memory`,
   * `web_core`, ...) the same way classpath targets do.
   */
  private val additionalTargets: List<AdditionalTarget> = emptyList(),
) {

  /**
   * One pre-resolved target supplied by a non-classpath caller. The wrapper carries
   * just enough metadata for the generator to merge it into the classpath flow and
   * relativize scripted-tool paths.
   */
  data class AdditionalTarget(
    /** Fully-resolved target shape — the same `AppTargetYamlConfig` a classpath load produces. */
    val config: AppTargetYamlConfig,
    /**
     * Filesystem root of the trailmap that authored this target (typically the workspace's
     * `trails/config/trailmaps/<id>/` directory). Used as
     * [ResolvedTargetToolDetailRenderer.ToolDetail.Scripted.originTrailmapDir]
     * so scripted tools render `./tools/foo.ts` instead of a per-machine
     * absolute path. Null is permitted but produces less-portable output.
     */
    val trailmapDir: File?,
  )

  /** One driver of one target: what it loads, adds, and excludes, and the tools that result. */
  internal data class DriverSurface(
    val driver: TrailblazeDriverType,
    val toolSets: Set<String>,
    val ownTools: Set<String>,
    val excluded: Set<String>,
    val available: Set<String>,
  )

  internal data class TargetSurface(
    val id: String,
    val displayName: String,
    val drivers: List<DriverSurface>,
    /** Every own tool the target declares, including ones none of its drivers can run. */
    val ownTools: Set<String>,
    /** Every tool this target reaches, with its metadata, or null when none is reachable. */
    val toolDetails: Map<String, ToolDetail?>,
  )

  fun generate() {
    val targetsDir = File(generatedDir, "targets").apply { mkdirs() }

    // Generator emits canonical bundled-classpath baselines committed to the repo and diffed
    // in CI. With the JVM platform default now workspace-aware, any developer running the
    // generator from a workspace would silently fold their `trails/config/` files into the
    // committed output — pin every discovery to ClasspathConfigResourceSource so the
    // generated artifacts stay reproducible across machines.
    val classpathOnly = ClasspathConfigResourceSource
    val customToolClasses = ToolYamlLoader.discoverAndLoadAll(resourceSource = classpathOnly)
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools(customToolClasses, resourceSource = classpathOnly)
    val toolSets = ToolSetYamlLoader.discoverAndLoadAll(resolver, resourceSource = classpathOnly)
    val classpathConfigs = AppTargetYamlLoader.discoverConfigs(resourceSource = classpathOnly)
    val classpathTargets = AppTargetYamlLoader.discoverAndLoadAll(
      toolNameResolver = resolver,
      availableToolSets = toolSets,
      companions = companions,
      resourceSource = classpathOnly,
    )

    // Merge classpath-discovered configs with caller-supplied additional configs. A
    // duplicate id between the two sources is a hard failure — the second would silently
    // replace the first's section. Naming both contributors keeps the failure diagnosable.
    val configsById = LinkedHashMap<String, AppTargetYamlConfig>()
    classpathConfigs.forEach { configsById[it.id] = it }
    val trailmapDirsByTargetId = LinkedHashMap<String, File>()
    additionalTargets.forEach { addition ->
      val previous = configsById.put(addition.config.id, addition.config)
      require(previous == null) {
        "Target id '${addition.config.id}' is declared by both a classpath-bundled " +
          "target and a workspace-resolved additional target. Rename one — duplicate " +
          "ids would collide in the tool-surface files."
      }
      addition.trailmapDir?.let { trailmapDirsByTargetId[addition.config.id] = it }
    }
    val targets = classpathTargets + additionalTargets.map { addition ->
      YamlBackedHostAppTarget(
        config = addition.config,
        toolNameResolver = resolver,
        availableToolSets = toolSets,
        companion = companions[addition.config.id],
      )
    }

    val discoveredScriptedByName =
      runCatching { ScriptedToolNameDiscoverer.discoverDescriptorsByName() }.getOrNull().orEmpty()

    val surfaces = targets.sortedBy { it.id }.map { target ->
      resolveTargetSurface(
        target = target,
        config = configsById[target.id],
        allToolSets = toolSets,
        resolver = resolver,
        discoveredScriptedByName = discoveredScriptedByName,
        trailmapDir = trailmapDirsByTargetId[target.id],
      )
    }
    val files = renderFiles(surfaces, toolSets, toolSetTrailmaps(classpathOnly))
    files.forEach { (name, content) -> ResolvedTargetIdempotentWrite.writeIfChanged(File(targetsDir, name), content) }
    pruneStaleFiles(targetsDir, keep = files.keys)
  }

  /**
   * Deletes the generated file of a trailmap that no longer declares a tool or toolset, so a removal
   * shows up as a deleted file rather than a stale one CI keeps diffing clean. Only files that start
   * with [GENERATED_HEADER] are touched: a hand-written note beside them survives regeneration.
   */
  internal fun pruneStaleFiles(dir: File, keep: Set<String>) {
    dir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name !in keep }
      ?.filter { it.useLines { lines -> lines.firstOrNull() == GENERATED_HEADER } }
      ?.forEach { it.delete() }
  }

  /**
   * Toolset id → the trailmap that declares it, from where its YAML lives
   * (`trailmaps/<trailmap>/toolsets/<name>.yaml`) — the same walk [ToolSetYamlLoader] makes.
   */
  private fun toolSetTrailmaps(resourceSource: ClasspathConfigResourceSource): Map<String, String> =
    resourceSource.discoverAndLoadRecursive(TrailblazeConfigPaths.TRAILMAPS_DIR, ".yaml")
      .mapNotNull { (relPath, content) ->
        val segments = relPath.split('/')
        if (segments.size < 3 || segments[1] != "toolsets") return@mapNotNull null
        val id = runCatching {
          TrailblazeConfigYaml.instance.decodeFromString(ToolSetYamlConfig.serializer(), content).id
        }.getOrNull() ?: return@mapNotNull null
        id to segments[0]
      }.toMap()

  private fun resolveTargetSurface(
    target: TrailblazeHostAppTarget,
    config: AppTargetYamlConfig?,
    allToolSets: Map<String, ResolvedToolSet>,
    resolver: ToolNameResolver,
    discoveredScriptedByName: Map<ToolName, ScriptedToolNameDiscoverer.DiscoveredDescriptor>,
    trailmapDir: File?,
  ): TargetSurface {
    if (config == null) return TargetSurface(target.id, target.displayName, emptyList(), emptySet(), emptyMap())
    // A target-shaped shared pack declares `target.tools:` but no `platforms:` — its tools reach
    // real app targets via `dependencies:`, so it has no drivers of its own.
    val platforms = config.platforms.orEmpty()

    // Retired drivers are dropped: a driver nothing can run on is an answer to a question no one
    // can act on — worse, it reads as a supported option.
    val allDrivers = platforms.flatMap { (platformKey, platformConfig) ->
      platformConfig.resolveDriverTypes(platformKey)
    }.filterNot { it in TrailblazeDriverType.RETIRED_DRIVERS }
      .toSortedSet(compareBy { it.name })

    // Route through the central `getExcludedToolSurfaceForDriver` accessor so the baseline honors
    // both exclusion backings (class / scripted) — reading only `getExcludedToolsForDriver` would
    // miss an `excluded_tools: [openUrl]` (scripted) and render an excluded tool as available.
    val excludedByDriver = allDrivers.associateWith { driverType ->
      target.getExcludedToolSurfaceForDriver(driverType).allToolNameStrings
    }

    // Which drivers each toolset applies to, from the platform sections that list it.
    val toolSetDriverScope = mutableMapOf<String, MutableSet<TrailblazeDriverType>>()
    for ((platformKey, platformConfig) in platforms) {
      val drivers = platformConfig.resolveDriverTypes(platformKey)
      platformConfig.toolSets?.forEach { tsId ->
        toolSetDriverScope.getOrPut(tsId) { mutableSetOf() }.addAll(drivers)
      }
    }
    // The runtime adds every compatible always-enabled toolset whether or not the target lists it
    // (TrailblazeToolSetCatalog.resolveForDriver), so scope those to every driver; the
    // `isCompatibleWith` check below narrows each one to the drivers it actually reaches.
    allToolSets.filter { (_, toolSet) ->
      toolSet.config.alwaysEnabled && allDrivers.any { toolSet.isCompatibleWith(it) }
    }.keys.forEach { tsId ->
      toolSetDriverScope.getOrPut(tsId) { mutableSetOf() }.addAll(allDrivers)
    }

    // Scripted-tool configs by name, from BOTH delivery mechanisms: toolset-delivered scripted
    // tools (resolved from the catalog descriptor) and target-declared `target.tools:` ones.
    val scriptedToolsByName = mutableMapOf<String, InlineScriptToolConfig>()
    toolSetDriverScope.keys.mapNotNull { allToolSets[it] }.forEach { toolSet ->
      toolSet.resolvedScriptedToolNames.map { it.toolName }.forEach { name ->
        if (scriptedToolsByName.containsKey(name)) return@forEach
        val discovered = discoveredScriptedByName[ToolName(name)] ?: return@forEach
        discovered.toolConfigs().firstOrNull { it.name == name }?.let { scriptedToolsByName[name] = it }
      }
    }
    config.tools?.forEach { scriptedToolsByName[it.name] = it }

    val drivers = allDrivers.map { dt ->
      val toolSetIds = toolSetDriverScope
        .filter { (tsId, scope) -> dt in scope && allToolSets[tsId]?.isCompatibleWith(dt) == true }
        .keys.toSortedSet()
      val fromToolSets = toolSetIds.flatMap { tsId -> allToolSets.getValue(tsId).allToolNames.map { it.toolName } }
      // Platform `tools:` lists reach every driver of their platform; a target's own scripted
      // tools are narrowed by their `supportedPlatforms:` / `supportedDrivers:`.
      val own = platforms.filter { (platformKey, platformConfig) -> dt in platformConfig.resolveDriverTypes(platformKey) }
        .flatMap { (_, platformConfig) -> platformConfig.tools.orEmpty() } +
        config.tools.orEmpty().filter { dt in driversForScriptedTool(it, allDrivers) }.map { it.name }
      val reached = (fromToolSets + own).toSet()
      val excluded = reached.filterTo(sortedSetOf()) { it in excludedByDriver.getValue(dt) }
      DriverSurface(
        driver = dt,
        toolSets = toolSetIds,
        ownTools = own.toSortedSet(),
        excluded = excluded,
        available = reached - excluded,
      )
    }
    val ownTools = (
      platforms.values.flatMap { it.tools.orEmpty() } + config.tools.orEmpty().map { it.name }
      ).toSortedSet()
    val allNames = drivers.flatMap { it.ownTools + it.toolSets.flatMap { ts -> allToolSets.getValue(ts).allToolNames.map { n -> n.toolName } } } + ownTools
    val toolDetails = allNames.toSortedSet().associateWith { name ->
      buildToolDetail(name, target.id, resolver, scriptedToolsByName, trailmapDir)
    }
    return TargetSurface(target.id, target.displayName, drivers, ownTools, toolDetails)
  }

  /** A tool's rendered entry, and the targets that render it that way. */
  private data class ToolVariant(val body: String, val targetIds: List<String>)

  /**
   * One file per trailmap, keyed by file name, plus the [INDEX_FILE_NAME] index. Each tool and each
   * toolset is written once, in the file of the trailmap that declares it, so a change to a shared
   * tool changes one file. A target's file also says what its agent loads, linking to the files that
   * declare it.
   */
  internal fun renderFiles(
    surfaces: List<TargetSurface>,
    allToolSets: Map<String, ResolvedToolSet>,
    toolSetTrailmaps: Map<String, String>,
  ): Map<String, String> {
    val usedToolSets = surfaces.flatMap { s -> s.drivers.flatMap { it.toolSets } }.toSortedSet()
    fun toolSetOwner(tsId: String): String =
      checkNotNull(toolSetTrailmaps[tsId]) { "toolset '$tsId' was loaded but no trailmap declares it" }

    val tokensByTargetTool = mutableMapOf<Pair<String, String>, Int?>()
    val variantsByTool = sortedMapOf<String, List<ToolVariant>>()
    val detailsByTool = mutableMapOf<String, MutableList<Pair<String, ToolDetail>>>()
    for (name in surfaces.flatMap { it.toolDetails.keys }.toSortedSet()) {
      // Group targets by the entry they render, so a tool shared by every target is listed once.
      val variants = LinkedHashMap<String, MutableList<String>>()
      for (surface in surfaces) {
        val detail = surface.toolDetails[name] ?: continue
        val tokens = llmDescriptionTokens(detail)
        tokensByTargetTool[surface.id to name] = tokens
        detailsByTool.getOrPut(name) { mutableListOf() } += surface.id to detail
        val body = "LLM description tokens: ${tokens ?: "- (hidden from the LLM)"}\n\n" +
          ResolvedTargetToolDetailRenderer.renderSection(detail)
        variants.getOrPut(body) { mutableListOf() } += surface.id
      }
      if (variants.isNotEmpty()) variantsByTool[name] = variants.map { (body, ids) -> ToolVariant(body, ids) }
    }

    val toolSetsByTool = usedToolSets.flatMap { tsId -> allToolSets.getValue(tsId).allToolNames.map { it.toolName to tsId } }
      .groupBy({ it.first }, { it.second })
    // A tool in several toolsets belongs with the one that reaches the most drivers (no driver list
    // reaches every driver): `mobile_primitives` over `android_framework` for a tool in both.
    val widestFirst = compareBy<String>({ ts ->
      allToolSets.getValue(ts).compatibleDriverTypes.size.let { if (it == 0) Int.MIN_VALUE else -it }
    }, { it })
    val toolOwner = variantsByTool.keys.associateWith { name ->
      val toolSetOwners = toolSetsByTool[name].orEmpty().sortedWith(widestFirst).map(::toolSetOwner)
      toolOwner(name, detailsByTool.getValue(name), toolSetOwners, surfaces)
    }

    val trailmapIds = (surfaces.map { it.id } + usedToolSets.map(::toolSetOwner) + toolOwner.values).toSortedSet()
    val surfacesById = surfaces.associateBy { it.id }
    fun toolLink(name: String, from: String) = link(name, toolOwner[name], from)

    val files = linkedMapOf<String, String>()
    files[INDEX_FILE_NAME] = renderIndex(surfaces, trailmapIds, tokensByTargetTool)
    for (trailmapId in trailmapIds) {
      files["$trailmapId.md"] = buildString {
        appendLine(GENERATED_HEADER)
        appendLine()
        val surface = surfacesById[trailmapId]
        if (surface != null) {
          appendLine("# `${surface.id}` — ${surface.displayName}")
          appendLine()
          appendLine("What the `${surface.id}` target's agent is told about. Every target: [$INDEX_FILE_NAME]($INDEX_FILE_NAME).")
          appendLine()
          renderTarget(surface, toolSetLink = { link(it, toolSetOwner(it), trailmapId) }, toolLink = { toolLink(it, trailmapId) })
        } else {
          appendLine("# `$trailmapId` trailmap")
          appendLine()
          appendLine("The toolsets and tools the `$trailmapId` trailmap declares. Every target: [$INDEX_FILE_NAME]($INDEX_FILE_NAME).")
          appendLine()
        }
        val ownToolSets = usedToolSets.filter { toolSetOwner(it) == trailmapId }
        if (ownToolSets.isNotEmpty()) {
          appendLine("## Toolsets")
          appendLine()
          ownToolSets.forEach { renderToolSet(allToolSets.getValue(it), toolLink = { name -> toolLink(name, trailmapId) }) }
        }
        val ownTools = variantsByTool.filterKeys { toolOwner[it] == trailmapId }
        if (ownTools.isNotEmpty()) {
          appendLine("## Tools")
          appendLine()
          ownTools.forEach { (name, variants) -> renderTool(name, variants) }
        }
      }.trimEnd() + "\n"
    }
    return files
  }

  /**
   * The trailmap that declares [name]: where a scripted tool's script lives, when its path names a
   * trailmap; else, of the trailmaps whose toolsets deliver it ([toolSetOwners], widest toolset
   * first), the one its name is prefixed with, or the first; else the target with the smallest
   * own-tool list that declares it — a shared pack's tools reach the targets that depend on it, so
   * the pack is the smallest holder. A relative script path says nothing about the declarer: a
   * framework toolset's scripted tool resolves relative to whichever consuming target rendered it.
   */
  private fun toolOwner(
    name: String,
    details: List<Pair<String, ToolDetail>>,
    toolSetOwners: List<String>,
    surfaces: List<TargetSurface>,
  ): String {
    val scripted = details.mapNotNull { (targetId, detail) -> (detail as? ToolDetail.Scripted)?.let { targetId to it } }
    scripted.firstNotNullOfOrNull { (_, detail) ->
      TRAILMAP_IN_PATH.find(detail.config.script.replace('\\', '/'))?.groupValues?.get(1)
    }?.let { return it }
    // Tool names are scoped by their trailmap (`clock_setAlarm`), so a matching prefix wins.
    (toolSetOwners.firstOrNull { name.startsWith("${it}_") } ?: toolSetOwners.firstOrNull())?.let { return it }
    return surfaces.filter { name in it.ownTools }.minWithOrNull(compareBy({ it.ownTools.size }, { it.id }))?.id
      ?: details.first().first
  }

  private fun link(name: String, ownerTrailmap: String?, from: String): String = when (ownerTrailmap) {
    null -> "`$name`"
    from -> "[`$name`](#${anchor(name)})"
    else -> "[`$name`]($ownerTrailmap.md#${anchor(name)})"
  }

  /** The anchor GitHub generates for a heading that is just `` `name` ``. */
  private fun anchor(name: String) = name.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }

  private fun renderIndex(
    surfaces: List<TargetSurface>,
    trailmapIds: Set<String>,
    tokensByTargetTool: Map<Pair<String, String>, Int?>,
  ): String = buildString {
    appendLine(GENERATED_HEADER)
    appendLine()
    appendLine("# LLM tool surface")
    appendLine()
    appendLine(
      "What each target's agent is told about, one file per trailmap. A target's file lists the toolsets " +
        "it loads, the tools it adds itself and the tools it excludes. Each toolset and each tool's " +
        "description, contract and parameters is written once, in the file of the trailmap that declares " +
        "it. A tool marked `Visible to LLM: no` can be called from a trail but is never offered to the LLM.",
    )
    appendLine()
    appendLine(
      "CI regenerates these files and fails on a diff, so a change to what an agent sees shows up here. " +
        "`trailblaze toolbox --target <id>` lists one target's tools expanded.",
    )
    appendLine()
    appendLine("## Targets")
    appendLine()
    surfaces.forEach { appendLine("- [`${it.id}`](${it.id}.md) — ${it.displayName}") }
    appendLine()
    val toolOnly = trailmapIds - surfaces.map { it.id }.toSet()
    if (toolOnly.isNotEmpty()) {
      appendLine("## Other trailmaps")
      appendLine()
      appendLine("Trailmaps that declare toolsets or tools but are not targets themselves.")
      appendLine()
      toolOnly.forEach { appendLine("- [`$it`]($it.md)") }
      appendLine()
    }
    appendLine("## Token totals")
    appendLine()
    appendLine(
      "Tool + parameter description text each driver sends the LLM: its tools that are visible to " +
        "the LLM, including the always-enabled framework tools every session gets. Tokens ≈ characters / 4. " +
        "These are host-driven session totals: an Android session whose agent runs on the device also drops " +
        "tools marked `requiresHost`.",
    )
    appendLine()
    appendLine("| Target | Driver | Tools | Tokens |")
    appendLine("|--------|--------|------:|-------:|")
    for (surface in surfaces) {
      for (driver in surface.drivers) {
        val sent = driver.available.mapNotNull { tokensByTargetTool[surface.id to it] }
        appendLine("| [`${surface.id}`](${surface.id}.md) | `${driver.driver.yamlKey}` | ${sent.size} | ${sent.sum()} |")
      }
    }
  }

  private fun StringBuilder.renderToolSet(toolSet: ResolvedToolSet, toolLink: (String) -> String) {
    appendLine("### `${toolSet.config.id}`")
    appendLine()
    toolSet.config.description?.takeIf { it.isNotBlank() }?.let {
      appendLine(it.trim())
      appendLine()
    }
    val compatible = toolSet.compatibleDriverTypes.map { it.yamlKey }.sorted()
    val alwaysEnabled = if (toolSet.config.alwaysEnabled) " Always enabled." else ""
    appendLine("Drivers: ${if (compatible.isEmpty()) "any" else compatible.joinToString(", ") { "`$it`" }}.$alwaysEnabled")
    appendLine()
    val names = toolSet.allToolNames.map { it.toolName }.sorted()
    if (names.isEmpty()) appendLine("_(no tools)_") else names.forEach { appendLine("- ${toolLink(it)}") }
    appendLine()
  }

  private fun StringBuilder.renderTool(name: String, variants: List<ToolVariant>) {
    // A split tool's headings carry a target suffix, so their generated anchors no longer equal
    // `#name`. Pin `#name` with an explicit anchor so links survive a tool gaining variants.
    if (variants.size > 1) {
      appendLine("<a id=\"${anchor(name)}\"></a>")
      appendLine()
    }
    for (variant in variants) {
      val suffix = if (variants.size == 1) "" else " (${variant.targetIds.joinToString(", ") { "`$it`" }})"
      appendLine("### `$name`$suffix")
      appendLine()
      append(variant.body)
    }
  }

  private fun StringBuilder.renderTarget(
    surface: TargetSurface,
    toolSetLink: (String) -> String,
    toolLink: (String) -> String,
  ) {
    val drivers = surface.drivers
    if (drivers.isEmpty()) {
      appendLine("Drivers: none. A shared pack: its tools reach app targets through `dependencies:`.")
    } else {
      appendLine("Drivers: ${drivers.joinToString(", ") { "`${it.driver.yamlKey}` (${it.driver.platform.name.lowercase()})" }}")
    }
    appendLine()
    // One line per item, naming the drivers it applies to, so adding or removing one item is a
    // one-line change rather than a row edit per driver.
    fun scope(applies: (DriverSurface) -> Boolean): String {
      val on = drivers.filter(applies).map { it.driver.yamlKey }
      return when {
        drivers.isEmpty() -> ""
        on.isEmpty() -> " — none of this target's drivers"
        on.size == drivers.size -> " — all"
        else -> " — " + on.joinToString(", ") { "`$it`" }
      }
    }
    fun section(title: String, names: Collection<String>, render: (String) -> String, applies: (DriverSurface, String) -> Boolean) {
      if (names.isEmpty()) return
      appendLine("$title:")
      appendLine()
      names.sorted().forEach { name -> appendLine("- ${render(name)}${scope { applies(it, name) }}") }
      appendLine()
    }
    section("Toolsets", drivers.flatMap { it.toolSets }.toSet(), toolSetLink) { d, n -> n in d.toolSets }
    section("Own tools", surface.ownTools, toolLink) { d, n -> n in d.ownTools }
    section("Excluded", drivers.flatMap { it.excluded }.toSet(), toolLink) { d, n -> n in d.excluded }
  }

  private fun buildToolDetail(
    toolName: String,
    targetId: String,
    resolver: ToolNameResolver,
    scriptedToolsByName: Map<String, InlineScriptToolConfig>,
    trailmapDir: File?,
  ): ToolDetail? {
    resolver.resolveOrNull(toolName)?.let { kclass ->
      return ToolDetail.ClassBacked(name = toolName, kclass = kclass)
    }
    scriptedToolsByName[toolName]?.let { scripted ->
      return ToolDetail.Scripted(
        name = toolName,
        config = scripted,
        originTrailmapId = targetId,
        consumerTrailmapId = targetId,
        // Passing the trailmap dir (when known) lets `ResolvedTargetToolDetailRenderer.renderScriptPath`
        // express the script as `./tools/foo.ts` instead of the runtime-resolved absolute path
        // that would otherwise leak per-machine state into the committed file.
        originTrailmapDir = trailmapDir,
      )
    }
    return null
  }

  /**
   * Estimated tokens of the description text [detail] sends the LLM — the tool description plus
   * every parameter description, from the same descriptor the runtime advertises — or null when
   * the tool is hidden from the LLM (or has no reachable metadata). Characters / 4, rounded up.
   *
   * A descriptor that fails to build throws rather than returning null, so a broken tool fails
   * the generator instead of reading as hidden and dropping out of the totals.
   */
  internal fun llmDescriptionTokens(detail: ToolDetail?): Int? {
    val texts: List<String> = when (detail) {
      null -> return null
      is ToolDetail.ClassBacked -> {
        val descriptor = detail.kclass.toKoogToolDescriptor() ?: return null
        // Data-class params lower to nested objects whose field descriptions the LLM also receives.
        listOf(descriptor.description) +
          (descriptor.requiredParameters + descriptor.optionalParameters).flatMap { it.descriptionTexts() }
      }
      is ToolDetail.Scripted -> {
        val config = detail.config
        val metaSurface = (config.meta?.get("trailblaze/surfaceToLlm") as? JsonPrimitive)?.booleanOrNull ?: true
        if (!config.surfaceToLlm || !metaSurface) return null
        // The runtime's own projection (`toKoogToolDescriptor(strict = false)`): an array or object
        // param keeps its element / nested field descriptions, which the LLM receives too.
        val descriptor = LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor(config)
          .toKoogToolDescriptorForTrailblazeDescriptor(strict = false)
        listOfNotNull(config.description) +
          (descriptor.requiredParameters + descriptor.optionalParameters).flatMap { it.descriptionTexts() }
      }
    }
    val chars = texts.sumOf { it.length }
    return (chars + 3) / 4
  }

  private fun ToolParameterDescriptor.descriptionTexts(): List<String> = listOf(description) + type.nestedDescriptionTexts()

  private fun ToolParameterType.nestedDescriptionTexts(): List<String> = when (this) {
    is ToolParameterType.Object -> properties.flatMap { it.descriptionTexts() }
    is ToolParameterType.List -> itemsType.nestedDescriptionTexts()
    is ToolParameterType.AnyOf -> types.flatMap { it.descriptionTexts() }
    else -> emptyList()
  }

  /**
   * Drivers this scripted tool applies to, derived from `_meta["trailblaze/supportedPlatforms"]`
   * and `_meta["trailblaze/supportedDrivers"]`.
   *
   * The metadata key is set by the trailmap loader from the descriptor's `supportedPlatforms:`
   * field. Values are platform names \u2014 `"android"`, `"ios"`, `"web"`, or `"compose"` \u2014
   * matching the lowercase enum names on [xyz.block.trailblaze.devices.TrailblazeDevicePlatform].
   *
   * When the metadata is missing or empty, scope falls back to [allDrivers] (every driver
   * the target supports). When present, the returned set is the subset of [allDrivers]
   * whose `platform` is named in the list. If
   * the list contains a platform the target doesn't actually support, that platform is just
   * silently absent from the result \u2014 the doc accurately reflects "where this tool could
   * fire given the target's driver matrix" rather than what the descriptor wishfully claims.
   */
  private fun driversForScriptedTool(
    inlineScript: InlineScriptToolConfig,
    allDrivers: Set<TrailblazeDriverType>,
  ): Set<TrailblazeDriverType> {
    val supportedPlatforms = readMetaList(inlineScript.meta, "trailblaze/supportedPlatforms").orEmpty()
    // `supportedDrivers` narrows further, the same way the runtime gate reads it: a tool declaring
    // `[ios-host]` is not registered in an `ios-axe` session even though both are iOS.
    val supportedDrivers = readMetaList(inlineScript.meta, "trailblaze/supportedDrivers").orEmpty()
    return allDrivers.filterTo(linkedSetOf()) { dt ->
      (supportedPlatforms.isEmpty() || dt.platform.name.lowercase() in supportedPlatforms) &&
        (supportedDrivers.isEmpty() || dt.yamlKey.lowercase() in supportedDrivers)
    }
  }

  /**
   * Parses `meta[key]` as a list of lowercase names, or returns null when the key is absent /
   * malformed. Malformed in-line entries (non-string array members) get silently dropped; the doc
   * still renders against the remaining valid entries rather than failing the whole generator run
   * on a typo in one descriptor's metadata.
   */
  private fun readMetaList(meta: JsonObject?, key: String): Set<String>? {
    val raw = meta?.get(key) ?: return null
    val array: JsonArray = runCatching { raw.jsonArray }.getOrNull() ?: return null
    return array.mapNotNullTo(linkedSetOf()) { element ->
      runCatching { element.jsonPrimitive.content.lowercase() }.getOrNull()
    }
  }

  companion object {
    /** The index: every target and trailmap file, and the per-driver token totals. */
    const val INDEX_FILE_NAME = "README.md"
    internal const val GENERATED_HEADER = "<!-- GENERATED FILE. DO NOT EDIT. Regenerate with: ./gradlew :internal-docs-generator:run -->"
    private val TRAILMAP_IN_PATH = Regex("""(?:^|/)trailmaps/([^/]+)/""")
  }
}
