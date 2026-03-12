package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import xyz.block.trailblaze.mcp.toolsets.DynamicToolSetManager
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.generateCategorySummaryForLLM

/**
 * Minimal MCP tool for enabling additional tool categories.
 *
 * This is the default toolset - provides just ONE tool: `tools`.
 * All category info is in the tool description - no need for separate list/get tools.
 */
@Suppress("unused")
class ToolSetManagementToolSet(
  private val toolSetManager: DynamicToolSetManager?,
) : ToolSet {

  @LLMDescription(
    """
    Enable additional tool categories when you need more capabilities.
    
    Categories:
    - OBSERVATION: Screen state tools (getScreenState, viewHierarchy)
    - CORE_INTERACTION: Tap, swipe, type, press keys
    - NAVIGATION: Back, scroll, launch app, open URL
    - VERIFICATION: Assert element visible, compare values
    - MEMORY: Store/recall values across steps
    - ALL: Everything (large context - use sparingly)
    
    Examples:
    - tools(enable=["OBSERVATION"]) → adds screen inspection
    - tools(enable=["CORE_INTERACTION", "NAVIGATION"]) → manual control
    """
  )
  @Tool
  fun tools(
    @LLMDescription("Categories to enable: OBSERVATION, CORE_INTERACTION, NAVIGATION, VERIFICATION, MEMORY, ALL")
    enable: List<ToolSetCategory>,
  ): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    if (enable.isEmpty()) {
      return "Error: No categories provided"
    }

    return manager.setCategories(enable.toSet())
  }
}

/**
 * Advanced MCP tools for fine-grained toolset management.
 *
 * This toolset is NOT registered by default. Register it explicitly when you need:
 * - List categories and see what's enabled
 * - Quick presets (useMinimalTools, useStandardTools, useTestingTools)
 * - Incremental category changes (addToolCategory, removeToolCategory)
 * - Focus mode (focusOnCategory)
 * - Reset to defaults (resetToolCategories)
 *
 * For most use cases, the minimal [ToolSetManagementToolSet] is sufficient.
 */
@Suppress("unused")
class AdvancedToolSetManagementToolSet(
  private val toolSetManager: DynamicToolSetManager?,
) : ToolSet {

  @LLMDescription("List all available tool categories with detailed descriptions.")
  @Tool
  fun listToolCategories(): String {
    return generateCategorySummaryForLLM()
  }

  @LLMDescription("Get the currently enabled tool categories and count of available tools.")
  @Tool
  fun getEnabledCategories(): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    return manager.describeEnabledCategories()
  }

  @LLMDescription(
    """
    Add a tool category to the currently enabled set.
    
    Use this when you need additional tools without removing your current ones.
    """
  )
  @Tool
  fun addToolCategory(
    @LLMDescription("Category to add")
    category: ToolSetCategory,
  ): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    return manager.addCategory(category)
  }

  @LLMDescription(
    """
    Remove a tool category from the currently enabled set.
    
    Use this to reduce context size by removing tools you no longer need.
    At least one category must remain enabled.
    """
  )
  @Tool
  fun removeToolCategory(
    @LLMDescription("Category to remove")
    category: ToolSetCategory,
  ): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    return manager.removeCategory(category)
  }

  @LLMDescription(
    """
    Focus on a single tool category, disabling all others.
    
    Use this when you want to narrow down to just one type of task.
    This minimizes context size for focused work.
    """
  )
  @Tool
  fun focusOnCategory(
    @LLMDescription("The single category to focus on")
    category: ToolSetCategory,
  ): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    return manager.focusOnCategory(category)
  }

  @LLMDescription(
    """
    Reset to the default tool categories.
    
    Default categories: CORE_INTERACTION, OBSERVATION
    """
  )
  @Tool
  fun resetToolCategories(): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    return manager.resetToDefault()
  }

  @LLMDescription(
    """
    Quick preset: Enable minimal tools for simple interaction tasks.
    
    Enables: CORE_INTERACTION only
    Best for: Simple tap/type tasks with known coordinates
    """
  )
  @Tool
  fun useMinimalTools(): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    return manager.setCategories(ToolSetCategory.MINIMAL)
  }

  @LLMDescription(
    """
    Quick preset: Enable standard tools for typical automation.
    
    Enables: CORE_INTERACTION, NAVIGATION, OBSERVATION
    Best for: General navigation and interaction tasks
    """
  )
  @Tool
  fun useStandardTools(): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    return manager.setCategories(ToolSetCategory.STANDARD)
  }

  @LLMDescription(
    """
    Quick preset: Enable testing tools for verification workflows.
    
    Enables: CORE_INTERACTION, NAVIGATION, OBSERVATION, VERIFICATION, MEMORY
    Best for: Test automation with assertions and state tracking
    """
  )
  @Tool
  fun useTestingTools(): String {
    val manager = toolSetManager
      ?: return "Error: Tool management not available in this session"

    return manager.setCategories(ToolSetCategory.TESTING)
  }
}
