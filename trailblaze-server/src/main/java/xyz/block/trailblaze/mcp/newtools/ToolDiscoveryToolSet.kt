package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.KoogToolExt
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
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
) : ToolSet {

  @LLMDescription(
    """
    Discover available Trailblaze tools.

    toolbox() → index of all tool categories and available targets
    toolbox(detail=true) → full parameter details for every tool
    toolbox(name="tapOnElement") → single tool with full descriptor
    toolbox(target="sampleapp") → tools for a specific target app

    Use this to understand what actions are possible before calling blaze().
    """
  )
  @Tool(McpToolProfile.TOOL_TOOLS)
  suspend fun toolbox(
    @LLMDescription("Filter to a single tool by name") name: String? = null,
    @LLMDescription("Filter to a specific target app's tools") target: String? = null,
    @LLMDescription("Search tools by keyword (matches names and descriptions)") search: String? = null,
    @LLMDescription("Filter by platform: android, ios, web") platform: String? = null,
    @LLMDescription("Expand tools with full parameter descriptions") detail: Boolean? = null,
  ): String {
    val platformFilter = platform?.let { TrailblazeDevicePlatform.fromString(it) }
    // "none" in index mode means "show only platform tools, no target tools"
    val isNoneTarget = target?.equals(DefaultTrailblazeHostAppTarget.id, ignoreCase = true) == true
    return when {
      name != null -> handleNameMode(name)
      search != null -> handleSearchMode(search, target)
      target != null && !isNoneTarget -> handleTargetMode(target, detail ?: false, platformFilter)
      else -> handleIndexMode(detail ?: false, platformFilter, suppressTargetTools = isNoneTarget)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Mode handlers
  // ─────────────────────────────────────────────────────────────────────────────

  private fun handleIndexMode(
    detail: Boolean,
    platformFilter: TrailblazeDevicePlatform? = null,
    suppressTargetTools: Boolean = false,
  ): String {
    val currentTarget = currentTargetProvider()
    val currentDriverType = currentDriverTypeProvider()
    // Platform filter from CLI --device overrides the connected device's platform
    val effectivePlatform = platformFilter ?: currentDriverType?.platform
    // Resolve a driver type for filtering: use connected driver, or default for the requested platform
    val effectiveDriverType = currentDriverType ?: platformFilter?.let { resolveDefaultDriverType(it) }
    val allTargets = allTargetAppsProvider()

    val excludedToolNames = getExcludedToolNames(currentTarget, effectiveDriverType)
    val platformToolsets = buildPlatformToolsets(detail, excludedToolNames, effectiveDriverType)
    val targetToolsets = if (suppressTargetTools) null else buildTargetToolsets(currentTarget, effectiveDriverType, detail)
    // Only show "other targets" hint when no device is connected (target tools not listed)
    val otherTargets = if (targetToolsets == null) buildOtherTargets(currentTarget, allTargets) else null

    val result = ToolDiscoveryIndexResult(
      currentTarget = currentTarget?.id,
      currentPlatform = effectivePlatform?.displayName,
      currentDriverType = effectiveDriverType?.yamlKey,
      platformToolsets = platformToolsets,
      targetToolsets = targetToolsets,
      otherTargets = otherTargets,
      usage = "These tools are used automatically by blaze(objective=\"...\"). " +
        "For direct execution: blaze(objective=\"description\", tools=\"- toolName:\\n    param: value\"). " +
        "For tool details: toolbox(name=\"toolName\").",
    )
    return jsonFormat.encodeToString(result)
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
    // Platform filter from CLI --device overrides when no device is connected
    val effectiveDriverType = currentDriverType ?: platformFilter?.let { resolveDefaultDriverType(it) }

    val supportedPlatforms = TrailblazeDevicePlatform.entries.filter { platform ->
      !targetApp.getPossibleAppIdsForPlatform(platform).isNullOrEmpty()
    }.map { it.displayName }

    // When a device is connected (or platform filter resolves a driver), show grouped tools.
    // Otherwise, show flat tools by platform.
    if (effectiveDriverType != null) {
      val groups = targetApp.getCustomToolGroupsForDriver(effectiveDriverType)
      val toolGroups = groups.mapNotNull { group ->
        val descriptors = group.toolClasses
          .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
          .sortedWith(compareBy { it.name })
        if (descriptors.isEmpty()) return@mapNotNull null
        ToolDiscoveryToolsetInfo(
          name = group.id,
          description = group.description,
          tools = if (detail) null else descriptors.map { it.name },
          toolDetails = if (detail) descriptors else null,
        )
      }

      return jsonFormat.encodeToString(
        ToolDiscoveryTargetResult(
          target = targetApp.id,
          displayName = targetApp.displayName,
          currentPlatform = effectiveDriverType.platform.displayName,
          supportedPlatforms = supportedPlatforms,
          toolGroups = toolGroups.ifEmpty { null },
        )
      )
    }

    // No device connected and no platform filter — show all platforms flat
    val platformTools = TrailblazeDevicePlatform.entries.mapNotNull { platform ->
      val tools = getCustomToolDescriptorsForPlatform(targetApp, platform)
      if (tools.isEmpty()) return@mapNotNull null
      ToolDiscoveryTargetPlatformTools(
        platform = platform.displayName,
        tools = if (detail) tools else tools.map { it.copy(requiredParameters = emptyList(), optionalParameters = emptyList()) },
      )
    }

    return jsonFormat.encodeToString(
      ToolDiscoveryTargetResult(
        target = targetApp.id,
        displayName = targetApp.displayName,
        supportedPlatforms = supportedPlatforms,
        toolsByPlatform = platformTools.ifEmpty { null },
      )
    )
  }

  private fun handleSearchMode(query: String, targetFilter: String?): String {
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
    val currentTarget = currentTargetProvider()
    val results = mutableListOf<ToolDiscoverySearchMatch>()

    // Search platform tools — use platform-filtered toolsets (same as index mode)
    // so tools inapplicable to the current device don't appear in results
    // (e.g., openUrl won't show for web since the none.yaml only includes web_core).
    val excludedToolNames = getExcludedToolNames(currentTarget, currentDriverType)
    val platformToolsets = buildPlatformToolsets(
      detail = true, excludedToolNames = excludedToolNames, driverType = currentDriverType,
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
      if (currentDriverType != null) {
        getCustomToolDescriptors(target, currentDriverType)
          .filter { matches(it) }
          .forEach { descriptor ->
            results.add(
              ToolDiscoverySearchMatch(
                tool = descriptor,
                source = "${target.displayName} (${currentDriverType.platform.displayName})",
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
   * Resolves a default driver type for a platform, used for filtering when no device is connected.
   */
  private fun resolveDefaultDriverType(platform: TrailblazeDevicePlatform): TrailblazeDriverType {
    return when (platform) {
      TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.DEFAULT_ANDROID
      TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
      TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.PLAYWRIGHT_NATIVE
    }
  }

  /**
   * Builds platform toolsets from the "none" target's tool groups.
   * The "none" target defines the base platform tools available on any device.
   * Falls back to [DISCOVERABLE_CATEGORIES] if the none target has no groups.
   */
  private fun buildPlatformToolsets(
    detail: Boolean,
    excludedToolNames: Set<String> = emptySet(),
    driverType: TrailblazeDriverType? = null,
  ): List<ToolDiscoveryToolsetInfo> {
    val noneTarget = allTargetAppsProvider().find { it.id == DefaultTrailblazeHostAppTarget.id }
    if (noneTarget != null) {
      // Use a driver type for group filtering (default to Android if none provided)
      val effectiveDriver = driverType ?: TrailblazeDriverType.DEFAULT_ANDROID
      val groups = noneTarget.getCustomToolGroupsForDriver(effectiveDriver)
      if (groups.isNotEmpty()) {
        return groups.mapNotNull { group ->
          val descriptors = group.toolClasses
            .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
            .filter { it.name !in excludedToolNames }
            .sortedWith(compareBy { it.name })
          if (descriptors.isEmpty()) return@mapNotNull null
          ToolDiscoveryToolsetInfo(
            name = group.id,
            description = group.description,
            tools = if (detail) null else descriptors.map { it.name },
            toolDetails = if (detail) descriptors else null,
          )
        }
      }
    }

    // Fallback: use hardcoded categories (open source without none target YAML)
    return DISCOVERABLE_CATEGORIES.mapNotNull { category ->
      val descriptors = getToolDescriptorsForCategory(category)
        .filter { it.name !in excludedToolNames }
      if (descriptors.isEmpty()) return@mapNotNull null
      ToolDiscoveryToolsetInfo(
        name = category.name.lowercase(),
        description = category.description,
        tools = if (detail) null else descriptors.map { it.name },
        toolDetails = if (detail) descriptors else null,
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
      // Device connected — show grouped tools for the current driver
      val allGroups = targets.flatMap { target ->
        target.getCustomToolGroupsForDriver(currentDriverType).mapNotNull { group ->
          val descriptors = group.toolClasses
            .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
            .sortedWith(compareBy { it.name })
          if (descriptors.isEmpty()) return@mapNotNull null
          ToolDiscoveryToolsetInfo(
            name = "${target.id}/${group.id}",
            description = group.description,
            tools = if (detail) null else descriptors.map { it.name },
            toolDetails = if (detail) descriptors else null,
          )
        }
      }
      return allGroups.ifEmpty { null }
    }

    // No device connected — show flat tools per target/platform
    val allGroups = targets.flatMap { target ->
      TrailblazeDevicePlatform.entries.mapNotNull { platform ->
        val descriptors = getCustomToolDescriptorsForPlatform(target, platform)
        if (descriptors.isEmpty()) return@mapNotNull null
        ToolDiscoveryToolsetInfo(
          name = "${target.id} (${platform.displayName})",
          description = "${target.displayName} tools for ${platform.displayName}",
          tools = if (detail) null else descriptors.map { it.name },
          toolDetails = if (detail) descriptors else null,
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
      val platforms = TrailblazeDevicePlatform.entries.filter { platform ->
        !target.getPossibleAppIdsForPlatform(platform).isNullOrEmpty()
      }.map { it.displayName }
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
      it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()?.name
    }.toSet()

    if (driverType == null || target == null) return systemExclusions

    val targetExclusions = try {
      target.getExcludedToolsForDriver(driverType)
        .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()?.name }
        .toSet()
    } catch (_: Exception) {
      emptySet()
    }

    return systemExclusions + targetExclusions
  }

  private fun getToolDescriptorsForCategory(
    category: ToolSetCategory,
  ): List<TrailblazeToolDescriptor> {
    val resolved = ToolSetCategoryMapping.resolve(category)
    val classDescriptors = resolved.toolClasses
      .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
    // Include YAML-defined tools (e.g. `pressBack` in NAVIGATION) so discovery output
    // matches what the executor will actually accept.
    val yamlDescriptors = KoogToolExt.buildDescriptorsForYamlDefined(resolved.yamlToolNames)
      .map { it.toTrailblazeToolDescriptor() }
    return (classDescriptors + yamlDescriptors).sortedWith(compareBy { it.name })
  }

  /**
   * Get custom tool descriptors for a specific driver type.
   * Used when a device is connected and we know the exact driver.
   */
  private fun getCustomToolDescriptors(
    target: TrailblazeHostAppTarget,
    driverType: TrailblazeDriverType,
  ): List<TrailblazeToolDescriptor> {
    return try {
      target.getCustomToolsForDriver(driverType)
        .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
        .sortedWith(compareBy { it.name })
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * Get custom tool descriptors for all driver types on a platform.
   * Used as fallback when no device is connected.
   */
  private fun getCustomToolDescriptorsForPlatform(
    target: TrailblazeHostAppTarget,
    platform: TrailblazeDevicePlatform,
  ): List<TrailblazeToolDescriptor> {
    return try {
      TrailblazeDriverType.entries.filter { it.platform == platform }
        .flatMap { driverType -> target.getCustomToolsForDriver(driverType) }
        .distinct()
        .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
        .sortedWith(compareBy { it.name })
    } catch (_: Exception) {
      emptyList()
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
  val platformToolsets: List<ToolDiscoveryToolsetInfo> = emptyList(),
  val targetToolsets: List<ToolDiscoveryToolsetInfo>? = null,
  val otherTargets: List<ToolDiscoveryOtherTarget>? = null,
  val usage: String? = null,
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
  /** Grouped tools for the current driver (when a device is connected). */
  val toolGroups: List<ToolDiscoveryToolsetInfo>? = null,
  /** Flat tools by platform (fallback when no device is connected). */
  val toolsByPlatform: List<ToolDiscoveryTargetPlatformTools>? = null,
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
