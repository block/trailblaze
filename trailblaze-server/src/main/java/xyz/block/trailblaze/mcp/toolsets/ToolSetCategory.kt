package xyz.block.trailblaze.mcp.toolsets

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementByNodeIdTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import kotlin.reflect.KClass

/**
 * Strategy for how tools are loaded and presented to the LLM.
 *
 * - [ALL_TOOLS]: Send all tools upfront. No progressive disclosure. This is the default
 *   to maximize reliability — the LLM always has every tool available.
 * - [PROGRESSIVE]: Start with minimal tools and let the LLM request more categories
 *   as needed via the `tools()` MCP tool. Saves tokens but may cause regressions
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
    description = "Essential UI interaction tools: tap at coordinates, swipe gestures, and text input. " +
      "This is the minimal toolset for basic device control.",
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
     * Minimal set optimized for inner agent performance.
     * Only includes the most common tools to reduce token usage.
     * ~6 tools instead of ~17, saving ~2,000+ tokens per request.
     */
    val INNER_AGENT_MINIMAL = setOf(CORE_INTERACTION)

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
   * Gets the TrailblazeTool classes for a category.
   */
  fun getToolClasses(category: ToolSetCategory): Set<KClass<out TrailblazeTool>> {
    return when (category) {
      ToolSetCategory.CORE_INTERACTION -> TrailblazeToolSet.DeviceControlTrailblazeToolSet.toolClasses
      ToolSetCategory.NAVIGATION -> getNavigationTools()
      ToolSetCategory.OBSERVATION -> getObservationTools()
      ToolSetCategory.VERIFICATION -> TrailblazeToolSet.VerifyToolSet.toolClasses
      ToolSetCategory.MEMORY -> TrailblazeToolSet.RememberTrailblazeToolSet.toolClasses
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

  private fun getNavigationTools(): Set<KClass<out TrailblazeTool>> {
    // Navigation-specific subset
    return setOf(
      xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool::class,
      xyz.block.trailblaze.toolcalls.commands.OpenUrlTrailblazeTool::class,
      xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool::class,
      xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool::class,
    )
  }

  private fun getObservationTools(): Set<KClass<out TrailblazeTool>> {
    // Observation tools are primarily in DeviceManagerToolSet (Koog), not TrailblazeTools
    // This returns snapshot tool which captures screen state
    return setOf(
      TakeSnapshotTool::class,
    )
  }

  /**
   * Minimal tool set for the inner agent, optimized for token efficiency.
   *
   * Only includes the most commonly used tools:
   * - tapOnElementByNodeId / tapOnPoint: Click on elements
   * - inputText: Type text
   * - swipe: Scroll/navigate
   * - pressBack: Back navigation
   * - objectiveStatus: Report completion
   *
   * Saves ~3,000+ tokens compared to STANDARD (6 tools vs ~17).
   */
  fun getInnerAgentMinimalTools(): Set<KClass<out TrailblazeTool>> {
    return setOf(
      TapOnElementByNodeIdTrailblazeTool::class,
      TapOnPointTrailblazeTool::class,
      InputTextTrailblazeTool::class,
      SwipeTrailblazeTool::class,
      PressBackTrailblazeTool::class,
      ObjectiveStatusTrailblazeTool::class,
    )
  }
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
