package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
import xyz.block.trailblaze.scripting.InProcessScriptedToolLauncher
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.scripting.mcp.shouldRegisterForPlatform
import xyz.block.trailblaze.toolcalls.KoogToolExt
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.getExcludedToolSurfaceForDriver
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterVisibility
import xyz.block.trailblaze.toolcalls.toTrailblazeToolDescriptorWithSource
import xyz.block.trailblaze.toolcalls.trailblazeToolSourceForScript
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass

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
   * Resource source used to discover `*.trailhead.yaml` / `*.shortcut.yaml` configs for role
   * enrichment. Injected by the host bootstrap when a workspace config dir is in play (so a
   * workspace-authored trailhead can appear in `trailheadTools`); falls back to the platform's
   * classpath scan otherwise — same default as [ToolYamlLoader.discoverShortcutsAndTrailheads].
   *
   * This provider exists purely so the host can layer in the same workspace `ConfigResourceSource`
   * it already uses for target / toolset discovery. When the host doesn't thread one through, the
   * server still resolves all classpath-bundled role tools correctly — the gap is only for
   * workspace-authored role YAMLs that aren't on the classpath.
   */
  private val resourceSourceProvider: () -> ConfigResourceSource = { platformConfigResourceSource() },
) : ToolSet {

  @LLMDescription(
    """
    Discover available Trailblaze tools.

    toolbox() → index of all tool categories and available targets
    toolbox(detail=true) → full parameter details for every tool
    toolbox(name="tap") → single tool with full descriptor
    toolbox(target="sampleapp") → tools for a specific target app

    Use this to understand what actions are possible before calling blaze().
    """
  )
  @Tool(McpToolProfile.TOOL_TOOLS)
  suspend fun toolbox(
    @LLMDescription("Filter to a single tool by name") name: String? = null,
    @LLMDescription("Filter to a specific target app's tools") target: String? = null,
    @LLMDescription("Substring search on tool name and description.") search: String? = null,
    @LLMDescription("Target device (e.g. android, android/emulator-5554).") platform: String? = null,
    @LLMDescription("Expand tools with full parameter descriptions") detail: Boolean? = null,
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
    return when {
      // NAME mode intentionally spans all platforms — looking up a tool by exact name is "find me
      // this tool wherever it's defined" rather than "what's runnable on the current device." The
      // result's `foundInCategories` / `foundInTargets` already tell the caller which platforms
      // the tool lives on. If `platform` was passed here it's accepted but ignored on purpose.
      name != null -> handleNameMode(name)
      search != null -> handleSearchMode(search, target, platformFilter)
      target != null && !isDefaultTarget -> handleTargetMode(target, detail ?: false, platformFilter)
      else -> handleIndexMode(detail ?: false, platformFilter, suppressTargetTools = isDefaultTarget)
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

    val excludedToolNames = getExcludedToolNames(currentTarget, effectiveDriverType)
    val platformToolsets = buildPlatformToolsets(detail, excludedToolNames, effectiveDriverType)
    val targetToolsets = if (suppressTargetTools) null else buildTargetToolsets(currentTarget, effectiveDriverType, detail)
    // Only show "other targets" hint when no device is connected (target tools not listed)
    val otherTargets = if (targetToolsets == null) buildOtherTargets(currentTarget, allTargets) else null

    // Enrich the response with role-grouped slim views (trailheads / shortcuts). Trailheads come
    // from two sources — see computeRoleNames: YAML-side `*.trailhead.yaml` metadata, and scripted
    // (.ts) tools that self-declare `trailhead:` inline in their spec (InlineScriptToolConfig.trailhead).
    // Filtered to tool names actually surfaced in the current platform / target toolsets so the
    // role lists never reference tools the CLI can't show details for.
    val (trailheadToolNames, shortcutToolNames) =
      computeRoleNames(platformToolsets, targetToolsets, target = currentTarget)

    val result = ToolDiscoveryIndexResult(
      currentTarget = currentTarget?.id,
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
      shortcutTools = shortcutToolNames,
    )
    return jsonFormat.encodeToString(result)
  }

  /**
   * Returns `(trailheadNames, shortcutNames)` filtered to tool names that appear in the in-scope
   * toolsets. The intersection guard means the CLI's "render trailheads grouped" pass never
   * names a tool the user can't drill into via `toolbox --name <id>`.
   *
   * Accepts a heterogeneous list of toolset sources so both [handleIndexMode] (platform +
   * target toolsets) and [handleTargetMode] (toolGroups, or flat toolsByPlatform) can share the
   * intersection logic. Each source contributes its in-scope tool ids via either the compact
   * `tools` list or the detailed `toolDetails` list — the helper accepts whichever shape the
   * caller has on hand.
   *
   * Trailhead names are the union of two sources: YAML-side `*.trailhead.yaml` /
   * `*.shortcut.yaml` metadata (via [ToolYamlLoader.discoverShortcutsAndTrailheads]), and
   * scripted (`.ts`) tools that self-declare `trailhead:` inline in their
   * `TrailblazeTypedToolSpec` instead of a sidecar YAML — those land on
   * [xyz.block.trailblaze.config.InlineScriptToolConfig.trailhead], reachable off [target]'s
   * [xyz.block.trailblaze.model.TrailblazeHostAppTarget.getInlineScriptTools]. Shortcuts have no
   * scripted-tool equivalent yet, so that half stays YAML-only.
   *
   * @param target the app target whose scripted tools to scan for inline trailheads.
   *   [handleIndexMode] passes the daemon's currently-connected target; [handleTargetMode] passes
   *   the explicitly-requested target. No default — every caller must decide, so a future call
   *   site can't silently inherit the wrong target for its mode (`toolbox trailheads
   *   --target=<other>` reporting the daemon's connected target's scripted trailheads instead of
   *   the requested one).
   */
  private fun computeRoleNames(
    vararg toolsetSources: List<ToolDiscoveryToolsetInfo>?,
    extraInScopeNames: Collection<String> = emptyList(),
    target: TrailblazeHostAppTarget?,
  ): Pair<List<String>, List<String>> {
    val inScopeNames: Set<String> = buildSet {
      toolsetSources.forEach { sets ->
        sets?.forEach { ts ->
          ts.tools?.let { addAll(it) }
          ts.toolDetails?.forEach { add(it.name) }
        }
      }
      addAll(extraInScopeNames)
    }
    // Use the injected resource source so a workspace-authored *.trailhead.yaml / *.shortcut.yaml
    // is also visible — otherwise we'd silently drop workspace role tools that ARE present in
    // the in-scope toolsets (because the host's target/toolset discovery uses a layered source).
    val allRoles = ToolYamlLoader.discoverShortcutsAndTrailheads(resourceSourceProvider())
    val yamlTrailheadNames = allRoles.values.filter { it.trailhead != null }.map { it.id }
    val scriptedTrailheadNames = target?.getInlineScriptTools()
      .orEmpty()
      .filter { it.trailhead != null }
      .map { it.name }
    val trailheads = (yamlTrailheadNames + scriptedTrailheadNames)
      .filter { it in inScopeNames }
      .distinct()
      .sorted()
    val shortcuts = allRoles.values
      .filter { it.shortcut != null && it.id in inScopeNames }
      .map { it.id }
      .sorted()
    return trailheads to shortcuts
  }

  private fun handleNameMode(name: String): String {
    // Search all platform tools
    val allPlatformTools = DISCOVERABLE_CATEGORIES.flatMap { category ->
      getToolDescriptorsForCategory(category).map { descriptor ->
        descriptor to category
      }
    }

    // Search current target tools
    val currentTarget = currentTargetProvider()
    val currentDriverType = currentDriverTypeProvider()
    val targetTools = if (currentTarget != null && currentDriverType != null) {
      getCustomToolDescriptors(currentTarget, currentDriverType).map { descriptor ->
        descriptor to currentTarget.id
      }
    } else {
      emptyList()
    }

    // Search all other target tools
    val allTargets = allTargetAppsProvider()
    val allTargetTools = allTargets.flatMap { appTarget ->
      TrailblazeDevicePlatform.entries.flatMap { platform ->
        getCustomToolDescriptorsForPlatform(appTarget, platform).map { descriptor ->
          descriptor to appTarget.id
        }
      }
    }

    val allTools = allPlatformTools + targetTools + allTargetTools

    val match = allTools.firstOrNull { (descriptor, _) ->
      descriptor.name.equals(name, ignoreCase = true)
    }

    if (match == null) {
      return jsonFormat.encodeToString(
        ToolDiscoveryNameResult(
          error = "Tool '$name' not found. Use toolbox() to see all available tools.",
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

    return jsonFormat.encodeToString(
      ToolDiscoveryNameResult(
        tool = descriptor,
        foundInCategories = foundInCategories.ifEmpty { null },
        foundInTargets = foundInTargets.ifEmpty { null },
        usage = buildUsageHint(descriptor),
      )
    )
  }

  private fun handleTargetMode(targetId: String, detail: Boolean, platformFilter: TrailblazeDevicePlatform? = null): String {
    val allTargets = allTargetAppsProvider()
    val targetApp = allTargets.firstOrNull { it.id.equals(targetId, ignoreCase = true) }

    if (targetApp == null) {
      val available = allTargets.map { it.id }
      return jsonFormat.encodeToString(
        ToolDiscoveryTargetResult(
          error = "Target '$targetId' not found. Available targets: ${available.joinToString(", ")}",
        )
      )
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
      // Apply targetApp's own exclusions so a `excluded_tools: [pressBack]` declaration in the
      // target YAML doesn't leak into the listing — discovery output should reflect what the
      // executor actually accepts when this target is current.
      val excludedToolNames = getExcludedToolNames(targetApp, effectiveDriverType)
      val classGroups = targetApp.getCustomToolGroupsForDriver(effectiveDriverType).mapNotNull { group ->
        val descriptors = group.toMergedDescriptors()
          .filter { it.name !in excludedToolNames }
          .sortedWith(compareBy { it.name })
        if (descriptors.isEmpty()) return@mapNotNull null
        ToolDiscoveryToolsetInfo(
          name = group.id,
          description = group.description,
          tools = if (detail) null else descriptors.map { it.name },
          toolDetails = descriptors,
        )
      }
      val toolGroups = classGroups + buildInlineScriptToolsetsForDriver(targetApp, effectiveDriverType, detail)

      // Mirror the index-mode role enrichment so `toolbox trailheads --target=<non-default>`
      // gets the role lists it needs — otherwise the CLI's role-filter view always reports
      // "No trailheads available" for any non-default target. Scope the intersection guard to
      // this target's own toolGroups so we never surface a tool that won't appear when the
      // user drills in via `--name <id>`.
      val (trailheadToolNames, shortcutToolNames) = computeRoleNames(toolGroups, target = targetApp)

      return jsonFormat.encodeToString(
        ToolDiscoveryTargetResult(
          target = targetApp.id,
          displayName = targetApp.displayName,
          currentPlatform = effectiveDriverType.platform.displayName,
          supportedPlatforms = supportedPlatforms,
          systemPrompt = systemPromptForTarget(targetApp),
          toolGroups = toolGroups.ifEmpty { null },
          trailheadTools = trailheadToolNames,
          shortcutTools = shortcutToolNames,
        )
      )
    }

    // No device connected and no platform filter — show all platforms flat. Each platform's
    // listing is filtered by targetApp's exclusions for that platform's drivers, mirroring the
    // device-connected branch above.
    val platformTools = TrailblazeDevicePlatform.entries.mapNotNull { platform ->
      val excludedForPlatform = TrailblazeDriverType.entries
        .filter { it.platform == platform }
        .flatMap { getExcludedToolNames(targetApp, it) }
        .toSet()
      val tools = getCustomToolDescriptorsForPlatform(targetApp, platform)
        .filter { it.name !in excludedForPlatform }
      if (tools.isEmpty()) return@mapNotNull null
      ToolDiscoveryTargetPlatformTools(
        platform = platform.displayName,
        tools = if (detail) tools else tools.map { it.copy(requiredParameters = emptyList(), optionalParameters = emptyList()) },
      )
    }

    // Flat-by-platform also gets role enrichment so role filtering works even when no device is
    // connected. Tool ids here come from the per-platform [TrailblazeToolDescriptor] lists rather
    // than a toolset shape, so feed them via the extraInScopeNames hook.
    val flatInScopeNames = platformTools.flatMap { it.tools.map { d -> d.name } }
    val (trailheadToolNames, shortcutToolNames) =
      computeRoleNames(extraInScopeNames = flatInScopeNames, target = targetApp)

    return jsonFormat.encodeToString(
      ToolDiscoveryTargetResult(
        target = targetApp.id,
        displayName = targetApp.displayName,
        supportedPlatforms = supportedPlatforms,
        systemPrompt = systemPromptForTarget(targetApp),
        toolsByPlatform = platformTools.ifEmpty { null },
        trailheadTools = trailheadToolNames,
        shortcutTools = shortcutToolNames,
      )
    )
  }

  private fun handleSearchMode(
    query: String,
    targetFilter: String?,
    platformFilter: TrailblazeDevicePlatform? = null,
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
    val excludedToolNames = getExcludedToolNames(currentTarget, effectiveDriverType)
    val platformToolsets = buildPlatformToolsets(
      detail = true, excludedToolNames = excludedToolNames, driverType = effectiveDriverType,
    )
    for (toolset in platformToolsets) {
      toolset.toolDetails?.filter { matches(it) }?.forEach { descriptor ->
        results.add(
          ToolDiscoverySearchMatch(
            tool = descriptor,
            source = toolset.name.replaceFirstChar { it.titlecase() },
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
        getCustomToolDescriptors(target, effectiveDriverType)
          .filter { matches(it) }
          .forEach { descriptor ->
            results.add(
              ToolDiscoverySearchMatch(
                tool = descriptor,
                source = "${target.displayName} (${effectiveDriverType.platform.displayName})",
              )
            )
          }
      } else {
        TrailblazeDevicePlatform.entries.forEach { platform ->
          getCustomToolDescriptorsForPlatform(target, platform)
            .filter { matches(it) }
            .forEach { descriptor ->
              results.add(
                ToolDiscoverySearchMatch(
                  tool = descriptor,
                  source = "${target.displayName} (${ platform.displayName})",
                )
              )
            }
        }
      }
    }

    // Deduplicate by tool name (prefer first match)
    val deduped = results.distinctBy { it.tool.name }

    return jsonFormat.encodeToString(
      ToolDiscoverySearchResult(
        query = query,
        matches = deduped.ifEmpty { null },
      )
    )
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

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
    excludedToolNames: Set<String> = emptySet(),
    driverType: TrailblazeDriverType? = null,
  ): List<ToolDiscoveryToolsetInfo> {
    val defaultTarget = allTargetAppsProvider().find { it.id == DefaultTrailblazeHostAppTarget.id }
    if (defaultTarget != null) {
      // Use a driver type for group filtering (default to Android if none provided)
      val effectiveDriver = driverType ?: TrailblazeDriverType.DEFAULT_ANDROID
      val groups = defaultTarget.getCustomToolGroupsForDriver(effectiveDriver)
      if (groups.isNotEmpty()) {
        return groups.mapNotNull { group ->
          val descriptors = group.toMergedDescriptors()
            .filter { it.name !in excludedToolNames }
            .sortedWith(compareBy { it.name })
          if (descriptors.isEmpty()) return@mapNotNull null
          ToolDiscoveryToolsetInfo(
            name = group.id,
            description = group.description,
            tools = if (detail) null else descriptors.map { it.name },
            toolDetails = descriptors,
          )
        }
      }
    }

    // Fallback: use hardcoded categories (open source without default target YAML)
    return DISCOVERABLE_CATEGORIES.mapNotNull { category ->
      val descriptors = getToolDescriptorsForCategory(category)
        .filter { it.name !in excludedToolNames }
      if (descriptors.isEmpty()) return@mapNotNull null
      ToolDiscoveryToolsetInfo(
        name = category.name.lowercase(),
        description = category.description,
        tools = if (detail) null else descriptors.map { it.name },
        toolDetails = descriptors,
      )
    }
  }

  private fun buildTargetToolsets(
    currentTarget: TrailblazeHostAppTarget?,
    currentDriverType: TrailblazeDriverType?,
    detail: Boolean,
  ): List<ToolDiscoveryToolsetInfo>? {
    // Show tool groups from ALL targets so toolbox reflects every custom
    // tool that is actually executable (the daemon loads all targets' tools).
    val targets = allTargetAppsProvider().filter { it.id != DefaultTrailblazeHostAppTarget.id }.toList()
    if (targets.isEmpty()) return null

    if (currentDriverType != null) {
      // Device connected — show grouped tools for the current driver. Each target's listing is
      // filtered by THAT target's own exclusions so an `excluded_tools:` declaration is honored
      // consistently with platform listings.
      val allGroups = targets.flatMap { target ->
        val excludedToolNames = getExcludedToolNames(target, currentDriverType)
        val classGroups = target.getCustomToolGroupsForDriver(currentDriverType).mapNotNull { group ->
          val descriptors = group.toMergedDescriptors()
            .filter { it.name !in excludedToolNames }
            .sortedWith(compareBy { it.name })
          if (descriptors.isEmpty()) return@mapNotNull null
          ToolDiscoveryToolsetInfo(
            name = "${target.id}/${group.id}",
            description = group.description,
            tools = if (detail) null else descriptors.map { it.name },
            toolDetails = descriptors,
          )
        }
        classGroups + buildInlineScriptToolsetsForDriver(target, currentDriverType, detail)
      }
      return allGroups.ifEmpty { null }
    }

    // No device connected — show flat tools per target/platform. Apply per-target, per-platform
    // exclusions for the same reason as the device-connected branch above.
    val allGroups = targets.flatMap { target ->
      TrailblazeDevicePlatform.entries.mapNotNull { platform ->
        val excludedForPlatform = TrailblazeDriverType.entries
          .filter { it.platform == platform }
          .flatMap { getExcludedToolNames(target, it) }
          .toSet()
        val descriptors = getCustomToolDescriptorsForPlatform(target, platform)
          .filter { it.name !in excludedForPlatform }
        if (descriptors.isEmpty()) return@mapNotNull null
        ToolDiscoveryToolsetInfo(
          name = "${target.id} (${platform.displayName})",
          description = "${target.displayName} tools for ${platform.displayName}",
          tools = if (detail) null else descriptors.map { it.name },
          toolDetails = descriptors,
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

    if (driverType == null || target == null) return systemExclusions

    val targetExclusions = try {
      // One read of the target's `excluded_tools:` surface across all three backings — the same
      // central accessor the resolver / runtime / on-device paths use. This keeps discovery's
      // hidden set in lockstep with what the agent actually sees: a target's
      // `excluded_tools: [pressBack]` (YAML) or `[openUrl]` (scripted) is hidden here exactly as
      // it's dropped from the LLM surface.
      val excluded = target.getExcludedToolSurfaceForDriver(driverType)
      // Class exclusions resolve to their advertised descriptor name (the @TrailblazeToolClass
      // name); YAML + scripted exclusions are already names.
      val classExclusions = excluded.toolClasses
        .mapNotNull { it.toTrailblazeToolDescriptorWithSource()?.name }
      (
        classExclusions +
          excluded.yamlToolNames.map { it.toolName } +
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

    return systemExclusions + targetExclusions
  }

  private fun getToolDescriptorsForCategory(
    category: ToolSetCategory,
  ): List<TrailblazeToolDescriptor> {
    val resolved = ToolSetCategoryMapping.resolve(category)
    val classDescriptors = resolved.toolClasses
      .mapNotNull { it.toTrailblazeToolDescriptorWithSource() }
    // Include YAML-defined tools (e.g. `pressBack` in NAVIGATION) so discovery output
    // matches what the executor will actually accept.
    val yamlDescriptors = KoogToolExt.buildTrailblazeDescriptorsForYamlDefined(resolved.yamlToolNames)
    // Include scripted tools (e.g. `openUrl` in NAVIGATION) so discovery output matches the
    // executor's accepted set; built from the catalog YAML without launching a QuickJS engine.
    val scriptedDescriptors = InProcessScriptedToolLauncher.describe(resolved.scriptedToolNames)
    return (classDescriptors + yamlDescriptors + scriptedDescriptors).sortedWith(compareBy { it.name })
  }

  /**
   * Get custom tool descriptors for a specific driver type.
   * Used when a device is connected and we know the exact driver.
   *
   * Unions class-backed, YAML-defined, catalog-scripted (toolset / `platforms.<p>.tools:` names),
   * and inline-scripted (`target.tools:`) tools so name/search/target listings see the same set the
   * executor accepts. Without the YAML/scripted branches, name-only entries pulled in by a target's
   * `tool_sets:` or `tools:` would silently drop from these listings even when they execute fine —
   * the same three-way parity the grouped [TrailblazeHostAppTarget.ToolGroup.toMergedDescriptors]
   * path enforces.
   */
  private fun getCustomToolDescriptors(
    target: TrailblazeHostAppTarget,
    driverType: TrailblazeDriverType,
  ): List<TrailblazeToolDescriptor> {
    return try {
      val classDescriptors = target.getCustomToolsForDriver(driverType)
        .mapNotNull { it.toTrailblazeToolDescriptorWithSource() }
      val yamlDescriptors = KoogToolExt
        .buildTrailblazeDescriptorsForYamlDefined(target.getCustomYamlToolNamesForDriver(driverType))
      val scriptedDescriptors = InProcessScriptedToolLauncher
        .describe(target.getCustomScriptedToolNamesForDriver(driverType))
      (classDescriptors + yamlDescriptors + scriptedDescriptors + getInlineToolDescriptors(target, driverType))
        .distinctBy { it.name }
        .sortedWith(compareBy { it.name })
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * Get custom tool descriptors for all driver types on a platform.
   * Used as fallback when no device is connected.
   *
   * Same class+YAML+scripted+inline union as [getCustomToolDescriptors], collapsed across every
   * driver for [platform] so a target's full surface area shows up regardless of which driver the
   * caller eventually picks. Scripted names are unioned across drivers first, then described in a
   * single [InProcessScriptedToolLauncher.describe] call (one catalog walk per invocation, matching
   * the YAML branch) rather than per-driver.
   */
  private fun getCustomToolDescriptorsForPlatform(
    target: TrailblazeHostAppTarget,
    platform: TrailblazeDevicePlatform,
  ): List<TrailblazeToolDescriptor> {
    return try {
      val driverTypes = TrailblazeDriverType.entries.filter { it.platform == platform }
      val classDescriptors = driverTypes
        .flatMap { driverType -> target.getCustomToolsForDriver(driverType) }
        .distinct()
        .mapNotNull { it.toTrailblazeToolDescriptorWithSource() }
      val yamlNames = driverTypes
        .flatMap { driverType -> target.getCustomYamlToolNamesForDriver(driverType) }
        .toSet()
      val yamlDescriptors = KoogToolExt.buildTrailblazeDescriptorsForYamlDefined(yamlNames)
      val scriptedNames = driverTypes
        .flatMap { driverType -> target.getCustomScriptedToolNamesForDriver(driverType) }
        .toSet()
      val scriptedDescriptors = InProcessScriptedToolLauncher.describe(scriptedNames)
      (classDescriptors + yamlDescriptors + scriptedDescriptors + getInlineToolDescriptorsForPlatform(target, platform))
        .distinctBy { it.name }
        .sortedWith(compareBy { it.name })
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun buildInlineScriptToolsetsForDriver(
    target: TrailblazeHostAppTarget,
    driverType: TrailblazeDriverType,
    detail: Boolean,
  ): List<ToolDiscoveryToolsetInfo> {
    val grouped = target.getInlineScriptTools()
      .mapNotNull { tool ->
        val meta = tool.meta?.let(TrailblazeToolMeta::fromJsonObject) ?: TrailblazeToolMeta()
        if (!meta.shouldRegister(driverType, preferHostAgent = true)) {
          null
        } else {
          (meta.toolset ?: "scripted") to inlineToolToDescriptor(tool)
        }
      }
      .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    return grouped.entries.map { (toolsetId, descriptors) ->
      val sorted = descriptors.sortedWith(compareBy { it.name })
      ToolDiscoveryToolsetInfo(
        name = "${target.id}/$toolsetId",
        description = "${target.displayName} scripted tools",
        tools = if (detail) null else sorted.map { it.name },
        toolDetails = sorted,
      )
    }.sortedBy { it.name }
  }

  private fun getInlineToolDescriptors(
    target: TrailblazeHostAppTarget,
    driverType: TrailblazeDriverType,
  ): List<TrailblazeToolDescriptor> =
    target.getInlineScriptTools().mapNotNull { tool ->
      val meta = tool.meta?.let(TrailblazeToolMeta::fromJsonObject) ?: TrailblazeToolMeta()
      if (!meta.shouldRegister(driverType, preferHostAgent = true)) {
        null
      } else {
        inlineToolToDescriptor(tool)
      }
    }

  private fun getInlineToolDescriptorsForPlatform(
    target: TrailblazeHostAppTarget,
    platform: TrailblazeDevicePlatform,
  ): List<TrailblazeToolDescriptor> =
    target.getInlineScriptTools().mapNotNull { tool ->
      val meta = tool.meta?.let(TrailblazeToolMeta::fromJsonObject) ?: TrailblazeToolMeta()
      if (!meta.shouldRegisterForPlatform(platform, preferHostAgent = true)) {
        null
      } else {
        inlineToolToDescriptor(tool)
      }
    }

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
  private fun buildUsageHint(descriptor: TrailblazeToolDescriptor): String {
    val allParams = descriptor.requiredParameters + descriptor.optionalParameters
    val yamlExample = if (allParams.isEmpty()) {
      "- ${descriptor.name}"
    } else {
      val params = allParams.joinToString("\n") { param ->
        val placeholder = when {
          param.type.contains("String", ignoreCase = true) -> "\"value\""
          param.type.contains("Int", ignoreCase = true) || param.type.contains("Long", ignoreCase = true) -> "0"
          param.type.contains("Boolean", ignoreCase = true) -> "true"
          else -> "\"value\""
        }
        "    ${param.name}: $placeholder"
      }
      "- ${descriptor.name}:\n$params"
    }
    return "Automatic: blaze(objective=\"your objective\") — the inner agent selects this tool when appropriate.\n" +
      "Direct: blaze(objective=\"description\", tools=\"$yamlExample\")"
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
  /**
   * Tool names whose YAML config carries a non-null `shortcut:` metadata block — i.e. tools
   * that jump from one named waypoint to another. Same shape and rationale as [trailheadTools].
   */
  val shortcutTools: List<String> = emptyList(),
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
)

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
  /** Tool names with shortcut role metadata, scoped to this target. */
  val shortcutTools: List<String> = emptyList(),
  val error: String? = null,
)

@Serializable
data class ToolDiscoveryTargetPlatformTools(
  val platform: String,
  val tools: List<TrailblazeToolDescriptor>,
)

@Serializable
data class ToolDiscoverySearchResult(
  val query: String? = null,
  val matches: List<ToolDiscoverySearchMatch>? = null,
  val error: String? = null,
)

@Serializable
data class ToolDiscoverySearchMatch(
  val tool: TrailblazeToolDescriptor,
  val source: String,
)

/**
 * Combines class-backed and YAML-defined tools from a [TrailblazeHostAppTarget.ToolGroup] into a
 * single descriptor list. Shared by every place that renders a [TrailblazeHostAppTarget.ToolGroup]
 * for discovery / summary output, so YAML-only entries (e.g. `eraseText`, `pressBack`) flow
 * through alongside class-backed tools.
 *
 * Class descriptors take precedence on name collision (class-backed implementations are
 * authoritative; YAML and scripted names are descriptors only), then YAML, then scripted.
 * `distinctBy { name }` defends against a future hand-built `ToolGroup` that lists the same name in
 * more than one backing — today's [YamlBackedHostAppTarget] resolver enforces mutual exclusion via
 * a typed `when`, but the data model itself does not.
 *
 * Scripted (`.ts` / `.js`) descriptors come from [InProcessScriptedToolLauncher.describe], which
 * resolves each name via `ScriptedToolNameDiscoverer` (trailmap `tools/` descriptor YAMLs on the
 * classpath + workspace overlay) — the same resolution the resolved-surface and non-grouped
 * discovery paths use. So a target whose custom tools are scripted shows up in grouped discovery
 * output alongside class- and YAML-backed tools — the discovery-grouping leg of the three-way
 * tool-backing parity.
 */
internal fun TrailblazeHostAppTarget.ToolGroup.toMergedDescriptors(): List<TrailblazeToolDescriptor> {
  val classDescriptors = toolClasses
    .mapNotNull { it.toTrailblazeToolDescriptorWithSource() }
  val yamlDescriptors = KoogToolExt.buildTrailblazeDescriptorsForYamlDefined(yamlToolNames)
  val scriptedDescriptors = InProcessScriptedToolLauncher.describe(scriptedToolNames)
  return (classDescriptors + yamlDescriptors + scriptedDescriptors).distinctBy { it.name }
}
