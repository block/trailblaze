package xyz.block.trailblaze.mcp.toolsets

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.toolcalls.ResolvedToolSet
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import kotlin.reflect.KClass

/**
 * Strategy for how tools are loaded and presented to the LLM.
 *
 * - [ALL_TOOLS]: Send all tools upfront. No progressive disclosure. This is the default
 *   to maximize reliability — the LLM always has every tool available.
 * - [PROGRESSIVE]: Start with minimal tools and let the LLM request more categories
 *   as needed via the `toolbox()` MCP tool. Saves tokens but may cause regressions
 *   if the LLM doesn't request the right categories.
 */
enum class ToolLoadingStrategy {
  /** Send all tools upfront. Default — maximizes reliability. */
  ALL_TOOLS,

  /** Start minimal, LLM requests more as needed. Saves tokens. */
  PROGRESSIVE,
}

/**
 * Categories of tools that can be enabled/disabled for subagents.
 *
 * This enables two-tier tool management:
 * 1. Parent LLM selects initial categories based on the high-level task
 * 2. Subagent can swap categories as it discovers what it needs
 *
 * Benefits:
 * - Reduces context window usage (fewer tool descriptions)
 * - Helps LLM focus on relevant tools
 * - Enables task-appropriate tool access
 */
@Serializable
enum class ToolSetCategory(
  val displayName: String,
  val description: String,
  val useCases: List<String>,
) {
  /**
   * Basic UI interaction tools - tap, swipe, type.
   * The minimum set for most UI automation tasks.
   */
  CORE_INTERACTION(
    displayName = "Core Interaction",
    description = "Essential UI interaction tools: tap, swipe, type, and coordinate taps. " +
      "This is the minimal toolset for basic device control. Prefer `tap` by ref id over " +
      "`tapOnPoint` unless coordinates are genuinely required.",
    useCases = listOf(
      "Simple navigation tasks",
      "Tapping buttons and links",
      "Entering text in fields",
      "Scrolling through content",
    ),
  ),

  /**
   * Navigation and screen traversal tools.
   */
  NAVIGATION(
    displayName = "Navigation",
    description = "Tools for moving between screens: back button, scroll until visible, open URLs, " +
      "and app launching. Use when you need to navigate through the app.",
    useCases = listOf(
      "Moving between app sections",
      "Deep linking to specific screens",
      "Launching or restarting the app",
      "Finding elements by scrolling",
    ),
  ),

  /**
   * Screen state observation tools.
   */
  OBSERVATION(
    displayName = "Screen Observation",
    description = "Tools for understanding the current screen: view hierarchy, screenshots, and " +
      "element inspection. Use when you need to see what's on screen.",
    useCases = listOf(
      "Understanding current UI state",
      "Finding element coordinates",
      "Debugging why something isn't visible",
      "Taking screenshots for verification",
    ),
  ),

  /**
   * Verification and assertion tools.
   */
  VERIFICATION(
    displayName = "Verification",
    description = "Tools for verifying UI state: assert element visible, compare values, " +
      "and AI-powered assertions. Use when you need to confirm something happened.",
    useCases = listOf(
      "Confirming navigation succeeded",
      "Verifying text or values on screen",
      "Checking if elements are present",
      "Validating test expectations",
    ),
  ),

  /**
   * Memory and state tracking tools.
   */
  MEMORY(
    displayName = "Memory & State",
    description = "Tools for remembering values across steps: store text, numbers, and use " +
      "AI to extract information. Use when you need to track values between actions.",
    useCases = listOf(
      "Storing account numbers or IDs",
      "Tracking values for later comparison",
      "Extracting data from the screen",
      "Building up state over multiple steps",
    ),
  ),

  /**
   * Session and configuration tools.
   */
  SESSION(
    displayName = "Session Control",
    description = "Tools for managing the automation session: end session, configure settings, " +
      "and control execution mode. Use for session lifecycle management.",
    useCases = listOf(
      "Ending the current session",
      "Changing configuration mid-test",
      "Switching execution modes",
    ),
  ),

  /**
   * All available tools - use sparingly due to context size.
   */
  ALL(
    displayName = "All Tools",
    description = "WARNING: Enables all available tools. This significantly increases context size. " +
      "Only use when you're unsure which category is needed, then narrow down.",
    useCases = listOf(
      "Exploratory tasks with unknown requirements",
      "Complex multi-phase operations",
      "When other categories are insufficient",
    ),
  ),
  ;

  companion object {
    /**
     * Default categories for a new session.
     */
    val DEFAULT_CATEGORIES = setOf(CORE_INTERACTION, OBSERVATION)

    /**
     * Minimal set for simple tasks.
     */
    val MINIMAL = setOf(CORE_INTERACTION)

    /**
     * Standard set for most automation tasks.
     */
    val STANDARD = setOf(CORE_INTERACTION, NAVIGATION, OBSERVATION)

    /**
     * Full set for testing scenarios.
     */
    val TESTING = setOf(CORE_INTERACTION, NAVIGATION, OBSERVATION, VERIFICATION, MEMORY)

    /**
     * Returns the allowed categories for a given mode.
     * 
     * - MCP_CLIENT_AS_AGENT: All categories (client controls which to use)
     * - TRAILBLAZE_AS_AGENT: SESSION only (client sends prompts, Trailblaze reasons)
     */
    fun getCategoriesForMode(mode: TrailblazeMcpMode): Set<ToolSetCategory> = when (mode) {
      TrailblazeMcpMode.MCP_CLIENT_AS_AGENT -> entries.toSet()
      TrailblazeMcpMode.TRAILBLAZE_AS_AGENT -> setOf(SESSION)
    }

    /**
     * Returns the default categories for a given mode.
     */
    fun getDefaultCategoriesForMode(mode: TrailblazeMcpMode): Set<ToolSetCategory> = when (mode) {
      TrailblazeMcpMode.MCP_CLIENT_AS_AGENT -> DEFAULT_CATEGORIES
      TrailblazeMcpMode.TRAILBLAZE_AS_AGENT -> setOf(SESSION)
    }

    /**
     * Returns the default categories for a given mode and loading strategy.
     *
     * When [ToolLoadingStrategy.ALL_TOOLS], all categories are enabled upfront.
     * When [ToolLoadingStrategy.PROGRESSIVE], the minimal default set is used.
     */
    fun getDefaultCategoriesForMode(
      mode: TrailblazeMcpMode,
      strategy: ToolLoadingStrategy,
    ): Set<ToolSetCategory> = when (strategy) {
      ToolLoadingStrategy.ALL_TOOLS -> when (mode) {
        TrailblazeMcpMode.MCP_CLIENT_AS_AGENT -> setOf(ALL)
        TrailblazeMcpMode.TRAILBLAZE_AS_AGENT -> setOf(SESSION)
      }
      ToolLoadingStrategy.PROGRESSIVE -> getDefaultCategoriesForMode(mode)
    }

  }
}

/**
 * Maps ToolSetCategory to actual TrailblazeToolSet implementations.
 *
 * This connects the abstract categories to concrete tool implementations.
 */
object ToolSetCategoryMapping {

  /**
   * Gets the TrailblazeTool classes for a category. Routes every category through
   * [TrailblazeToolSetCatalog.entryToolClasses] so MCP sees the same tool surface as the YAML
   * catalog — no Kotlin-only special cases. `tapOnPoint` is part of `core_interaction`; targets
   * that want to disable it for their own app surface can use YAML `excluded_tools:` at the
   * target level (e.g. `excluded_tools: [tapOnPoint]` in a target YAML).
   */
  fun getToolClasses(category: ToolSetCategory): Set<KClass<out TrailblazeTool>> {
    return when (category) {
      ToolSetCategory.CORE_INTERACTION -> TrailblazeToolSetCatalog.entryToolClasses("core_interaction")
      ToolSetCategory.NAVIGATION -> TrailblazeToolSetCatalog.entryToolClasses("navigation")
      ToolSetCategory.OBSERVATION -> TrailblazeToolSetCatalog.entryToolClasses("observation")
      ToolSetCategory.VERIFICATION -> TrailblazeToolSetCatalog.entryToolClasses("verification")
      ToolSetCategory.MEMORY -> TrailblazeToolSetCatalog.entryToolClasses("memory")
      ToolSetCategory.SESSION -> emptySet() // Session tools are Koog ToolSets, not TrailblazeTools
      ToolSetCategory.ALL -> TrailblazeToolSet.DefaultLlmTrailblazeTools
    }
  }

  /**
   * Gets the combined tool classes for multiple categories.
   */
  fun getToolClasses(categories: Set<ToolSetCategory>): Set<KClass<out TrailblazeTool>> {
    return categories.flatMap { getToolClasses(it) }.toSet()
  }

  /**
   * Gets the YAML-defined tool names for a category. Parallel to [getToolClasses] for the
   * `tools:` composition case — e.g. `navigation` includes `pressBack` (no Kotlin class).
   *
   * Routes through [TrailblazeToolSetCatalog.entryYamlToolNames] so MCP sees the same
   * YAML-defined surface as other catalog consumers. [ToolSetCategory.ALL] returns every
   * YAML-defined name from every catalog entry so the "give me everything" path doesn't
   * silently drop YAML-only tools.
   */
  fun getYamlToolNames(category: ToolSetCategory): Set<ToolName> {
    return when (category) {
      ToolSetCategory.CORE_INTERACTION -> TrailblazeToolSetCatalog.entryYamlToolNames("core_interaction")
      ToolSetCategory.NAVIGATION -> TrailblazeToolSetCatalog.entryYamlToolNames("navigation")
      ToolSetCategory.OBSERVATION -> TrailblazeToolSetCatalog.entryYamlToolNames("observation")
      ToolSetCategory.VERIFICATION -> TrailblazeToolSetCatalog.entryYamlToolNames("verification")
      ToolSetCategory.MEMORY -> TrailblazeToolSetCatalog.entryYamlToolNames("memory")
      ToolSetCategory.SESSION -> emptySet()
      ToolSetCategory.ALL -> TrailblazeToolSetCatalog.defaultEntries().flatMap { it.yamlToolNames }.toSet()
    }
  }

  /**
   * Gets the combined YAML-defined tool names for multiple categories.
   */
  fun getYamlToolNames(categories: Set<ToolSetCategory>): Set<ToolName> {
    return categories.flatMap { getYamlToolNames(it) }.toSet()
  }

  /**
   * Class set + YAML-defined tool names for a single category, bundled so callers can't
   * accidentally consume only half. Prefer this over the split [getToolClasses] /
   * [getYamlToolNames] pair when you need a complete tool surface — every live MCP entrypoint
   * (DirectMcpToolExecutor, SubagentOrchestrator, the inner-agent fallback in
   * TrailblazeMcpServer, DynamicToolSetManager) must advertise both, and the regression this
   * API guards against is exactly the "class-only lookup silently drops YAML-defined tools"
   * bug that shipped with the pressBack migration.
   *
   * Returns [ResolvedToolSet] — the same type the catalog-level
   * [TrailblazeToolSetCatalog.resolve] returns, so callers can use one API regardless of
   * whether they selected tools by category or by catalog id.
   */
  fun resolve(category: ToolSetCategory): ResolvedToolSet = ResolvedToolSet(
    toolClasses = getToolClasses(category),
    yamlToolNames = getYamlToolNames(category),
  )

  /**
   * Class set + YAML-defined tool names for a set of categories, bundled. Same rationale as
   * [resolve] — the combined entry point so callers can't miss the YAML half.
   */
  fun resolve(categories: Set<ToolSetCategory>): ResolvedToolSet = ResolvedToolSet(
    toolClasses = getToolClasses(categories),
    yamlToolNames = getYamlToolNames(categories),
  )

}

/**
 * Human-readable summary of available categories for LLM selection.
 */
fun generateCategorySummaryForLLM(): String = buildString {
  appendLine("Available Tool Categories:")
  appendLine()
  ToolSetCategory.entries.forEach { category ->
    appendLine("## ${category.displayName} (${category.name})")
    appendLine(category.description)
    appendLine()
    appendLine("Best for:")
    category.useCases.forEach { useCase ->
      appendLine("  - $useCase")
    }
    appendLine()
  }
}
