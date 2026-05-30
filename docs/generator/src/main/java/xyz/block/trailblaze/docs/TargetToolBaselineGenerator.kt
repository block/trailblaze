package xyz.block.trailblaze.docs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.config.AppTargetCompanion
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ResolvedToolSet
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolSetYamlLoader
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.toolcalls.ResolvedTargetIdempotentWrite
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer.Header
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer.ToolDetail
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.toolName
import java.io.File

/**
 * Generates per-target documentation under `docs/internal/targets/`. Two artifacts per target:
 *
 *   1. **`TARGET_<id>.md`** — the matrix view: every tool in the resolved toolbox shown in
 *      alphabetical order with toolset membership and a per-driver availability column
 *      (✅ / ❌). Checked into the repo and diffed in CI so any toolset/target YAML change
 *      that affects the surface is immediately visible.
 *   2. **`<id>/tools/<toolName>.md`** — per-tool sidecar (one file per tool in the matrix)
 *      rendered by [ResolvedTargetToolDetailRenderer], the same renderer the workspace
 *      `trailblaze check` command emits per-target sidecars from. Sharing the renderer
 *      across the workspace, OSS, and internal pipelines is the explicit dogfooding contract:
 *      any gap in tool metadata shows up identically everywhere, so we feel it once and fix
 *      it for everyone.
 *
 * The matrix's tool-name cells are Markdown links into the sidecar tree, so a reviewer can
 * drill from "what's available on Android Accessibility?" into "what are this tool's
 * parameters?" without leaving the docs.
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
   * scripted-tool `script:` paths as repo-relative strings in the sidecar; without
   * it the runtime-resolved absolute path would leak into the committed file and
   * churn across machines.
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
     * so scripted-tool sidecars render `./tools/foo.ts` instead of a per-machine
     * absolute path. Null is permitted but produces less-portable sidecars.
     */
    val trailmapDir: File?,
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
    // duplicate id between the two sources is a hard failure — both sources would
    // emit the same `TARGET_<id>.md` and the second `writeIfChanged` call would
    // silently overwrite the first. Naming both contributors in the error keeps the
    // failure diagnosable instead of leaving the author to wonder why one
    // target's matrix disappeared.
    val configsById = LinkedHashMap<String, AppTargetYamlConfig>()
    classpathConfigs.forEach { configsById[it.id] = it }
    val trailmapDirsByTargetId = LinkedHashMap<String, File>()
    additionalTargets.forEach { addition ->
      val previous = configsById.put(addition.config.id, addition.config)
      require(previous == null) {
        "Target id '${addition.config.id}' is declared by both a classpath-bundled " +
          "target and a workspace-resolved additional target. Rename one — duplicate " +
          "ids would emit colliding TARGET_<id>.md files."
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

    val yamlDefinedByName: Map<String, ToolYamlConfig> =
      ToolYamlLoader.discoverYamlDefinedTools(classpathOnly)
        .mapKeys { it.key.toolName }

    for (target in targets.sortedBy { it.id }) {
      val config = configsById[target.id]
      val markdown = generateTargetMarkdown(
        target = target,
        allToolSets = toolSets,
        config = config,
        targetsDir = targetsDir,
        resolver = resolver,
        yamlDefinedByName = yamlDefinedByName,
        trailmapDir = trailmapDirsByTargetId[target.id],
      )
      ResolvedTargetIdempotentWrite.writeIfChanged(
        File(targetsDir, "TARGET_${target.id}.md"),
        markdown,
      )
    }
  }

  private fun generateTargetMarkdown(
    target: TrailblazeHostAppTarget,
    allToolSets: Map<String, ResolvedToolSet>,
    config: AppTargetYamlConfig?,
    targetsDir: File,
    resolver: ToolNameResolver,
    yamlDefinedByName: Map<String, ToolYamlConfig>,
    trailmapDir: File?,
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

    // From target-level scripted tools (`target.tools:` — the `List<InlineScriptToolConfig>`).
    // This is the trailmap-authoring path: each `<trailmap>/tools/<name>.yaml` descriptor is
    // resolved by the trailmap loader into one [InlineScriptToolConfig] entry, with the tool
    // name already extracted from the descriptor's `name:` field.
    //
    // Scope: target-root by default, narrowed to the drivers matching the descriptor's
    // `supportedPlatforms:` list (carried through to runtime as
    // `_meta["trailblaze/supportedPlatforms"]`). A descriptor that declares
    // `supportedPlatforms: [android]` is restricted to Android drivers in the table;
    // omitting the field defaults to every driver the target supports.
    //
    // Label as `script:<filename>` — "this tool came from a JS/TS module," with the
    // filename being the `.ts`/`.js` script the descriptor's `script:` points at.
    val scriptedToolsByName = mutableMapOf<String, InlineScriptToolConfig>()
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
      scriptedToolsByName[toolName] = inlineScript
    }

    // Emit per-tool sidecars under `<targetsDir>/<id>/tools/<name>.md` and remember which
    // names produced a sidecar so the matrix can render them as Markdown links. Tools whose
    // metadata isn't reachable get no link — the matrix renders the bare name, which is the
    // honest "no metadata available" signal the renderer contracts elsewhere.
    val sidecarsWritten = writeToolSidecars(
      targetId = target.id,
      targetsDir = targetsDir,
      toolNames = toolEntries.keys,
      resolver = resolver,
      yamlDefinedByName = yamlDefinedByName,
      scriptedToolsByName = scriptedToolsByName,
      trailmapDir = trailmapDir,
    )

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
      val toolCell = if (toolName in sidecarsWritten) {
        "[$toolName](${target.id}/tools/$toolName.md)"
      } else {
        toolName
      }
      appendLine("| $toolCell | $tsLabel | ${cells.joinToString(" | ")} |")
    }
    appendLine()

    appendLine(DocsGenerator.THIS_DOC_IS_GENERATED_MESSAGE)
  }

  /**
   * For each tool name in [toolNames] that we can classify (class-backed via [resolver],
   * YAML-defined via [yamlDefinedByName], or scripted via [scriptedToolsByName]), emit a
   * Markdown sidecar under `<targetsDir>/<targetId>/tools/<name>.md` using the shared
   * [ResolvedTargetToolDetailRenderer]. Returns the set of names that got a sidecar so the
   * matrix renderer knows which cells to linkify.
   *
   * Also prunes stale emitter-owned sidecar files (recognized by the
   * [ResolvedTargetToolDetailRenderer.GENERATED_BANNER] first line) whose tool no longer
   * appears in the matrix — keeps the dogfood pipeline in lockstep with what the workspace
   * `trailblaze check` emitter does for renamed/removed tools.
   */
  private fun writeToolSidecars(
    targetId: String,
    targetsDir: File,
    toolNames: Set<String>,
    resolver: ToolNameResolver,
    yamlDefinedByName: Map<String, ToolYamlConfig>,
    scriptedToolsByName: Map<String, InlineScriptToolConfig>,
    trailmapDir: File?,
  ): Set<String> {
    val sidecarDir = File(targetsDir, "$targetId/tools").apply { mkdirs() }
    val written = mutableSetOf<String>()
    val keepNames = mutableSetOf<String>()
    for (toolName in toolNames.sorted()) {
      val detail = buildToolDetail(toolName, targetId, resolver, yamlDefinedByName, scriptedToolsByName, trailmapDir)
        ?: continue
      val sidecarFile = File(sidecarDir, "$toolName.md")
      val markdown = ResolvedTargetToolDetailRenderer.renderMarkdown(detail = detail, header = INTERNAL_HEADER)
      // Path-traversal guard — see ResolvedTargetReportEmitter for the rationale; same
      // contract here so a misvalidated internal-target tool name can't escape the
      // sidecar directory.
      ResolvedTargetIdempotentWrite.writeSidecarIfChanged(sidecarDir, sidecarFile, markdown)
      written += toolName
      keepNames += sidecarFile.name
    }
    pruneStaleSidecars(sidecarDir, keepNames)
    return written
  }

  private fun buildToolDetail(
    toolName: String,
    targetId: String,
    resolver: ToolNameResolver,
    yamlDefinedByName: Map<String, ToolYamlConfig>,
    scriptedToolsByName: Map<String, InlineScriptToolConfig>,
    trailmapDir: File?,
  ): ToolDetail? {
    resolver.resolveOrNull(toolName)?.let { kclass ->
      return ToolDetail.ClassBacked(name = toolName, kclass = kclass)
    }
    yamlDefinedByName[toolName]?.let { config ->
      return ToolDetail.YamlDefined(name = toolName, config = config)
    }
    scriptedToolsByName[toolName]?.let { scripted ->
      return ToolDetail.Scripted(
        name = toolName,
        config = scripted,
        originTrailmapId = targetId,
        consumerTrailmapId = targetId,
        // Passing the trailmap dir (when known) lets `ResolvedTargetToolDetailRenderer.renderScriptPath`
        // express the script as `./tools/foo.ts` instead of the runtime-resolved absolute path
        // that would otherwise leak per-machine state into the committed sidecar.
        originTrailmapDir = trailmapDir,
      )
    }
    return null
  }

  // Visible to tests so a unit test can pin the banner-vs-hand-authored distinction
  // without needing the full classpath-discovery + companion machinery `generate()`
  // depends on.
  internal fun pruneStaleSidecars(sidecarDir: File, keepNames: Set<String>) {
    if (!sidecarDir.isDirectory) return
    val candidates = sidecarDir.listFiles { f ->
      f.isFile && f.name.endsWith(".md") && f.name !in keepNames
    } ?: return
    for (file in candidates) {
      val firstLine = runCatching { file.bufferedReader().use { it.readLine().orEmpty() } }.getOrNull()
        ?: continue
      if (firstLine == ResolvedTargetToolDetailRenderer.GENERATED_BANNER) file.delete()
    }
  }

  /**
   * Drivers this scripted tool applies to, derived from `_meta["trailblaze/supportedPlatforms"]`.
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
    private val INTERNAL_HEADER = Header(
      origin = "Internal per-target tool reference",
      regenerateHint = "Regenerate with: ./gradlew :internal-docs-generator:run",
    )
  }
}
