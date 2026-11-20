package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.KnownTargetMessages
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.McpToolNames
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
import xyz.block.trailblaze.scripting.InProcessScriptedToolLauncher
import xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration
import xyz.block.trailblaze.scripting.ScriptedToolCatalog
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.scripting.mcp.shouldRegisterForPlatform
import xyz.block.trailblaze.toolcalls.CallableOnlyFromToolsException
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.isCallableOnlyFromTools
import xyz.block.trailblaze.toolcalls.ToolNameSuggestions
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.getExcludedToolSurfaceForDriver
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterVisibility
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.toolcalls.toTrailblazeToolDescriptorIgnoringLlmSurface
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import xyz.block.trailblaze.toolcalls.toTrailblazeToolDescriptorWithSource
import xyz.block.trailblaze.toolcalls.trailblazeToolSourceForScript
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

/**
 * The default registry lookup: one [ToolNameResolver] per tool set, built on first use.
 *
 * Building a resolver scans the classpath for tool YAML, so it must not happen per lookup, and it
 * must not happen at construction either — most `toolbox()` calls never reach a registry lookup,
 * and the tool set is constructed on every MCP session.
 */
private fun memoizedRegistryLookup(
  resourceSourceProvider: () -> ConfigResourceSource,
): (String) -> KClass<out TrailblazeTool>? {
  val resolver = lazy { ToolNameResolver.fromBuiltInAndCustomTools(resourceSource = resourceSourceProvider()) }
  return { name ->
    val exact = resolver.value.resolveOrNull(name)
    // [ToolNameResolver.resolveOrNull] is an exact map lookup, which is what a trail's YAML decode
    // needs. A lookup is the other case: the catalogue half of this same query already matches
    // case-insensitively, so without this `TAPON` is described as offered-by-nobody or as
    // not-found depending purely on which half answered.
    exact ?: resolver.value.allKnownNames()
      .firstOrNull { it.equals(name, ignoreCase = true) }
      ?.let { resolver.value.resolveOrNull(it) }
  }
}

/**
 * MCP tool for discovering available Trailblaze tools.
 *
 * Provides a structured index of all tools organized by category (platform toolsets)
 * and target app (custom tools). Agents use this to understand what actions are possible
 * before constructing tool call sequences.
 *
 * This is pure discovery -- no execution. Works even without a device connected
 * (shows what WOULD be available).
 */
@Suppress("unused")
class ToolDiscoveryToolSet(
  private val sessionContext: TrailblazeMcpSessionContext? = null,
  private val allTargetAppsProvider: () -> Set<TrailblazeHostAppTarget> = { emptySet() },
  private val currentTargetProvider: () -> TrailblazeHostAppTarget? = { null },
  private val currentDriverTypeProvider: () -> TrailblazeDriverType? = { null },
  /**
   * The connected device's classifiers, for tools gated on `trailblaze/requiredClassifiers`.
   * Consulted only when listing for [currentDriverTypeProvider]'s driver — a listing for another
   * platform isn't about this device. `null` = unknown, which lists a gated tool rather than hiding
   * one that may apply.
   */
  private val currentDeviceClassifiersProvider: () -> List<TrailblazeDeviceClassifier>? = { null },
  /**
   * Resource source used to discover `*.trailhead.yaml` configs for role
   * enrichment. Injected by the host bootstrap when a workspace config dir is in play (so a
   * workspace-authored trailhead can appear in `trailheadTools`); falls back to the platform's
   * classpath scan otherwise — same default as [ToolYamlLoader.discoverTrailheads].
   *
   * This provider exists purely so the host can layer in the same workspace `ConfigResourceSource`
   * it already uses for target / toolset discovery. When the host doesn't thread one through, the
   * server still resolves all classpath-bundled role tools correctly — the gap is only for
   * workspace-authored role YAMLs that aren't on the classpath.
   */
  private val resourceSourceProvider: () -> ConfigResourceSource = { platformConfigResourceSource() },
  /**
   * Looks a bare tool name up in the global tool registry — the same namespace the YAML decoder
   * resolves a recorded trail's steps against — returning the backing class or null.
   *
   * Separate from the toolset catalogue on purpose: the catalogue answers "what is on offer",
   * this answers "does this name mean anything at all". Injected so a test can pin either answer
   * without building a real registry, which scans the classpath.
   *
   * Expected to match case-insensitively, as the catalogue half of a name lookup does — otherwise
   * `TAPON` and `tapOn` get different verdicts from the same query.
   */
  private val knownToolClassProvider: (String) -> KClass<out TrailblazeTool>? =
    memoizedRegistryLookup(resourceSourceProvider),
  /**
   * Builds the scripted-tool descriptor index one [toolbox] call reads. Every mode describes
   * scripted tools for many (target, platform) pairs, and each description used to walk the whole
   * descriptor tree again — a name lookup on a daemon with a dozen targets walked it about forty
   * times. One catalog per call means one walk per call; a new call gets a new catalog so a
   * workspace edit is visible on the next request. Injected so a test can count the walks.
   */
  private val scriptedToolCatalogFactory: () -> ScriptedToolCatalog = { ScriptedToolCatalog() },
) : ToolSet {

  @LLMDescription(
    """
    Discover available Trailblaze tools.

    toolbox() → index of all tool categories and available targets
    toolbox(detail=true) → full parameter details for every tool
    toolbox(name="tap") → single tool with full descriptor
    toolbox(target="sampleapp") → tools for a specific target app
    toolbox(all=true) → also tools the in-session LLM is not offered; call one with a direct step(tools=...) call, a trail step, or a TS tool

    Use this to understand what actions are possible before calling step().
    """
  )
  @Tool(McpToolNames.TOOL_TOOLS)
  suspend fun toolbox(
    @LLMDescription("Filter to a single tool by name") name: String? = null,
    @LLMDescription("Filter to a specific target app's tools") target: String? = null,
    @LLMDescription("Substring search on tool name and description.") search: String? = null,
    @LLMDescription("Target device (e.g. android, android/emulator-5554).") platform: String? = null,
    @LLMDescription("Expand tools with full parameter descriptions") detail: Boolean? = null,
    @LLMDescription(
      "Also list tools the in-session LLM is not offered (hidden by the tool, or excluded by the " +
        "target). Call one with a direct step(tools=...) call, a trail step, or a TS tool " +
        "(ctx.tools.<name>), except a target's own scripted tool that it also drops, which is never started.",
    )
    all: Boolean? = null,
  ): String {
    val platformFilter = platform?.let { TrailblazeDevicePlatform.fromString(it) }
    // "default" in index mode means "show only platform tools, no target tools"
    val isDefaultTarget = target?.equals(DefaultTrailblazeHostAppTarget.id, ignoreCase = true) == true
    // Distinguish "no --platform" from "invalid --platform" so a typo (`--device=androd`) doesn't
    // silently degrade to the daemon's current driver — that's the exact failure mode the
    // `--device` override was added to prevent. The error is returned in the SAME mode-specific
    // envelope the call would have produced on success, so the CLI's per-mode formatter (and any
    // MCP client doing structural typing) sees a result shape that matches the query type rather
    // than a generic IndexResult-as-fallback.
    if (platform != null && platformFilter == null) {
      // Use `visibleEntries` so the hidden DESKTOP platform doesn't leak into the user-facing
      // error message — keeps the surface aligned with `device list` and target dropdowns.
      val accepted = TrailblazeDevicePlatform.visibleEntries.joinToString(", ") { it.name.lowercase() }
      val msg = "Unknown platform '$platform'. Accepted values: $accepted."
      return when {
        name != null -> jsonFormat.encodeToString(ToolDiscoveryNameResult(error = msg))
        search != null -> jsonFormat.encodeToString(ToolDiscoverySearchResult(error = msg))
        target != null && !isDefaultTarget -> jsonFormat.encodeToString(ToolDiscoveryTargetResult(error = msg))
        else -> jsonFormat.encodeToString(ToolDiscoveryIndexResult(error = msg))
      }
    }
    // One descriptor walk for everything this call describes — see [scriptedToolCatalogFactory].
    val catalog = scriptedToolCatalogFactory()
    val includeNotOffered = all ?: false
    return when {
      // NAME mode intentionally spans all platforms — looking up a tool by exact name is "find me
      // this tool wherever it's defined" rather than "what's runnable on the current device." The
      // result's `foundInCategories` / `foundInTargets` already tell the caller which platforms
      // the tool lives on. `target` / `platform` only scope the note on whether the LLM is offered it.
      name != null -> handleNameMode(name, target, platformFilter, catalog)
      search != null -> handleSearchMode(search, target, platformFilter, catalog, includeNotOffered)
      target != null && !isDefaultTarget ->
        handleTargetMode(target, detail ?: false, platformFilter, catalog, includeNotOffered)
      else -> handleIndexMode(
        detail ?: false,
        platformFilter,
        suppressTargetTools = isDefaultTarget,
        catalog = catalog,
        includeNotOffered = includeNotOffered,
      )
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Mode handlers
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Index-mode handler — renders the platform-wide catalogue plus, optionally, the currently
   * bound target's tool groups.
   *
   * The [suppressTargetTools] flag is the "platform tools only" signal that the dispatcher sets
   * when the caller explicitly passed `target="default"` (the [DefaultTrailblazeHostAppTarget]
   * sentinel — see the `isDefaultTarget` check in [toolbox]). When `true`:
   *  - [buildTargetToolsets] is skipped (no per-target groups).
   *  - The `systemPrompt` field is forced to `null` (a target-specific prompt above a
   *    platform-only catalogue would mislead the caller about the scope of what was returned).
   * When `false` (the normal index entry from `toolbox()` with no `target` argument), the
   * current target's tools and system prompt flow through normally.
   */
  private fun handleIndexMode(
    detail: Boolean,
    platformFilter: TrailblazeDevicePlatform? = null,
    suppressTargetTools: Boolean = false,
    catalog: ScriptedToolCatalog,
    includeNotOffered: Boolean = false,
  ): String {
    val currentTarget = currentTargetProvider()
    val currentDriverType = currentDriverTypeProvider()
    // Header platform: CLI flag wins over the connected device's platform.
    val effectivePlatform = platformFilter ?: currentDriverType?.platform
    // Resolve a driver type for filtering. When the user explicitly passes `--device=<platform>`
    // and it disagrees with the daemon's currently-connected driver (e.g. a web playwright session
    // is live but the user asked about android), the CLI flag wins — otherwise the listing would
    // contradict the header (`(Android)` over a list of web tools). When the platforms agree, keep
    // the specific connected driver so on-device instrumentation vs accessibility distinctions are
    // preserved.
    val effectiveDriverType = resolveEffectiveDriverType(currentDriverType, platformFilter)
    logIfDriverWasOverridden(currentDriverType, platformFilter, effectiveDriverType)
    val allTargets = allTargetAppsProvider()

    // A platform-only listing (explicit `target="default"`) is scoped to the default target, not the
    // daemon's selected one: filter and report it that way, and list the selected target among the others.
    // Prefer the loaded default target (e.g. `default.yaml`) so its own `excluded_tools` apply.
    val listedTarget = if (suppressTargetTools) {
      allTargets.find { it.id == DefaultTrailblazeHostAppTarget.id } ?: DefaultTrailblazeHostAppTarget
    } else {
      currentTarget
    }
    val allPlatformToolsets = buildPlatformToolsets(
      detail, getTargetExcludedToolNames(listedTarget, effectiveDriverType), effectiveDriverType, catalog,
    ).withoutRepeatedNotOffered()
    val allTargetToolsets =
      if (suppressTargetTools) null else buildTargetToolsets(currentTarget, effectiveDriverType, detail, catalog)
        ?.withoutRepeatedNotOffered { it.name.substringBefore("/") }
    // Counted over the listed target only: detail mode lists every target's groups, and the note is
    // about what THIS target's session is not offered.
    val notOfferedCount = notOfferedToolCount(
      allPlatformToolsets,
      allTargetToolsets?.filter { ts ->
        // Device-connected groups are `<id>/<group>`; driver-less ones are `<id> (<platform>)`.
        currentTarget != null && (ts.name.startsWith("${currentTarget.id}/") || ts.name.startsWith("${currentTarget.id} ("))
      },
    )
    val platformToolsets = allPlatformToolsets.shown(includeNotOffered)
    val targetToolsets = allTargetToolsets?.shown(includeNotOffered)?.ifEmpty { null }
    // Other-targets hint: when the target listing is scoped to the current target
    // (!includeAllTargetsInIndex), the rest of the catalogue IS this name list — always emit it.
    // Detail mode lists every target's tools inline, so the hint is only needed when that
    // listing came back empty (legacy behavior).
    val otherTargets = if (targetToolsets == null || !includeAllTargetsInIndex(detail)) {
      buildOtherTargets(listedTarget, allTargets)
    } else {
      null
    }

    // Enrich the response with a slim trailheads view. Trailheads come from two sources — see
    // computeTrailheadNames: YAML-side `*.trailhead.yaml` metadata, and scripted
    // (.ts) tools that self-declare `trailhead:` inline in their spec (InlineScriptToolConfig.trailhead).
    // Filtered to tool names actually surfaced in the current platform / target toolsets so the
    // role lists never reference tools the CLI can't show details for.
    val trailheadToolNames =
      computeTrailheadNames(platformToolsets, targetToolsets, target = currentTarget)

    val result = ToolDiscoveryIndexResult(
      currentTarget = listedTarget?.id,
      currentPlatform = effectivePlatform?.displayName,
      currentDriverType = effectiveDriverType?.yamlKey,
      // Skip the system prompt when the caller explicitly asked for the "default" sentinel
      // (platform-only listing) — the prompt is target-specific guidance, and a callsite
      // that scoped its query to platform tools (e.g. `RecordingToolDiscovery` which uses
      // `target=default` for its first probe, or an explicit `trailblaze toolbox --target
      // default`) would be misled by app-specific instructions for a target whose tools
      // were intentionally excluded. `systemPromptForTarget` enforces the same rule when
      // `currentTarget` happens to be the default sentinel itself (e.g. `trailblaze config
      // target default`).
      systemPrompt = if (suppressTargetTools) null else currentTarget?.let(::systemPromptForTarget),
      platformToolsets = platformToolsets,
      targetToolsets = targetToolsets,
      otherTargets = otherTargets,
      usage = "These tools are used automatically by blaze(objective=\"...\"). " +
        "For direct execution: blaze(objective=\"description\", tools=\"- toolName:\\n    param: value\"). " +
        "For tool details: toolbox(name=\"toolName\").",
      trailheadTools = trailheadToolNames,
      notOfferedToolCount = notOfferedCount.takeIf { it > 0 },
      notOfferedNote = notOfferedNote(notOfferedCount, includeNotOffered),
      includesNotOffered = true.takeIf { includeNotOffered },
    )
    return jsonFormat.encodeToString(result)
  }

  /**
   * Returns trailhead tool names filtered to tool names that appear in the in-scope
   * toolsets. The intersection guard means the CLI's "render trailheads grouped" pass never
   * names a tool the user can't drill into via `toolbox --name <id>`.
   *
   * Accepts a heterogeneous list of toolset sources so both [handleIndexMode] (platform +
   * target toolsets) and [handleTargetMode] (toolGroups, or flat toolsByPlatform) can share the
   * intersection logic. Each source contributes its in-scope tool ids via either the compact
   * `tools` list or the detailed `toolDetails` list — the helper accepts whichever shape the
   * caller has on hand.
   *
   * Trailhead names are the union of two sources: YAML-side `*.trailhead.yaml` metadata (via
   * [ToolYamlLoader.discoverTrailheads]), and scripted (`.ts`) tools that self-declare
   * `trailhead:` inline in their `TrailblazeTypedToolSpec` instead of a sidecar YAML — those land
   * on [xyz.block.trailblaze.config.InlineScriptToolConfig.trailhead], reachable off [target]'s
   * [xyz.block.trailblaze.model.TrailblazeHostAppTarget.getInlineScriptTools].
   *
   * @param target the app target whose scripted tools to scan for inline trailheads.
   *   [handleIndexMode] passes the daemon's currently-connected target; [handleTargetMode] passes
   *   the explicitly-requested target. No default — every caller must decide, so a future call
   *   site can't silently inherit the wrong target for its mode (`toolbox trailheads
   *   --target=<other>` reporting the daemon's connected target's scripted trailheads instead of
   *   the requested one).
   */
  private fun computeTrailheadNames(
    vararg toolsetSources: List<ToolDiscoveryToolsetInfo>?,
    extraInScopeNames: Collection<String> = emptyList(),
    target: TrailblazeHostAppTarget?,
  ): List<String> {
    val inScopeNames: Set<String> = buildSet {
      toolsetSources.forEach { sets ->
        sets?.forEach { ts ->
          ts.tools?.let { addAll(it) }
          ts.toolDetails?.forEach { add(it.name) }
        }
      }
      addAll(extraInScopeNames)
    }
    // Use the injected resource source so a workspace-authored *.trailhead.yaml is also
    // visible — otherwise we'd silently drop workspace trailheads that ARE present in the
    // in-scope toolsets (because the host's target/toolset discovery uses a layered source).
    val yamlTrailheadNames = ToolYamlLoader.discoverTrailheads(resourceSourceProvider()).values.map { it.id }
    val scriptedTrailheadNames = target?.getInlineScriptTools()
      .orEmpty()
      .filter { it.trailhead != null }
      .map { it.name }
    return (yamlTrailheadNames + scriptedTrailheadNames)
      .filter { it in inScopeNames }
      .distinct()
      .sorted()
  }

  private fun handleNameMode(
    name: String,
    targetId: String?,
    platformFilter: TrailblazeDevicePlatform?,
    catalog: ScriptedToolCatalog,
  ): String {
    val allTargets = allTargetAppsProvider()
    // `target` only scopes the verdict on whether the LLM is offered the tool. An id nothing has
    // loaded would silently judge it against the current target instead, so say so.
    val namedTarget = targetId?.let { id ->
      allTargets.firstOrNull { it.id.equals(id, ignoreCase = true) }
        ?: DefaultTrailblazeHostAppTarget.takeIf { it.id.equals(id, ignoreCase = true) }
        ?: return jsonFormat.encodeToString(ToolDiscoveryNameResult(error = unknownTargetMessage(id, allTargets)))
    }

    // Search all platform tools
    val allPlatformTools = DISCOVERABLE_CATEGORIES.flatMap { category ->
      getToolDescriptorsForCategory(category, catalog).map { descriptor ->
        descriptor to category
      }
    }

    // Search current target tools
    val currentTarget = currentTargetProvider()
    val currentDriverType = currentDriverTypeProvider()
    val currentListed = if (currentTarget != null && currentDriverType != null) {
      listCustomTools(currentTarget, currentDriverType, catalog)
    } else {
      emptyList()
    }
    val targetTools = currentListed.map { it.descriptor to currentTarget!!.id }

    // Search all other target tools
    val allTargetTools = allTargets.flatMap { appTarget ->
      TrailblazeDevicePlatform.entries.flatMap { platform ->
        getCustomToolDescriptorsForPlatform(appTarget, platform, catalog, includeHidden = true).map { descriptor ->
          descriptor to appTarget.id
        }
      }
    }

    val allTools = allPlatformTools + targetTools + allTargetTools

    val match = allTools.firstOrNull { (descriptor, _) ->
      descriptor.name.equals(name, ignoreCase = true)
    }

    if (match == null) {
      // The catalogue above is what this device and target OFFER. A name can be a real tool and
      // still be absent from it — `tapOn` is class-backed, has a `.tool.yaml`, and is what the
      // recorder writes when it upgrades a coordinate tap, but no toolset lists it. Denying those
      // names leaves a reader holding a recorded trail that the CLI says contains a tool which
      // does not exist. Answer with the tool and say where it stands instead.
      registryOnlyDescriptor(name)?.let { descriptor ->
        val standing = toolSetStanding(descriptor.name)
        return jsonFormat.encodeToString(
          ToolDiscoveryNameResult(
            tool = descriptor,
            availability = standing.availability,
            usage = when (standing.door) {
              ToolDoor.AGENT -> buildUsageHint(descriptor)
              ToolDoor.SESSION -> buildSessionToolUsageHint(descriptor)
              ToolDoor.DIRECT -> buildNotOfferedUsageHint(descriptor)
              ToolDoor.TOOL_ONLY -> buildToolOnlyUsageHint(descriptor)
              ToolDoor.TRAIL -> buildTrailUsageHint(descriptor, standing.runsOnlyOnDrivers)
            },
          )
        )
      }
      return jsonFormat.encodeToString(
        ToolDiscoveryNameResult(
          error = unknownToolLookupMessage(name, allTools.map { (descriptor, _) -> descriptor.name }),
        )
      )
    }

    val (descriptor, _) = match
    val foundInCategories = allPlatformTools
      .filter { (d, _) -> d.name == descriptor.name }
      .map { (_, cat) -> cat.displayName }
    val foundInTargets = (targetTools + allTargetTools)
      .filter { (d, _) -> d.name == descriptor.name }
      .map { (_, targetId) -> targetId }
      .distinct()

    // Found is not the same as offered: the lookup above includes tools the in-session LLM never
    // sees, so say which, and why, before the usage hint tells the reader to `blaze(...)` it.
    val scopeTarget = namedTarget ?: currentTarget
    val scopeDriver = resolveEffectiveDriverType(currentDriverType, platformFilter)
    // The current target's listing was walked above; reuse it when that is the scope asked about.
    val scopeListed = currentListed.takeIf { scopeTarget == currentTarget && scopeDriver == currentDriverType }
    val notOffered = notOfferedReasonFor(descriptor.name, scopeTarget, scopeDriver, catalog, scopeListed)

    return jsonFormat.encodeToString(
      ToolDiscoveryNameResult(
        tool = descriptor,
        foundInCategories = foundInCategories.ifEmpty { null },
        foundInTargets = foundInTargets.ifEmpty { null },
        availability = notOffered?.let { notOfferedAvailability(descriptor.name, it, scopeTarget, scopeDriver) },
        notOffered = notOffered,
        usage = if (notOffered == null) buildUsageHint(descriptor) else buildNotOfferedUsageHint(descriptor),
      )
    )
  }

  /**
   * Why the in-session LLM is not offered [toolName] on [target] / [driverType], or null when it is
   * (or when that can't be told). The tool hiding itself wins over the target excluding it: it is
   * off the menu on every target, and that is the more useful thing to know. [targetListed] is
   * [target]'s own listing on [driverType] when the caller already has it.
   *
   * With no driver (no device bound, no `platform`), [target]'s whole surface — platform tools
   * included — is judged on every driver a run of it can reach: offered when any of them offers it.
   */
  private fun notOfferedReasonFor(
    toolName: String,
    target: TrailblazeHostAppTarget?,
    driverType: TrailblazeDriverType?,
    catalog: ScriptedToolCatalog,
    targetListed: List<ListedTool>? = null,
  ): ToolNotOfferedReason? {
    val drivers = when {
      driverType != null -> listOf(driverType)
      target != null -> reachableDrivers(target)
      else -> emptyList()
    }
    val marks = drivers.flatMap { driver ->
      scopedMarks(target, driver, catalog, targetListed.takeIf { driver == driverType })
        .filter { (name, _) -> name.equals(toolName, ignoreCase = true) }
    }
    if (marks.isNotEmpty()) return if (marks.any { (_, reason) -> reason == null }) null else marks.first().second

    // Not in this scope's listing (another target's tool, or no device): only the tool's own flag applies.
    return if (hiddenFromLlmAnywhere(toolName, catalog)) ToolNotOfferedReason.HIDDEN_BY_TOOL else null
  }

  /**
   * Every tool [target]'s listing on [driverType] carries, platform and target-owned alike, with
   * its not-offered mark — the same marks that listing shows, so a lookup agrees with it exactly.
   */
  private fun scopedMarks(
    target: TrailblazeHostAppTarget?,
    driverType: TrailblazeDriverType,
    catalog: ScriptedToolCatalog,
    targetListed: List<ListedTool>? = null,
  ): List<Pair<String, ToolNotOfferedReason?>> =
    buildPlatformToolsets(
      detail = false,
      targetExcludedToolNames = getTargetExcludedToolNames(target, driverType),
      driverType = driverType,
      catalog = catalog,
    ).flatMap { ts -> ts.toolDetails.orEmpty().map { it.name to ts.notOffered?.get(it.name) } } +
      (targetListed ?: target?.let { listCustomTools(it, driverType, catalog) }.orEmpty())
        .map { it.descriptor.name to it.notOffered }

  /**
   * The drivers a run of [target] can reach: live ones, on a platform it declares, and
   * `ANDROID_TEST` only with an in-process harness. A no-device listing merges exactly these.
   */
  private fun reachableDrivers(target: TrailblazeHostAppTarget): List<TrailblazeDriverType> =
    TrailblazeDriverType.entries
      .filter { target.getPossibleAppIdsForPlatform(it.platform) != null && it !in TrailblazeDriverType.RETIRED_DRIVERS }
      .filter { it != TrailblazeDriverType.ANDROID_TEST || target.getAndroidTestInstrumentationTarget() != null }

  /**
   * What a lookup naming a target nothing has loaded says back. A target declared as living in
   * another repo gets the clone pointer appended — without it the available-list reads as "pick
   * another one", which is never the right recovery.
   */
  private fun unknownTargetMessage(targetId: String, allTargets: Collection<TrailblazeHostAppTarget>): String {
    val elsewhere = KnownTargetMessages.unavailableTargetHint(targetId)?.let { "\n$it" }.orEmpty()
    return "Target '$targetId' not found. Available targets: ${allTargets.joinToString(", ") { it.id }}$elsewhere"
  }

  /** Whether [toolName] sets `surfaceToLlm: false` itself, in whichever backing defines it. */
  private fun hiddenFromLlmAnywhere(toolName: String, catalog: ScriptedToolCatalog): Boolean {
    if (!surfacedToAgents(toolName)) return true
    // The catalog rethrows a broken workspace's discovery failure; that is no reason to fail a lookup.
    val scripted = runCatching { catalog.descriptorsByName }.getOrNull()?.get(ToolName(toolName))
      ?.toolConfigs()
      ?.firstOrNull { it.name == toolName }
    if (scripted != null && !scripted.surfacesToLlm(scripted.toolMeta())) return true
    return allTargetAppsProvider().any { target ->
      target.getInlineScriptTools().any { it.name == toolName && !it.surfacesToLlm(it.toolMeta()) }
    }
  }

  /**
   * Usage for a tool found by name that the in-session LLM is not offered. [buildUsageHint]'s
   * objective-only `blaze(...)` goes through the agent, which does not have it, so the only `blaze`
   * form shown is a direct call that names the tool.
   */
  private fun buildNotOfferedUsageHint(descriptor: TrailblazeToolDescriptor): String =
    "Direct: blaze(objective=\"description\", tools=\"${toolYamlExample(descriptor)}\")\n" +
      "As a trail step:\n" +
      "${toolYamlExample(descriptor)}\n" +
      "From a TS tool: ctx.tools.${descriptor.name}(${toolArgsJsExample(descriptor)})"

  /**
   * Usage for a [xyz.block.trailblaze.toolcalls.TrailblazeToolClass.callableOnlyFromTools] tool:
   * the one door is another tool, so no trail or `blaze(tools=…)` form is shown.
   */
  private fun buildToolOnlyUsageHint(descriptor: TrailblazeToolDescriptor): String =
    "From a TS tool: ctx.tools.${descriptor.name}(${toolArgsJsExample(descriptor)})"

  /**
   * The descriptor for a name the global tool registry knows but no toolset offers, or `null` when
   * the name really is unknown.
   *
   * The registry here is the same one the YAML decoder resolves against, which is what makes the
   * distinction meaningful: a name it knows decodes into a real tool and executes when a recorded
   * trail replays it, so "not found" is the wrong answer even though the tool is not on offer.
   *
   * The descriptor is built ignoring `surfaceToLlm`, without which this returns null for exactly
   * the tools that land here — `tapOn` is hidden from the model on purpose, and hiding it from
   * the model is not a reason to deny it to someone who typed its name.
   *
   * [SYSTEM_INTERNAL_TOOLS] stays hidden. Those are the agent framework talking to itself, they
   * are excluded from every other discovery surface, and being in the registry is not a reason to
   * make one of them the exception.
   */
  private fun registryOnlyDescriptor(name: String): TrailblazeToolDescriptor? =
    knownToolClassProvider(name)
      ?.takeUnless { it in SYSTEM_INTERNAL_TOOLS }
      ?.toTrailblazeToolDescriptorIgnoringLlmSurface()

  /**
   * How a tool the catalogue above did not list can actually be invoked from here, which decides
   * the usage hint that goes with its availability note.
   */
  private enum class ToolDoor {
    /** The inner agent has it: `blaze(...)` and `trailblaze tool` both work. */
    AGENT,

    /**
     * The session's own tool list has it and the inner agent does not. `trailblaze tool` ends at
     * `step`, which is the inner agent's gate, so it refuses; the caller invokes the tool directly.
     */
    SESSION,

    /**
     * The session registers it but the inner agent is not offered it (`surfaceToLlm: false`): a
     * direct call (`blaze(tools=…)`, `trailblaze tool`) and a trail step both run it.
     */
    DIRECT,

    /** Nothing here runs it. Only a trail's `tools:` block does. */
    TRAIL,

    /** Only another tool may call it (`callableOnlyFromTools`); no trail or direct call runs it. */
    TOOL_ONLY,
  }

  /**
   * [runsOnlyOnDrivers] is set when every toolset offering the tool is scoped to drivers this
   * session does not have (or there is no device at all): the tool's executor will not run on
   * this session's device whatever door it comes through, so a scripted `client.tools.<name>(…)`
   * offered as a way in from here would fail. Null means the driver is not the obstacle.
   */
  private class ToolSetStanding(
    val availability: String,
    val door: ToolDoor,
    val runsOnlyOnDrivers: List<String>? = null,
  )

  /**
   * Missing from the catalogue does not mean "in no toolset". The catalogue is the discoverable
   * categories plus the loaded targets' toolsets, and a toolset can be in neither:
   *
   * - `multi_device` is declared by no target and switched on per session, so `switchDevice` used
   *   to be described as a tool no agent can choose in the very session that was advertising it.
   * - An `always_enabled` toolset (`android_framework`, `mobile_primitives`) is on every session
   *   whose driver it lists without any target declaring it, so `mobile_listInstalledApps` was
   *   described as unseen by any agent in an Android session where the inner agent had it.
   *
   * Ask the toolset catalogue by name before saying nothing offers a tool; then ask the session
   * (for a session-bound toolset) or the driver (for an always-enabled one) whether it is on offer
   * here, and say which door it is behind.
   */
  private fun toolSetStanding(toolName: String): ToolSetStanding {
    if (knownToolClassProvider(toolName)?.isCallableOnlyFromTools() == true) {
      return ToolSetStanding(CallableOnlyFromToolsException.message(toolName), ToolDoor.TOOL_ONLY)
    }
    val offering = TrailblazeToolSetCatalog.defaultEntries()
      .filter { entry -> entry.toolNames.any { it.equals(toolName, ignoreCase = true) } }
    if (offering.isEmpty()) return ToolSetStanding(NOT_OFFERED_AVAILABILITY, ToolDoor.TRAIL)
    val tail = notOfferedTail(toolName)

    val multiDevice = offering.firstOrNull { it.id == SwitchDeviceTrailblazeTool.MULTI_DEVICE_TOOLSET_ID }
    if (multiDevice != null) {
      // Same predicate the server registers the toolset on, so this cannot say "not offered" to a
      // session that has the tool in its tool list.
      val session = sessionContext
      if (session != null && session.advertisesMultiDeviceTools()) {
        return ToolSetStanding(
          availability = "Offered by the `${multiDevice.id}` toolset: this session has bound " +
            session.boundDeviceNames().joinToString(", ") +
            ", so the tool is advertised to it directly — call it as its own tool. 'trailblaze tool' " +
            "routes through the inner agent, which does not have it, and refuses to run it.",
          door = ToolDoor.SESSION,
        )
      }
      return ToolSetStanding(
        availability = "Offered only by the `${multiDevice.id}` toolset, which no target declares: a " +
          "session gets it when it binds two or more named devices (a trail's `config.devices:`), " +
          "and this session has not. Until then $tail",
        door = ToolDoor.TRAIL,
      )
    }

    val driver = currentDriverTypeProvider()
    val alwaysEnabled = offering.filter { it.alwaysEnabled }
    if (alwaysEnabled.isNotEmpty()) {
      // `resolveForSession` puts every always-enabled toolset the driver lists on the inner
      // agent's surface, target or no target — so on a listed driver the tool is simply there.
      val onThisDriver = alwaysEnabled.filter { driver != null && it.isCompatibleWith(driver) }
      if (onThisDriver.isNotEmpty()) {
        // The toolset being on the surface does not put every tool in it on the agent's menu. A
        // tool marked `surfaceToLlm = false` (`mobile_clearAppData`) is dropped from the agent's
        // descriptors, but the session still registers it, so a direct call and a trail run it.
        if (!surfacedToAgents(toolName)) {
          return ToolSetStanding(
            availability = "Not offered to the LLM (it hides itself; the ${onThisDriver.ids()} " +
              "toolset(s) enable it on ${driver!!.yamlKey}); ${directCallEnding(toolName)}",
            door = ToolDoor.DIRECT,
          )
        }
        return ToolSetStanding(
          availability = "Always enabled by the ${onThisDriver.ids()} toolset(s) on this session's " +
            "driver (${driver!!.yamlKey}), so every agent here has it.",
          door = ToolDoor.AGENT,
        )
      }
      val drivers = alwaysEnabled.driverKeys()
      val here = if (driver == null) "This session has no device bound" else "This session's driver is ${driver.yamlKey}"
      return ToolSetStanding(
        availability = "Always enabled by the ${alwaysEnabled.ids()} toolset(s) on drivers " +
          "${drivers.joinToString(", ")}. $here, so it is not in scope here: ${notOfferedTail(toolName, drivers)}",
        door = ToolDoor.TRAIL,
        runsOnlyOnDrivers = drivers,
      )
    }

    // A driver-scoped toolset (`web_*` in an Android session) is out of scope for the same reason
    // an always-enabled one is: the tool does not run on this device, so no door from here works.
    val runsOnlyOnDrivers = offering.driverKeys()
      .takeIf { it.isNotEmpty() && (driver == null || offering.none { it.isCompatibleWith(driver) }) }
    return ToolSetStanding(
      availability = "Offered by the ${offering.ids()} toolset(s), which this session's device and " +
        "target do not put in scope, so ${notOfferedTail(toolName, runsOnlyOnDrivers)}",
      door = ToolDoor.TRAIL,
      runsOnlyOnDrivers = runsOnlyOnDrivers,
    )
  }

  /** The `yamlKey`s of every driver these toolsets are scoped to, deduplicated and sorted. */
  private fun List<ToolSetCatalogEntry>.driverKeys(): List<String> =
    flatMap { it.compatibleDriverTypes }.map { it.yamlKey }.distinct().sorted()

  /**
   * Whether the recorder ever writes this tool into a trail, for the recorder's tool palette.
   * Nothing a caller reads says it: the CLI never talks about recordings.
   *
   * A scripted tool opts out with `isRecordable: false` or `_meta: trailblaze/isRecordable: false`;
   * a target's own (inline) copy is read before a catalog one, as the session registers it over
   * that. Pass one [catalog] when asking about many names: each new catalog walks the descriptors.
   */
  fun recordable(toolName: String, catalog: ScriptedToolCatalog? = null): Boolean {
    knownToolClassProvider(toolName)?.let { return it.trailblazeToolClassAnnotation().isRecordable }
    val scripted = allTargetAppsProvider().firstNotNullOfOrNull { target ->
      target.getInlineScriptTools().firstOrNull { it.name == toolName }
    } ?: runCatching { (catalog ?: scriptedToolCatalogFactory()).descriptorsByName }.getOrNull()
      ?.get(ToolName(toolName))
      ?.toolConfigs()
      ?.firstOrNull { it.name == toolName }
    return scripted == null || (scripted.isRecordable && scripted.toolMeta().isRecordable)
  }

  /**
   * What a name lookup says about a tool a toolset in scope lists but the LLM is not offered:
   * [reason] says why, and the ending says how it is still called.
   */
  private fun notOfferedAvailability(
    toolName: String,
    reason: ToolNotOfferedReason,
    target: TrailblazeHostAppTarget?,
    driverType: TrailblazeDriverType?,
  ): String {
    val why = when (reason) {
      ToolNotOfferedReason.HIDDEN_BY_TOOL -> "the tool hides itself (`surfaceToLlm: false`)"
      ToolNotOfferedReason.EXCLUDED_BY_TARGET ->
        "target `${target?.id}` drops it ${driverType?.let { "on ${it.yamlKey}" } ?: "on every platform it runs on"} (`excluded_tools:`)"
    }
    return "Not offered to the LLM ($why); ${directCallEnding(toolName)}"
  }

  /** How a tool the LLM is not offered, but that this session registers, is still called. */
  private fun directCallEnding(toolName: String): String =
    "call it with `trailblaze tool`, a trail step, or a TS tool (`ctx.tools.$toolName`)."

  /**
   * What is left for a tool this session does not register: `trailblaze tool` checks the session's
   * tools and refuses it, while a trail step names its own device and a TS tool resolves through the
   * whole registry. When the tool is scoped to drivers this session lacks, both doors are named
   * with the drivers they open on.
   */
  private fun notOfferedTail(toolName: String, runsOnlyOnDrivers: List<String>? = null): String =
    if (runsOnlyOnDrivers == null) {
      NOT_OFFERED_TAIL
    } else {
      "no agent here sees it and `trailblaze tool` can't call it from this session. A trail step on a " +
        "${runsOnlyOnDrivers.joinToString(", ")} device can, and so can a TS tool (`ctx.tools.$toolName`) " +
        "in a session on one of those drivers, not this one."
    }

  private fun List<ToolSetCatalogEntry>.ids(): String = joinToString(", ") { "`${it.id}`" }

  /**
   * Whether the agent's toolbox advertises this tool. Same flag the descriptor builders filter on,
   * so this cannot call a tool agent-visible that the agent's own tool list omits.
   */
  private fun surfacedToAgents(toolName: String): Boolean =
    knownToolClassProvider(toolName)?.trailblazeToolClassAnnotation()?.surfaceToLlm ?: true

  private fun handleTargetMode(
    targetId: String,
    detail: Boolean,
    platformFilter: TrailblazeDevicePlatform? = null,
    catalog: ScriptedToolCatalog,
    includeNotOffered: Boolean = false,
  ): String {
    val allTargets = allTargetAppsProvider()
    val targetApp = allTargets.firstOrNull { it.id.equals(targetId, ignoreCase = true) }

    if (targetApp == null) {
      return jsonFormat.encodeToString(ToolDiscoveryTargetResult(error = unknownTargetMessage(targetId, allTargets)))
    }

    val currentDriverType = currentDriverTypeProvider()
    // Platform filter from CLI --device overrides the daemon's connected-device driver when the
    // platforms disagree — otherwise `toolbox --device=android --target=myapp` would silently
    // return web tools just because the daemon happens to have a playwright session live. When
    // the platforms agree, keep the specific connected driver so on-device instrumentation vs
    // accessibility distinctions are preserved.
    val effectiveDriverType = resolveEffectiveDriverType(currentDriverType, platformFilter)
    logIfDriverWasOverridden(currentDriverType, platformFilter, effectiveDriverType)

    val supportedPlatforms = TrailblazeDevicePlatform.entries.filter { platform ->
      !targetApp.getPossibleAppIdsForPlatform(platform).isNullOrEmpty()
    }.map { it.displayName }

    // When a device is connected (or platform filter resolves a driver), show grouped tools.
    // Otherwise, show flat tools by platform.
    if (effectiveDriverType != null) {
      // Apply targetApp's own exclusions so a `excluded_tools: [eraseText]` declaration in the
      // target YAML doesn't leak into the listing — discovery output should reflect what the
      // executor actually accepts when this target is current.
      val excludedToolNames = getTargetExcludedToolNames(targetApp, effectiveDriverType)
      val inline = inlineListedTools(targetApp, effectiveDriverType)
      val inlineNames = inlineToolNames(inline)
      val classGroups = targetApp.getCustomToolGroupsForDriver(effectiveDriverType).mapNotNull { group ->
        toolsetInfo(
          name = group.id,
          description = group.description,
          listed = listedTools(
            group.toolClasses,
            group.scriptedToolNames.filterNotTo(mutableSetOf()) { it.toolName in inlineNames },
            catalog,
            excludedToolNames,
          ),
          detail = detail,
        )
      }
      val allToolGroups = (classGroups + buildInlineScriptToolsetsForDriver(targetApp, inline, detail))
        .withoutRepeatedNotOffered()
      val notOfferedCount = notOfferedToolCount(allToolGroups)
      val toolGroups = allToolGroups.shown(includeNotOffered)

      // Mirror the index-mode role enrichment so `toolbox trailheads --target=<non-default>`
      // gets the role lists it needs — otherwise the CLI's role-filter view always reports
      // "No trailheads available" for any non-default target. Scope the intersection guard to
      // this target's own toolGroups so we never surface a tool that won't appear when the
      // user drills in via `--name <id>`.
      val trailheadToolNames = computeTrailheadNames(toolGroups, target = targetApp)

      return jsonFormat.encodeToString(
        ToolDiscoveryTargetResult(
          target = targetApp.id,
          displayName = targetApp.displayName,
          currentPlatform = effectiveDriverType.platform.displayName,
          supportedPlatforms = supportedPlatforms,
          systemPrompt = systemPromptForTarget(targetApp),
          toolGroups = toolGroups.ifEmpty { null },
          trailheadTools = trailheadToolNames,
          notOfferedToolCount = notOfferedCount.takeIf { it > 0 },
          notOfferedNote = notOfferedNote(notOfferedCount, includeNotOffered),
          includesNotOffered = true.takeIf { includeNotOffered },
        )
      )
    }

    // No device connected and no platform filter — show all platforms flat. Each platform's
    // listing is filtered by targetApp's exclusions for that platform's drivers, mirroring the
    // device-connected branch above.
    val allListed = TrailblazeDevicePlatform.entries.associateWith { platform ->
      listPermittedToolsForPlatform(targetApp, platform, catalog)
    }
    val offeredNames = allListed.values.flatten().filter { it.notOffered == null }.mapTo(mutableSetOf()) { it.descriptor.name }
    val notOfferedCount = allListed.values.flatten()
      .mapNotNullTo(mutableSetOf()) { l -> l.descriptor.name.takeIf { l.notOffered != null } }
      .count { it !in offeredNames }
    val platformTools = allListed.mapNotNull { (platform, listed) ->
      val shown = if (includeNotOffered) listed else listed.filter { it.notOffered == null }
      if (shown.isEmpty()) return@mapNotNull null
      ToolDiscoveryTargetPlatformTools(
        platform = platform.displayName,
        tools = toolDetailsFor(detail, shown.map { it.descriptor }),
        notOffered = shown.mapNotNull { l -> l.notOffered?.let { l.descriptor.name to it } }.toMap().ifEmpty { null },
      )
    }

    // Flat-by-platform also gets role enrichment so role filtering works even when no device is
    // connected. Tool ids here come from the per-platform [TrailblazeToolDescriptor] lists rather
    // than a toolset shape, so feed them via the extraInScopeNames hook.
    val flatInScopeNames = platformTools.flatMap { it.tools.map { d -> d.name } }
    val trailheadToolNames =
      computeTrailheadNames(extraInScopeNames = flatInScopeNames, target = targetApp)

    return jsonFormat.encodeToString(
      ToolDiscoveryTargetResult(
        target = targetApp.id,
        displayName = targetApp.displayName,
        supportedPlatforms = supportedPlatforms,
        systemPrompt = systemPromptForTarget(targetApp),
        toolsByPlatform = platformTools.ifEmpty { null },
        trailheadTools = trailheadToolNames,
        notOfferedToolCount = notOfferedCount.takeIf { it > 0 },
        notOfferedNote = notOfferedNote(notOfferedCount, includeNotOffered),
        includesNotOffered = true.takeIf { includeNotOffered },
      )
    )
  }

  private fun handleSearchMode(
    query: String,
    targetFilter: String?,
    platformFilter: TrailblazeDevicePlatform? = null,
    catalog: ScriptedToolCatalog,
    includeNotOffered: Boolean = false,
  ): String {
    val terms = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
    if (terms.isEmpty()) {
      return jsonFormat.encodeToString(
        ToolDiscoverySearchResult(error = "Search query cannot be empty.")
      )
    }

    fun matches(descriptor: TrailblazeToolDescriptor): Boolean {
      val haystack = "${descriptor.name} ${descriptor.description ?: ""}".lowercase()
      return terms.all { term -> haystack.contains(term) }
    }

    val currentDriverType = currentDriverTypeProvider()
    // Same precedence as INDEX/TARGET modes: an explicit `--device=<platform>` wins over the
    // daemon's currently-connected driver when the platforms disagree. Without this, a user
    // running `toolbox --search=… --device=android` while the daemon held a web playwright
    // session would silently see web-only results — the same bug the override was designed
    // to prevent for the listing modes.
    val effectiveDriverType = resolveEffectiveDriverType(currentDriverType, platformFilter)
    logIfDriverWasOverridden(currentDriverType, platformFilter, effectiveDriverType)
    val currentTarget = currentTargetProvider()
    val results = mutableListOf<ToolDiscoverySearchMatch>()

    // Search platform tools — use platform-filtered toolsets (same as index mode)
    // so tools inapplicable to the current device don't appear in results
    // (e.g., openUrl won't show for web since the default.yaml only includes web_core).
    // A `target` filter scopes the platform tools' exclusions too: they are what THAT target drops.
    val exclusionTarget = if (targetFilter == null) {
      currentTarget
    } else {
      allTargetAppsProvider().firstOrNull { it.id.equals(targetFilter, ignoreCase = true) }
        ?: DefaultTrailblazeHostAppTarget.takeIf { it.id.equals(targetFilter, ignoreCase = true) }
    }
    val platformToolsets = buildPlatformToolsets(
      detail = true,
      targetExcludedToolNames = getTargetExcludedToolNames(exclusionTarget, effectiveDriverType),
      driverType = effectiveDriverType,
      catalog = catalog,
    )
    for (toolset in platformToolsets) {
      toolset.toolDetails?.filter { matches(it) }?.forEach { descriptor ->
        results.add(
          ToolDiscoverySearchMatch(
            tool = descriptor,
            source = toolset.name.replaceFirstChar { it.titlecase() },
            notOffered = toolset.notOffered?.get(descriptor.name),
          )
        )
      }
    }

    // Search target tools — scoped to connected driver if available
    val targetsToSearch = if (targetFilter != null) {
      val target = allTargetAppsProvider().firstOrNull { it.id.equals(targetFilter, ignoreCase = true) }
      if (target != null) listOf(target) else emptyList()
    } else if (currentTarget != null) {
      listOf(currentTarget)
    } else {
      allTargetAppsProvider().toList()
    }

    for (target in targetsToSearch) {
      if (effectiveDriverType != null) {
        listCustomTools(target, effectiveDriverType, catalog)
          .filter { matches(it.descriptor) }
          .forEach { listed ->
            results.add(
              ToolDiscoverySearchMatch(
                tool = listed.descriptor,
                source = "${target.displayName} (${effectiveDriverType.platform.displayName})",
                notOffered = listed.notOffered,
              )
            )
          }
      } else {
        TrailblazeDevicePlatform.entries.forEach { platform ->
          listPermittedToolsForPlatform(target, platform, catalog)
            .filter { matches(it.descriptor) }
            .forEach { listed ->
              results.add(
                ToolDiscoverySearchMatch(
                  tool = listed.descriptor,
                  source = "${target.displayName} (${platform.displayName})",
                  notOffered = listed.notOffered,
                )
              )
            }
        }
      }
    }

    // Deduplicate by tool name, preferring an offered match over a not-offered one for the same name.
    val offeredNames = results.filter { it.notOffered == null }.mapTo(mutableSetOf()) { it.tool.name }
    val deduped = results
      .filter { it.notOffered == null || it.tool.name !in offeredNames }
      .distinctBy { it.tool.name }
    val notOfferedCount = deduped.count { it.notOffered != null }
    val shown = if (includeNotOffered) deduped else deduped.filter { it.notOffered == null }

    return jsonFormat.encodeToString(
      ToolDiscoverySearchResult(
        query = query,
        matches = shown.ifEmpty { null },
        notOfferedToolCount = notOfferedCount.takeIf { it > 0 },
        notOfferedNote = notOfferedNote(notOfferedCount, includeNotOffered, matching = true),
        includesNotOffered = true.takeIf { includeNotOffered },
      )
    )
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Descriptor list for a toolset's `toolDetails` field, sized to the requested mode.
   *
   * `detail=false` (the index / compact modes) keeps name + description — the CLI's compact
   * listing and the role views render description peeks from `toolDetails` even without
   * `--detail`, so it can't be nulled out — but strips the parameter descriptors (and, via
   * `copy`, the body-property `inputSchema`). Without the strip, a no-args `toolbox()` call
   * serialized the FULL parameter surface of every tool across every loaded target despite
   * promising an index (measured at hundreds of thousands of characters on a heavily-loaded
   * daemon), overwhelming MCP agents whose first discovery call it is. `detail=true` returns
   * the descriptors untouched.
   */
  private fun toolDetailsFor(
    detail: Boolean,
    descriptors: List<TrailblazeToolDescriptor>,
  ): List<TrailblazeToolDescriptor> = if (detail) {
    descriptors
  } else {
    descriptors.map { it.copy(requiredParameters = emptyList(), optionalParameters = emptyList()) }
  }

  /**
   * Single source of the compact-index scoping decision. The invariant it guards: whenever the
   * target listing is scoped to the current target (this returns false), `handleIndexMode` must
   * emit `otherTargets` so the rest of the catalogue stays discoverable. Both the
   * [buildTargetToolsets] filter and the `otherTargets` derivation consult this predicate —
   * change it here, never inline at one site.
   */
  private fun includeAllTargetsInIndex(detail: Boolean): Boolean = detail

  /**
   * Resolves the system prompt content the daemon ships back for [target] in target-mode.
   *
   * Returns null for the `default` sentinel — that target models "platform tools only" and has
   * no app-specific guidance to surface; emitting an inherited prompt here would be inconsistent
   * with the catalog scope. Today's `toolbox()` dispatcher already routes `target=default` through
   * index mode (where the equivalent suppression lives), so this branch is defense-in-depth for
   * direct MCP callers that bypass the dispatcher.
   *
   * `internal` rather than `private` so a regression test can pin the default-sentinel branch
   * directly — the dispatcher's `isDefaultTarget` guard makes the branch unreachable from
   * `toolbox()` today, so a focused unit test is the only way to keep it honest.
   */
  internal fun systemPromptForTarget(target: TrailblazeHostAppTarget): String? =
    if (target.id == DefaultTrailblazeHostAppTarget.id) null else target.getSystemPromptTemplate()

  /**
   * Resolves the driver type used to filter discovery output, reconciling the daemon's currently
   * connected driver against an explicit `--device=<platform>` CLI flag.
   *
   * Precedence:
   * - If [platformFilter] is provided and its platform differs from [currentDriverType]'s platform,
   *   the CLI flag wins. Returns the platform's default driver type. This is the load-bearing case:
   *   without it, `toolbox --device=android --target=myapp` silently returns web tools whenever the
   *   daemon has any non-Android driver active (e.g. a leftover playwright session).
   * - Otherwise (platforms agree, or no [platformFilter]), keep [currentDriverType] so the specific
   *   on-device driver (instrumentation vs accessibility, axe vs host on iOS, etc.) is preserved.
   * - If neither is set, returns null.
   */
  private fun resolveEffectiveDriverType(
    currentDriverType: TrailblazeDriverType?,
    platformFilter: TrailblazeDevicePlatform?,
  ): TrailblazeDriverType? = when {
    platformFilter != null && currentDriverType?.platform != platformFilter ->
      resolveDefaultDriverType(platformFilter)
    else -> currentDriverType
  }

  /**
   * Emits a `daemon.log` breadcrumb when the platform filter forced an override of the daemon's
   * currently-connected driver, so a confused user can trace why their `--device=<X>` invocation
   * filtered by a different driver than the daemon's "current" one. Called from each mode handler
   * (INDEX/TARGET/SEARCH) alongside [resolveEffectiveDriverType] — extracted so the resolver stays
   * a pure function that's unit-testable in isolation, and the logging side effect lives where it
   * can be silenced or extended without touching resolution semantics.
   */
  private fun logIfDriverWasOverridden(
    currentDriverType: TrailblazeDriverType?,
    platformFilter: TrailblazeDevicePlatform?,
    effectiveDriverType: TrailblazeDriverType?,
  ) {
    if (platformFilter == null || effectiveDriverType == null) return
    if (currentDriverType?.platform == platformFilter) return
    Console.log(
      "[ToolDiscoveryToolSet] --device=${platformFilter.name.lowercase()} overrides connected " +
        "driver ${currentDriverType?.yamlKey ?: "(none)"} — filtering with ${effectiveDriverType.yamlKey}",
    )
  }

  /**
   * Resolves a default driver type for a platform, used for filtering when no device is connected.
   *
   * Distinct from [TrailblazeDriverType.Companion.defaultForPlatform]: that companion returns
   * `null` for platforms without a user-togglable per-platform default (WEB, DESKTOP) — its
   * contract is "what does `trailblaze config <p>-driver` toggle?" Discovery filtering, by
   * contrast, needs *some* driver for every platform so listings can be filtered consistently;
   * this local helper picks the canonical filtering driver (`PLAYWRIGHT_NATIVE` for WEB,
   * `COMPOSE` for DESKTOP) where the companion would have returned null. Don't merge the two
   * without preserving that distinction.
   */
  private fun resolveDefaultDriverType(platform: TrailblazeDevicePlatform): TrailblazeDriverType {
    return when (platform) {
      TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.DEFAULT_ANDROID
      TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
      TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.PLAYWRIGHT_NATIVE
      TrailblazeDevicePlatform.DESKTOP -> TrailblazeDriverType.DEFAULT_DESKTOP
    }
  }

  /**
   * Builds platform toolsets from the "default" target's tool groups.
   * The "default" target defines the base platform tools available on any device.
   * Falls back to [DISCOVERABLE_CATEGORIES] if the default target has no groups.
   */
  private fun buildPlatformToolsets(
    detail: Boolean,
    targetExcludedToolNames: Set<String> = emptySet(),
    driverType: TrailblazeDriverType? = null,
    catalog: ScriptedToolCatalog,
  ): List<ToolDiscoveryToolsetInfo> {
    val defaultTarget = allTargetAppsProvider().find { it.id == DefaultTrailblazeHostAppTarget.id }
    if (defaultTarget != null) {
      // Use a driver type for group filtering (default to Android if none provided)
      val effectiveDriver = driverType ?: TrailblazeDriverType.DEFAULT_ANDROID
      val groups = defaultTarget.getCustomToolGroupsForDriver(effectiveDriver)
      if (groups.isNotEmpty()) {
        return groups.mapNotNull { group ->
          toolsetInfo(
            name = group.id,
            description = group.description,
            listed = listedTools(group.toolClasses, group.scriptedToolNames, catalog, targetExcludedToolNames),
            detail = detail,
          )
        }
      }
    }

    // Fallback: use hardcoded categories (open source without default target YAML)
    return DISCOVERABLE_CATEGORIES.mapNotNull { category ->
      val resolved = ToolSetCategoryMapping.resolve(category)
      toolsetInfo(
        name = category.name.lowercase(),
        description = category.description,
        listed = listedTools(resolved.toolClasses, resolved.scriptedToolNames, catalog, targetExcludedToolNames),
        detail = detail,
      )
    }
  }

  /**
   * A tool as a listing sees it: its descriptor, and why the in-session LLM is not offered it
   * (null when it is). Every listing is built with the not-offered tools in and marked; [shown]
   * drops them for the default view, so the count of what was dropped comes from the same pass.
   */
  private class ListedTool(val descriptor: TrailblazeToolDescriptor, val notOffered: ToolNotOfferedReason?)

  private fun notOfferedReason(hiddenByTool: Boolean, excludedByTarget: Boolean): ToolNotOfferedReason? = when {
    hiddenByTool -> ToolNotOfferedReason.HIDDEN_BY_TOOL
    excludedByTarget -> ToolNotOfferedReason.EXCLUDED_BY_TARGET
    else -> null
  }

  /**
   * Class-backed and catalog-scripted tools, each marked. A class marked `surfaceToLlm = false`
   * gets no descriptor from the gate the agent's toolbox goes through, so it is described without
   * that gate; a scripted tool's flag comes from its config, as the session's registration reads
   * it. [SYSTEM_INTERNAL_TOOLS] never appear.
   */
  private fun listedTools(
    toolClasses: Set<KClass<out TrailblazeTool>>,
    scriptedToolNames: Set<ToolName>,
    catalog: ScriptedToolCatalog,
    targetExcludedToolNames: Set<String>,
  ): List<ListedTool> {
    val classTools = toolClasses.filter { it !in SYSTEM_INTERNAL_TOOLS }.mapNotNull { cls ->
      val offered = cls.toTrailblazeToolDescriptorWithSource()
      val descriptor = offered
        ?: cls.takeUnless { it.trailblazeToolClassAnnotation().surfaceToLlm }?.toTrailblazeToolDescriptorIgnoringLlmSurface()
        ?: return@mapNotNull null
      ListedTool(descriptor, notOfferedReason(offered == null, descriptor.name in targetExcludedToolNames))
    }
    val scriptedTools = InProcessScriptedToolLauncher.resolveInProcessScriptedTools(scriptedToolNames, catalog = catalog)
      .map { resolved ->
        val config = resolved.config
        ListedTool(
          LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor(config),
          notOfferedReason(!config.surfacesToLlm(config.toolMeta()), config.name in targetExcludedToolNames),
        )
      }
    return (classTools + scriptedTools).distinctBy { it.descriptor.name }
  }

  /** A toolset entry over [listed], every tool in it, with the not-offered ones marked. Null when empty. */
  private fun toolsetInfo(
    name: String,
    description: String,
    listed: List<ListedTool>,
    detail: Boolean,
  ): ToolDiscoveryToolsetInfo? {
    if (listed.isEmpty()) return null
    val sorted = listed.sortedWith(compareBy { it.descriptor.name })
    val descriptors = sorted.map { it.descriptor }
    return ToolDiscoveryToolsetInfo(
      name = name,
      description = description,
      tools = if (detail) null else descriptors.map { it.name },
      toolDetails = toolDetailsFor(detail, descriptors),
      notOffered = sorted.mapNotNull { l -> l.notOffered?.let { l.descriptor.name to it } }.toMap().ifEmpty { null },
    )
  }

  /**
   * The view a caller asked for: every tool, marked, when [includeNotOffered]; otherwise only the
   * tools the in-session LLM is offered, with toolsets that end up empty dropped.
   */
  private fun List<ToolDiscoveryToolsetInfo>.shown(includeNotOffered: Boolean): List<ToolDiscoveryToolsetInfo> {
    if (includeNotOffered) return this
    return mapNotNull { ts ->
      val notOffered = ts.notOffered?.keys ?: return@mapNotNull ts
      val details = ts.toolDetails?.filter { it.name !in notOffered }
      if (details.isNullOrEmpty()) return@mapNotNull null
      ts.copy(tools = ts.tools?.filter { it !in notOffered }, toolDetails = details, notOffered = null)
    }
  }

  /**
   * Drops a not-offered entry whose name an earlier toolset already lists, so a tool a target both
   * names in a toolset and ships as an inline script shows once under `all=true`. Toolsets with
   * different [scope]s never dedupe against each other (one per target, in a listing of several).
   */
  private fun List<ToolDiscoveryToolsetInfo>.withoutRepeatedNotOffered(
    scope: (ToolDiscoveryToolsetInfo) -> String = { "" },
  ): List<ToolDiscoveryToolsetInfo> {
    val seenByScope = mutableMapOf<String, MutableSet<String>>()
    return mapNotNull { ts ->
      val seen = seenByScope.getOrPut(scope(ts)) { mutableSetOf() }
      val repeats = ts.notOffered?.keys.orEmpty().filterTo(mutableSetOf()) { it in seen }
      seen += ts.toolDetails.orEmpty().map { it.name }
      if (repeats.isEmpty()) return@mapNotNull ts
      val details = ts.toolDetails?.filter { it.name !in repeats }
      if (details.isNullOrEmpty()) return@mapNotNull null
      ts.copy(
        tools = ts.tools?.filter { it !in repeats },
        toolDetails = details,
        notOffered = ts.notOffered?.filterKeys { it !in repeats }?.ifEmpty { null },
      )
    }
  }

  /** Distinct tools these toolsets mark as not offered, less any another toolset here does offer. */
  private fun notOfferedToolCount(vararg toolsets: List<ToolDiscoveryToolsetInfo>?): Int {
    val all = toolsets.flatMap { it.orEmpty() }
    val notOffered = all.flatMap { it.notOffered?.keys.orEmpty() }.toSet()
    val offered = all.flatMap { ts -> ts.toolDetails.orEmpty().map { it.name }.filter { ts.notOffered?.containsKey(it) != true } }
    return (notOffered - offered.toSet()).size
  }

  /**
   * The one line an MCP caller reads under a default listing: how many tools it is not seeing, what
   * they are for, and how to list them. Null when there are none, or when they were listed.
   */
  private fun notOfferedNote(count: Int, includeNotOffered: Boolean, matching: Boolean = false): String? {
    if (count <= 0 || includeNotOffered) return null
    val what = (if (matching) "matching " else "") + (if (count == 1) "tool is" else "tools are")
    return "$count more $what not offered to the LLM: offered tools drive a flow, the rest are for specific " +
      "use cases; call one with a direct step(tools=...) call, a trail step, or a TS tool (ctx.tools.<name>). " +
      "Pass all=true to list them."
  }

  private fun InlineScriptToolConfig.toolMeta(): TrailblazeToolMeta =
    meta?.let(TrailblazeToolMeta::fromJsonObject) ?: TrailblazeToolMeta()

  private fun buildTargetToolsets(
    currentTarget: TrailblazeHostAppTarget?,
    currentDriverType: TrailblazeDriverType?,
    detail: Boolean,
    catalog: ScriptedToolCatalog,
  ): List<ToolDiscoveryToolsetInfo>? {
    // detail=true lists tool groups from ALL targets — the explicit full-catalogue view for
    // discovery. MCP registration is scoped to the current target, so this deliberately exceeds
    // the callable surface; another target's tools become callable after switching to it.
    //
    // detail=false (the no-args index) mirrors the callable surface: the CURRENT target only. A
    // daemon with many targets loaded serialized every tool's name and description across all of
    // them into what the tool description promises is an "index" — useless as an MCP agent's
    // first discovery call and unreadable in a terminal. The other targets still surface by name
    // via `otherTargets` (see handleIndexMode); drill in with `toolbox(target=...)` / `--target`.
    val targets = allTargetAppsProvider()
      .filter { it.id != DefaultTrailblazeHostAppTarget.id }
      .filter { includeAllTargetsInIndex(detail) || it.id == currentTarget?.id }
      .toList()
    if (targets.isEmpty()) return null

    if (currentDriverType != null) {
      // Device connected — show grouped tools for the current driver. Each target's listing is
      // filtered by THAT target's own exclusions so an `excluded_tools:` declaration is honored
      // consistently with platform listings.
      val allGroups = targets.flatMap { target ->
        val excludedToolNames = getTargetExcludedToolNames(target, currentDriverType)
        val inline = inlineListedTools(target, currentDriverType)
        val inlineNames = inlineToolNames(inline)
        val classGroups = target.getCustomToolGroupsForDriver(currentDriverType).mapNotNull { group ->
          toolsetInfo(
            name = "${target.id}/${group.id}",
            description = group.description,
            listed = listedTools(
              group.toolClasses,
              group.scriptedToolNames.filterNotTo(mutableSetOf()) { it.toolName in inlineNames },
              catalog,
              excludedToolNames,
            ),
            detail = detail,
          )
        }
        classGroups + buildInlineScriptToolsetsForDriver(target, inline, detail)
      }
      return allGroups.ifEmpty { null }
    }

    // No device connected — show flat tools per target/platform. Apply per-target, per-platform
    // exclusions for the same reason as the device-connected branch above.
    val allGroups = targets.flatMap { target ->
      TrailblazeDevicePlatform.entries.mapNotNull { platform ->
        toolsetInfo(
          name = "${target.id} (${platform.displayName})",
          description = "${target.displayName} tools for ${platform.displayName}",
          listed = listPermittedToolsForPlatform(target, platform, catalog),
          detail = detail,
        )
      }
    }
    return allGroups.ifEmpty { null }
  }

  private fun buildOtherTargets(
    currentTarget: TrailblazeHostAppTarget?,
    allTargets: Set<TrailblazeHostAppTarget>,
  ): List<ToolDiscoveryOtherTarget>? {
    val others = allTargets.filter { it.id != currentTarget?.id && it.id != DefaultTrailblazeHostAppTarget.id }
    if (others.isEmpty()) return null

    return others.map { target ->
      // Emit the lowercase enum id (`android`, `ios`, `web`, `desktop`) — that's what
      // `--device` accepts. The display-name form ("Android", "iOS", "Web Browser") looks
      // nicer in a sentence but invites copy-paste failure when users try to feed it back
      // into the CLI as `--device "Web Browser"`.
      val platforms = TrailblazeDevicePlatform.entries.filter { platform ->
        !target.getPossibleAppIdsForPlatform(platform).isNullOrEmpty()
      }.map { it.name.lowercase() }
      ToolDiscoveryOtherTarget(
        name = target.id,
        platforms = platforms.ifEmpty { null },
      )
    }
  }

  /**
   * Computes tool names to exclude from discovery output.
   *
   * Combines:
   * - Target-specific exclusions (e.g., a target replacing swipe with a custom impl)
   * - System-internal tools that are agent machinery, not user-facing actions
   */
  private fun getExcludedToolNames(
    target: TrailblazeHostAppTarget?,
    driverType: TrailblazeDriverType?,
  ): Set<String> {
    val systemExclusions = SYSTEM_INTERNAL_TOOLS.mapNotNull {
      it.toTrailblazeToolDescriptorWithSource()?.name
    }.toSet()
    return systemExclusions + getTargetExcludedToolNames(target, driverType)
  }

  /**
   * The names [target] drops on [driverType] with `excluded_tools:` — the tools a listing marks
   * [ToolNotOfferedReason.EXCLUDED_BY_TARGET]. Unlike [SYSTEM_INTERNAL_TOOLS], these stay callable
   * by name (a direct call, a trail, a TS tool), so `all=true` lists them.
   */
  private fun getTargetExcludedToolNames(
    target: TrailblazeHostAppTarget?,
    driverType: TrailblazeDriverType?,
  ): Set<String> {
    if (driverType == null || target == null) return emptySet()

    return try {
      // One read of the target's `excluded_tools:` surface across both backings — the same
      // central accessor the resolver / runtime / on-device paths use. This keeps discovery's
      // hidden set in lockstep with what the agent actually sees: a target's
      // `excluded_tools: [openUrl]` (scripted) is hidden here exactly as it's dropped from the
      // LLM surface.
      val excluded = target.getExcludedToolSurfaceForDriver(driverType)
      // Class exclusions resolve to their advertised descriptor name (the @TrailblazeToolClass
      // name); scripted exclusions are already names.
      val classExclusions = excluded.toolClasses
        .mapNotNull { it.toTrailblazeToolDescriptorWithSource()?.name }
      (
        classExclusions +
          excluded.scriptedToolNames.map { it.toolName }
        ).toSet()
    } catch (e: Exception) {
      // Don't fail discovery if a target's exclusion lookup throws — just degrade to "no
      // target-side exclusions" and surface the failure so it isn't silent during debugging.
      Console.log(
        "Warning: failed to resolve excluded tools for target '${target.id}' / $driverType: ${e.message}",
      )
      emptySet()
    }
  }

  private fun getToolDescriptorsForCategory(
    category: ToolSetCategory,
    catalog: ScriptedToolCatalog,
  ): List<TrailblazeToolDescriptor> {
    val resolved = ToolSetCategoryMapping.resolve(category)
    val classDescriptors = resolved.toolClasses
      .mapNotNull { it.toTrailblazeToolDescriptorWithSource() }
    // Include scripted tools (e.g. `openUrl` in NAVIGATION) so discovery output matches the
    // executor's accepted set; built from the catalog YAML without launching a QuickJS engine.
    val scriptedDescriptors = InProcessScriptedToolLauncher.describe(resolved.scriptedToolNames, catalog)
    return (classDescriptors + scriptedDescriptors).sortedWith(compareBy { it.name })
  }

  /**
   * [target]'s own tools on [driverType] — class-backed, catalog-scripted and inline-scripted —
   * each marked with why the in-session LLM is not offered it, if it isn't. The scripted branches
   * keep name-only entries pulled in by a target's `tool_sets:` or `tools:` in the listings, so
   * they match the set the executor accepts.
   */
  private fun listCustomTools(
    target: TrailblazeHostAppTarget,
    driverType: TrailblazeDriverType,
    catalog: ScriptedToolCatalog,
  ): List<ListedTool> = try {
    val excluded = getTargetExcludedToolNames(target, driverType)
    val listed = listedTools(
      target.getCustomToolsForDriver(driverType),
      target.getCustomScriptedToolNamesForDriver(driverType),
      catalog,
      excluded,
    )
    // Inline first: the session registers a target's own scripted tool over a catalog one of the
    // same name, so its visibility is the one that holds.
    (inlineListedTools(target, driverType).map { it.second } + listed)
      .distinctBy { it.descriptor.name }
      .sortedWith(compareBy { it.descriptor.name })
  } catch (_: Exception) {
    emptyList()
  }

  /**
   * The tools [target] offers on [platform] with no device connected: each driver a run of this
   * target can reach contributes its own surface minus its `excluded_tools:`, merged. A tool one
   * driver excludes stays when another reachable driver on the platform supports and permits it. A
   * driver no run can reach never puts one back: a retired driver, any driver on a platform the
   * target doesn't declare, or `ANDROID_TEST` on a target with no in-process harness.
   */
  private fun getPermittedToolDescriptorsForPlatform(
    target: TrailblazeHostAppTarget,
    platform: TrailblazeDevicePlatform,
    catalog: ScriptedToolCatalog,
  ): List<TrailblazeToolDescriptor> =
    listPermittedToolsForPlatform(target, platform, catalog).filter { it.notOffered == null }.map { it.descriptor }

  /**
   * [getPermittedToolDescriptorsForPlatform] plus the tools it leaves out, each marked: a tool is
   * offered when any reachable driver offers it, otherwise it carries the first driver's reason.
   */
  private fun listPermittedToolsForPlatform(
    target: TrailblazeHostAppTarget,
    platform: TrailblazeDevicePlatform,
    catalog: ScriptedToolCatalog,
  ): List<ListedTool> {
    val byName = linkedMapOf<String, ListedTool>()
    reachableDrivers(target)
      .filter { it.platform == platform }
      .forEach { driverType ->
        val systemExcluded = getExcludedToolNames(target = null, driverType = driverType)
        for (listed in listCustomTools(target, driverType, catalog)) {
          val name = listed.descriptor.name
          if (name in systemExcluded) continue
          val existing = byName[name]
          if (existing == null || (existing.notOffered != null && listed.notOffered == null)) byName[name] = listed
        }
      }
    return byName.values.sortedWith(compareBy { it.descriptor.name })
  }

  /**
   * Get custom tool descriptors for all driver types on a platform.
   * Used as fallback when no device is connected.
   *
   * Same class+scripted+inline union as [listCustomTools], collapsed across every
   * driver for [platform] so a target's full surface area shows up regardless of which driver the
   * caller eventually picks. Scripted names are unioned across drivers first, then described in a
   * single [InProcessScriptedToolLauncher.describe] call against the request's shared [catalog]
   * rather than per-driver.
   */
  private fun getCustomToolDescriptorsForPlatform(
    target: TrailblazeHostAppTarget,
    platform: TrailblazeDevicePlatform,
    catalog: ScriptedToolCatalog,
    includeHidden: Boolean = false,
  ): List<TrailblazeToolDescriptor> {
    return try {
      val driverTypes = TrailblazeDriverType.entries.filter { it.platform == platform }
      val classDescriptors = driverTypes
        .flatMap { driverType -> target.getCustomToolsForDriver(driverType) }
        .distinct()
        .mapNotNull { it.toTrailblazeToolDescriptorWithSource() }
      val scriptedNames = driverTypes
        .flatMap { driverType -> target.getCustomScriptedToolNamesForDriver(driverType) }
        .toSet()
      val scriptedDescriptors = InProcessScriptedToolLauncher.describe(scriptedNames, catalog)
      (classDescriptors + scriptedDescriptors + getInlineToolDescriptorsForPlatform(target, platform, includeHidden))
        .distinctBy { it.name }
        .sortedWith(compareBy { it.name })
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun buildInlineScriptToolsetsForDriver(
    target: TrailblazeHostAppTarget,
    inline: List<Pair<String, ListedTool>>,
    detail: Boolean,
  ): List<ToolDiscoveryToolsetInfo> =
    inline
      .groupBy(keySelector = { it.first }, valueTransform = { it.second })
      .mapNotNull { (toolsetId, listed) ->
        toolsetInfo(
          name = "${target.id}/$toolsetId",
          description = "${target.displayName} scripted tools",
          listed = listed,
          detail = detail,
        )
      }
      .sortedBy { it.name }

  /** The connected device's classifiers when [driverType] is its driver; otherwise unknown. */
  private fun classifiersFor(driverType: TrailblazeDriverType): List<TrailblazeDeviceClassifier>? =
    if (driverType == currentDriverTypeProvider()) currentDeviceClassifiersProvider() else null

  /**
   * Names of the inline tools in [inline]. A session registers a target's own scripted tool over a
   * catalog tool of the same name, so grouped listings drop the catalog copy.
   */
  private fun inlineToolNames(inline: List<Pair<String, ListedTool>>): Set<String> =
    inline.mapTo(mutableSetOf()) { it.second.descriptor.name }

  /**
   * [target]'s inline-scripted tools that register on [driverType], keyed by toolset id and marked:
   * hidden by their own `surfaceToLlm: false`, or excluded by the target's `excluded_tools:`.
   */
  private fun inlineListedTools(
    target: TrailblazeHostAppTarget,
    driverType: TrailblazeDriverType,
  ): List<Pair<String, ListedTool>> {
    val excluded = excludedInlineToolNames(target, driverType)
    return target.getInlineScriptTools().mapNotNull { tool ->
      val meta = tool.toolMeta()
      if (!meta.shouldRegister(driverType, preferHostAgent = true, classifiersFor(driverType))) return@mapNotNull null
      (meta.toolset ?: "scripted") to ListedTool(
        inlineToolToDescriptor(tool),
        notOfferedReason(!tool.surfacesToLlm(meta), tool.name in excluded),
      )
    }
  }

  /**
   * The target's own scripted tools that `excluded_tools:` opts out of on [driverType]. A
   * trailmap's `.ts` tools are on its target unlisted, so the opt-out is the only thing keeping
   * one out of a listing.
   */
  private fun excludedInlineToolNames(target: TrailblazeHostAppTarget, driverType: TrailblazeDriverType): Set<String> =
    target.getExcludedToolSurfaceForDriver(driverType).scriptedToolNames.mapTo(mutableSetOf()) { it.toolName }

  private fun getInlineToolDescriptorsForPlatform(
    target: TrailblazeHostAppTarget,
    platform: TrailblazeDevicePlatform,
    includeHidden: Boolean = false,
  ): List<TrailblazeToolDescriptor> =
    target.getInlineScriptTools().mapNotNull { tool ->
      val meta = tool.meta?.let(TrailblazeToolMeta::fromJsonObject) ?: TrailblazeToolMeta()
      if ((!includeHidden && !tool.surfacesToLlm(meta)) || !meta.shouldRegisterForPlatform(platform, preferHostAgent = true)) {
        null
      } else {
        inlineToolToDescriptor(tool)
      }
    }

  /**
   * Whether the session advertises [this] tool to the LLM: neither its top-level `surfaceToLlm`
   * nor its `_meta` turns it off. A hidden helper stays dispatchable, but toolbox must not offer
   * what `step` won't call.
   */
  private fun InlineScriptToolConfig.surfacesToLlm(meta: TrailblazeToolMeta): Boolean =
    surfaceToLlm && meta.surfaceToLlm

  private fun inlineToolToDescriptor(tool: InlineScriptToolConfig): TrailblazeToolDescriptor {
    val requiredNames = ((tool.inputSchema["required"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?: emptyList()).toSet()
    val properties = tool.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = mutableListOf<TrailblazeToolParameterDescriptor>()
    val optional = mutableListOf<TrailblazeToolParameterDescriptor>()

    properties.forEach { (name, schema) ->
      val schemaObj = schema as? JsonObject
      val flattenedUnion = schemaObj?.let { discriminatedUnionParameters(name, it, name in requiredNames) }
      if (flattenedUnion != null) {
        required += flattenedUnion.first
        optional += flattenedUnion.second
        return@forEach
      }
      val enumValues = (schemaObj?.get("enum") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      val descriptor = TrailblazeToolParameterDescriptor(
        name = name,
        type = jsonSchemaTypeLabel(schema),
        description = schemaObj?.get("description")
          ?.let { it as? JsonPrimitive }
          ?.contentOrNull,
        // Enum metadata flows through the same JSON schema `enum` array
        // `jsonSchemaTypeLabel` already inspects to decide the type label. Capturing the
        // values here lets the recording Tool Palette render a dropdown for inline-script
        // tools too — without it, only Koog-class enums would get the dropdown treatment.
        validValues = enumValues,
      )
      if (name in requiredNames) {
        required += descriptor
      } else {
        optional += descriptor
      }
    }

    return TrailblazeToolDescriptor(
      name = tool.name,
      description = tool.description,
      requiredParameters = required,
      optionalParameters = optional,
      source = trailblazeToolSourceForScript(tool.script),
    )
  }

  private fun discriminatedUnionParameters(
    parentName: String,
    schema: JsonObject,
    parentRequired: Boolean,
  ): Pair<List<TrailblazeToolParameterDescriptor>, List<TrailblazeToolParameterDescriptor>>? {
    val variants = (schema["anyOf"] as? JsonArray)
      ?.mapNotNull { it as? JsonObject }
      ?: return null
    if (variants.isEmpty()) return null

    val typeValues = mutableListOf<String>()
    val typeDescriptions = mutableListOf<String>()
    val associated = LinkedHashMap<String, AssociatedUnionParameter>()
    for (variant in variants) {
      val properties = variant["properties"] as? JsonObject ?: return null
      val typeSchema = properties["type"] as? JsonObject ?: return null
      val typeValue = (typeSchema["const"] as? JsonPrimitive)?.contentOrNull ?: return null
      typeValues += typeValue
      (typeSchema["description"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { typeDescriptions += "$typeValue: $it" }

      properties.forEach { (childName, childSchema) ->
        if (childName == "type") return@forEach
        val parameterName = "$parentName.$childName"
        associated.getOrPut(parameterName) {
          AssociatedUnionParameter(
            descriptor = TrailblazeToolParameterDescriptor(
              name = "$parentName.$childName",
              type = jsonSchemaTypeLabel(childSchema),
              description = (childSchema as? JsonObject)
                ?.get("description")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull,
              validValues = ((childSchema as? JsonObject)?.get("enum") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            ),
          )
        }.visibleForValues += typeValue
      }
    }

    val typeParam = TrailblazeToolParameterDescriptor(
      name = "$parentName.type",
      type = "String",
      description = buildString {
        append((schema["description"] as? JsonPrimitive)?.contentOrNull ?: "Select the $parentName variant.")
        if (typeDescriptions.isNotEmpty()) {
          append(" ")
          append(typeDescriptions.joinToString(" "))
        }
      },
      validValues = typeValues,
    )

    val required = if (parentRequired) listOf(typeParam) else emptyList()
    val optional = buildList {
      if (!parentRequired) add(typeParam)
      associated.values.forEach { associatedParam ->
        add(
          associatedParam.descriptor.copy().also {
            it.visibleWhen = TrailblazeToolParameterVisibility(
              parameterName = typeParam.name,
              values = associatedParam.visibleForValues.distinct(),
            )
          },
        )
      }
    }
    return required to optional
  }

  private data class AssociatedUnionParameter(
    val descriptor: TrailblazeToolParameterDescriptor,
    val visibleForValues: MutableList<String> = mutableListOf(),
  )

  private fun jsonSchemaTypeLabel(schema: JsonElement): String {
    val obj = schema as? JsonObject ?: return "Any"
    val enumValues = obj["enum"] as? JsonArray
    if (enumValues != null && enumValues.isNotEmpty()) {
      return "String"
    }
    return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
      "string" -> "String"
      "integer" -> "Int"
      "number" -> "Number"
      "boolean" -> "Boolean"
      "array" -> "Array"
      "object" -> "Object"
      else -> "Any"
    }
  }

  /**
   * Builds a usage hint for a specific tool showing how to invoke it via blaze().
   */
  private fun buildUsageHint(descriptor: TrailblazeToolDescriptor): String =
    "Automatic: blaze(objective=\"your objective\") — the inner agent selects this tool when appropriate.\n" +
      "Direct: blaze(objective=\"description\", tools=\"${toolYamlExample(descriptor)}\")"

  /**
   * The usage hint for a tool this session does not register (nothing offers it, or its toolset is
   * out of scope). [buildUsageHint] is false here by construction: no agent can select it, and the
   * `blaze(tools=…)` form is what the device/target gate refuses. What runs it is a trail step or a
   * TS tool, which resolves through the whole registry, on a device whose driver the tool runs on.
   */
  private fun buildTrailUsageHint(descriptor: TrailblazeToolDescriptor, runsOnlyOnDrivers: List<String>?): String {
    val tsTool = if (runsOnlyOnDrivers == null) {
      "From a TS tool"
    } else {
      "From a TS tool in a session on ${runsOnlyOnDrivers.joinToString(", ")}"
    }
    return "As a trail step:\n${toolYamlExample(descriptor)}\n" +
      "$tsTool: ctx.tools.${descriptor.name}(${toolArgsJsExample(descriptor)})"
  }

  /**
   * The usage hint for a tool a session-bound toolset offers to THIS session. It is in the
   * session's own tool list, not the inner agent's, so [buildUsageHint]'s `blaze(...)` forms are
   * the wrong door: the caller invokes it directly, or a trail step names it.
   */
  private fun buildSessionToolUsageHint(descriptor: TrailblazeToolDescriptor): String =
    "Call ${descriptor.name} directly — this session advertises it as its own tool.\n" +
      "In a trail's tools: block:\n${toolYamlExample(descriptor)}"

  /** The argument object of one `client.tools.<name>(…)` call, with placeholder values. */
  private fun toolArgsJsExample(descriptor: TrailblazeToolDescriptor): String {
    val allParams = descriptor.requiredParameters + descriptor.optionalParameters
    if (allParams.isEmpty()) return "{}"
    return allParams.joinToString(", ", prefix = "{ ", postfix = " }") { "${it.name}: ${placeholderFor(it.type)}" }
  }

  private fun placeholderFor(type: String): String = when {
    // Composites first: an `OBJECT` param (a restored selector, say) takes a nested block, and
    // `"value"` would be a type error rather than a starting point. Checked before the scalar
    // branches so a future type name that merely contains "int" can't claim it.
    type.equals("OBJECT", ignoreCase = true) -> "{ }"
    type.equals("ARRAY", ignoreCase = true) -> "[ ]"
    type.contains("String", ignoreCase = true) -> "\"value\""
    type.contains("Int", ignoreCase = true) || type.contains("Long", ignoreCase = true) -> "0"
    type.contains("Boolean", ignoreCase = true) -> "true"
    else -> "\"value\""
  }

  /** One tool call as it appears in a trail's `tools:` block, with placeholder argument values. */
  private fun toolYamlExample(descriptor: TrailblazeToolDescriptor): String {
    val allParams = descriptor.requiredParameters + descriptor.optionalParameters
    if (allParams.isEmpty()) return "- ${descriptor.name}"
    val params = allParams.joinToString("\n") { param -> "    ${param.name}: ${placeholderFor(param.type)}" }
    return "- ${descriptor.name}:\n$params"
  }

  companion object {
    private val jsonFormat = Json { prettyPrint = true; encodeDefaults = false }

    /**
     * Categories shown in the tool discovery index.
     * ALL is excluded since it's a superset; SESSION is excluded since those are Koog ToolSets.
     */
    val DISCOVERABLE_CATEGORIES = listOf(
      ToolSetCategory.CORE_INTERACTION,
      ToolSetCategory.NAVIGATION,
      ToolSetCategory.OBSERVATION,
      ToolSetCategory.VERIFICATION,
      ToolSetCategory.MEMORY,
    )

    /**
     * Tools that are internal agent machinery and should not appear in discovery output.
     * These are used by the agent framework itself, not invoked by users or external agents.
     */
    val SYSTEM_INTERNAL_TOOLS: Set<KClass<out TrailblazeTool>> = setOf(
      ObjectiveStatusTrailblazeTool::class,
    )

    /**
     * What a lookup says about a tool that exists but that nothing offers — no toolset in the
     * catalogue lists it. A tool some toolset lists that this session merely has out of scope gets
     * a note naming that toolset instead; see [toolSetStanding].
     *
     * The honest scope matters here, because the two halves point opposite ways: nothing will
     * *choose* this tool — it is in no toolset, so no agent sees it and `trailblaze tool <name>`
     * refuses to run it — yet the name is real, so a recorded trail that names it still replays.
     * `tapOn` is the case that motivated this: the recorder writes it when it upgrades a
     * coordinate tap to a selector, and every lookup surface used to answer "not found".
     *
     * The `trailblaze tool` half of that is not a guess: the CLI's `tool` command ends at
     * `callTool("step", …)`, so it lands in [StepToolSet]'s device/target gate — the gate
     * `StepToolSetDirectToolsTest` drives directly.
     */
    private const val NOT_OFFERED_OPENING: String = "Not offered by any toolset, so"

    /**
     * The consequence shared by every "a toolset offers it, but not to this session" verdict in
     * `toolSetStanding`: the same two facts as [NOT_OFFERED_AVAILABILITY], minus its opening claim.
     */
    private const val NOT_OFFERED_TAIL: String =
      "no agent here sees it and `trailblaze tool` can't call it from this session; a trail step or a " +
        "TS tool (`ctx.tools.<name>`) can."

    internal const val NOT_OFFERED_AVAILABILITY: String = "$NOT_OFFERED_OPENING $NOT_OFFERED_TAIL"

    /**
     * What a lookup for an unrecognized tool name says back.
     *
     * Names the closest real tools. A bare "not found" is least believable exactly here, where
     * the reader usually arrived holding a name they saw somewhere real, so the reply should
     * carry them toward the tool they meant. Names the registry knows never reach this message —
     * they get the descriptor and [NOT_OFFERED_AVAILABILITY] instead.
     *
     * The recovery is phrased as the command to run. This string is read by CLI users as often as
     * by agents, and `Use toolbox()` is a function call in neither of their vocabularies.
     */
    internal fun unknownToolLookupMessage(name: String, knownToolNames: List<String>): String {
      val didYouMean = ToolNameSuggestions.didYouMeanSuffix(name, knownToolNames)
      return "Tool '$name' not found.$didYouMean Run 'trailblaze toolbox' to list every tool " +
        "available for the current device and target."
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Result types (serializable for JSON output)
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ToolDiscoveryIndexResult(
  val currentTarget: String? = null,
  val currentPlatform: String? = null,
  val currentDriverType: String? = null,
  /**
   * Inlined contents of the resolved target's `system_prompt_file:` — the curated LLM-facing
   * prose the framework agent receives at session start. Null when the target has no system
   * prompt configured. The CLI surfaces this verbatim under a `## System prompt` section so
   * a shell-side agent (Claude / Codex / etc.) sees the same context as the in-session LLM.
   */
  val systemPrompt: String? = null,
  val platformToolsets: List<ToolDiscoveryToolsetInfo> = emptyList(),
  val targetToolsets: List<ToolDiscoveryToolsetInfo>? = null,
  val otherTargets: List<ToolDiscoveryOtherTarget>? = null,
  val usage: String? = null,
  /**
   * Tool names whose YAML config carries a non-null `trailhead:` metadata block — i.e. tools
   * that bring the device to a known starting state (sign-in, launch, deep-link). Parallel slim
   * view alongside [platformToolsets] / [targetToolsets]: callers that want to render trailheads
   * as their own section (or accept a `trailheads` positional filter) read this list and
   * cross-reference into the toolset entries for descriptions. Not a property of the per-tool
   * [TrailblazeToolDescriptor] by design — see the kdoc on that data class for why capability
   * flags don't ride on the shared descriptor; this is a response-level enrichment.
   */
  val trailheadTools: List<String> = emptyList(),
  /** How many of this target's tools on this driver the in-session LLM is not offered. See [ToolNotOfferedReason]. */
  val notOfferedToolCount: Int? = null,
  /** One line pointing at `all=true` when [notOfferedToolCount] tools were left out of this listing. */
  val notOfferedNote: String? = null,
  /**
   * True when the listing was asked for with `all=true` and includes the tools the LLM is not
   * offered. A daemon that predates `all` never sets it, which is how a client tells the two apart.
   */
  val includesNotOffered: Boolean? = null,
  // `error` is the contract the CLI formatter (`ToolboxCommand.formatToolsResult`) keys off to
  // short-circuit to `Console.error(...)` regardless of which mode-specific formatter would
  // otherwise run. Toolbox-level validation failures (e.g. unknown `--device` platform) set this
  // so a `--target=foo --device=typo` call doesn't get misrouted through `formatTargetResult` and
  // emit a misleading "Target not found" line.
  val error: String? = null,
)

@Serializable
data class ToolDiscoveryToolsetInfo(
  val name: String,
  val description: String,
  /** Tool names (index mode, when detail=false). */
  val tools: List<String>? = null,
  /** Full tool descriptors (detail mode, when detail=true). */
  val toolDetails: List<TrailblazeToolDescriptor>? = null,
  /** Tools listed here that the in-session LLM is not offered, and why. Present only with `all=true`. */
  val notOffered: Map<String, ToolNotOfferedReason>? = null,
)

/**
 * Why a tool a target can call is not on the in-session LLM's menu. Either way `trailblaze tool`, a
 * trail step, or a TS tool's `ctx.tools.<name>(…)` still calls it: the LLM is offered the tools that drive a
 * flow, and these are for specific use cases it should not reach for all the time.
 */
@Serializable
enum class ToolNotOfferedReason {
  /** The tool sets `surfaceToLlm: false` itself, so no target offers it. */
  @SerialName("hidden_by_tool")
  HIDDEN_BY_TOOL,

  /** The target drops it on this driver with `platforms.<p>.excluded_tools:`. */
  @SerialName("excluded_by_target")
  EXCLUDED_BY_TARGET,
}

@Serializable
data class ToolDiscoveryOtherTarget(
  val name: String,
  val platforms: List<String>? = null,
)

@Serializable
data class ToolDiscoveryNameResult(
  val tool: TrailblazeToolDescriptor? = null,
  val foundInCategories: List<String>? = null,
  val foundInTargets: List<String>? = null,
  /**
   * Set only when the tool was found outside the catalogue of offered tools: nothing offers it
   * ([ToolDiscoveryToolSet.NOT_OFFERED_AVAILABILITY]), or a toolset this session does not have in
   * scope does, or a session-bound toolset this session has bound does. Null is the ordinary
   * case and means the tool is offered, as [foundInCategories] / [foundInTargets] then say where.
   */
  val availability: String? = null,
  /** Set when the tool was found but the in-session LLM is not offered it on the scoped target and driver. */
  val notOffered: ToolNotOfferedReason? = null,
  val usage: String? = null,
  val error: String? = null,
)

@Serializable
data class ToolDiscoveryTargetResult(
  val target: String? = null,
  val displayName: String? = null,
  val currentPlatform: String? = null,
  val supportedPlatforms: List<String>? = null,
  /**
   * Inlined contents of this target's `system_prompt_file:` — see
   * [ToolDiscoveryIndexResult.systemPrompt] for the contract.
   */
  val systemPrompt: String? = null,
  /** Grouped tools for the current driver (when a device is connected). */
  val toolGroups: List<ToolDiscoveryToolsetInfo>? = null,
  /** Flat tools by platform (fallback when no device is connected). */
  val toolsByPlatform: List<ToolDiscoveryTargetPlatformTools>? = null,
  /**
   * Tool names with trailhead role metadata, scoped to this target. Same shape and rationale
   * as [ToolDiscoveryIndexResult.trailheadTools]; populated here so `toolbox trailheads
   * --target=<non-default>` doesn't silently report an empty list (the CLI receives a
   * target-mode envelope, not index-mode, when a non-default target is requested).
   */
  val trailheadTools: List<String> = emptyList(),
  /** Same as [ToolDiscoveryIndexResult.notOfferedToolCount], for this target. */
  val notOfferedToolCount: Int? = null,
  /** Same as [ToolDiscoveryIndexResult.notOfferedNote]. */
  val notOfferedNote: String? = null,
  /** Same as [ToolDiscoveryIndexResult.includesNotOffered]. */
  val includesNotOffered: Boolean? = null,
  val error: String? = null,
)

@Serializable
data class ToolDiscoveryTargetPlatformTools(
  val platform: String,
  val tools: List<TrailblazeToolDescriptor>,
  /** With `all=true`: the tools here the in-session LLM is not offered, and why. */
  val notOffered: Map<String, ToolNotOfferedReason>? = null,
)

@Serializable
data class ToolDiscoverySearchResult(
  val query: String? = null,
  val matches: List<ToolDiscoverySearchMatch>? = null,
  /** How many matching tools the in-session LLM is not offered; left out of [matches] unless `all=true`. */
  val notOfferedToolCount: Int? = null,
  /** Same as [ToolDiscoveryIndexResult.notOfferedNote], for the matches. */
  val notOfferedNote: String? = null,
  /** Same as [ToolDiscoveryIndexResult.includesNotOffered]. */
  val includesNotOffered: Boolean? = null,
  val error: String? = null,
)

@Serializable
data class ToolDiscoverySearchMatch(
  val tool: TrailblazeToolDescriptor,
  val source: String,
  /** Why the in-session LLM is not offered this match; null when it is. */
  val notOffered: ToolNotOfferedReason? = null,
)

/**
 * Combines class-backed and scripted tools from a [TrailblazeHostAppTarget.ToolGroup] into a
 * single descriptor list, for [DeviceManagerToolSet]'s per-group tool summary, so scripted entries
 * flow through alongside class-backed tools. `toolbox` listings go through `listedTools` instead,
 * which also marks the tools the LLM is not offered.
 *
 * Class descriptors take precedence on name collision (class-backed implementations are
 * authoritative; scripted names are descriptors only).
 * `distinctBy { name }` defends against a future hand-built `ToolGroup` that lists the same name in
 * more than one backing — today's [YamlBackedHostAppTarget] resolver enforces mutual exclusion via
 * a typed `when`, but the data model itself does not.
 *
 * Scripted (`.ts` / `.js`) descriptors come from [InProcessScriptedToolLauncher.describe], which
 * resolves each name via `ScriptedToolNameDiscoverer` (trailmap `tools/` descriptor YAMLs on the
 * classpath + workspace overlay) — the same resolution the resolved-surface and non-grouped
 * discovery paths use. So a target whose custom tools are scripted shows up in grouped discovery
 * output alongside class-backed tools — the discovery-grouping leg of the tool-backing parity.
 *
 * Scripted descriptors are read through [catalog]; a caller merging many groups in one request
 * passes the same catalog to each so the descriptor tree is walked once. Required, not defaulted,
 * so a new caller has to thread its request's catalog to compile — a default here silently buys
 * back one descriptor walk per group, which is the cost this parameter exists to remove.
 */
internal fun TrailblazeHostAppTarget.ToolGroup.toMergedDescriptors(
  catalog: ScriptedToolCatalog,
): List<TrailblazeToolDescriptor> {
  val classDescriptors = toolClasses
    .mapNotNull { it.toTrailblazeToolDescriptorWithSource() }
  val scriptedDescriptors = InProcessScriptedToolLauncher.describe(scriptedToolNames, catalog)
  return (classDescriptors + scriptedDescriptors).distinctBy { it.name }
}
