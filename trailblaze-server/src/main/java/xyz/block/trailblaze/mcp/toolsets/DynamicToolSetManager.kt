package xyz.block.trailblaze.mcp.toolsets

import io.modelcontextprotocol.kotlin.sdk.server.Server
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import kotlin.reflect.KClass

/**
 * Manages dynamic tool registration for MCP sessions.
 *
 * Enables the two-tier tool management pattern:
 * 1. Parent LLM selects initial categories when spawning a subagent
 * 2. Subagent can swap categories as it works through the task
 *
 * When categories change, this manager:
 * - Computes the new set of tools
 * - Registers/unregisters tools with the MCP server
 * - Sends `tools/list_changed` notification to client
 *
 * Usage:
 * ```kotlin
 * val manager = DynamicToolSetManager(mcpServer, sessionContext)
 *
 * // Parent selects initial categories
 * manager.setCategories(setOf(CORE_INTERACTION, NAVIGATION))
 *
 * // Subagent discovers it needs verification tools
 * manager.addCategory(VERIFICATION)
 *
 * // Subagent focuses on just verification
 * manager.setCategories(setOf(VERIFICATION))
 * ```
 */
class DynamicToolSetManager(
  private val mcpServer: Server,
  private val sessionContext: TrailblazeMcpSessionContext,
  private val mcpSessionId: McpSessionId,
  private val onToolsChanged: (Set<KClass<out TrailblazeTool>>) -> Unit,
) {
  /**
   * Currently enabled categories for this session.
   *
   * When `includePrimitiveTools = false` (default for external clients):
   * - No primitive tool categories are enabled initially
   * - External clients use high-level tools like runPrompt() instead
   *
   * When `includePrimitiveTools = true` (for internal self-connection):
   * - Default categories are enabled based on the session's mode
   * - TRAILBLAZE_AS_AGENT only gets SESSION
   * - MCP_CLIENT_AS_AGENT gets the default categories
   */
  private val lock = Any()

  private var enabledCategories: MutableSet<ToolSetCategory> =
    if (sessionContext.includePrimitiveTools) {
      ToolSetCategory.getDefaultCategoriesForMode(sessionContext.mode).toMutableSet()
    } else {
      // External clients start with no primitive tools
      // They should use runPrompt() for UI automation
      mutableSetOf()
    }

  /**
   * Currently registered tool classes.
   */
  private var registeredToolClasses: Set<KClass<out TrailblazeTool>> = emptySet()

  /**
   * Gets the currently enabled categories.
   */
  fun getEnabledCategories(): Set<ToolSetCategory> = synchronized(lock) { enabledCategories.toSet() }

  /**
   * Gets the tool descriptors for currently registered tools.
   *
   * Used by the inner agent (screen analyzer) to present available tools
   * to the LLM in a type-safe manner.
   */
  fun getCurrentToolDescriptors(): List<TrailblazeToolDescriptor> = synchronized(lock) {
    registeredToolClasses.mapNotNull { toolClass ->
      // Convert KClass -> ToolDescriptor -> TrailblazeToolDescriptor
      toolClass.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()
    }
  }

  /**
   * Gets a human-readable description of enabled categories.
   */
  fun describeEnabledCategories(): String = synchronized(lock) {
    buildString {
      appendLine("Enabled Tool Categories:")
      enabledCategories.forEach { category ->
        appendLine("- ${category.displayName}: ${category.description.take(80)}...")
      }
      appendLine()
      appendLine("Total tools available: ${registeredToolClasses.size}")
    }
  }

  /**
   * Sets the enabled categories, replacing any existing ones.
   * Categories are filtered based on the current session mode.
   *
   * Note: When includePrimitiveTools is false, this will warn that primitive tools
   * should not be enabled for external clients.
   *
   * @param categories The new set of categories to enable
   * @return Description of what changed
   */
  fun setCategories(categories: Set<ToolSetCategory>): String {
    val changedTools: Set<KClass<out TrailblazeTool>>?
    val result = synchronized(lock) {
      // Warn if trying to enable primitive tools when includePrimitiveTools is false
      val primitiveWarning = if (!sessionContext.includePrimitiveTools && categories.isNotEmpty()) {
        "WARNING: Enabling primitive tools for external MCP clients is not recommended.\n" +
          "Use runPrompt() for UI automation instead, or set includePrimitiveTools=true for internal use.\n\n"
      } else ""

      val allowedCategories = ToolSetCategory.getCategoriesForMode(sessionContext.mode)
      val filteredCategories = categories.intersect(allowedCategories)

      // If requested categories aren't allowed in this mode, warn the user
      val disallowedCategories = categories - allowedCategories
      val warnings = if (disallowedCategories.isNotEmpty()) {
        "Warning: Categories ${disallowedCategories.joinToString { it.displayName }} are not available in ${sessionContext.mode.name} mode.\n"
      } else ""

      val previousCategories = enabledCategories.toSet()
      enabledCategories = filteredCategories.ifEmpty {
        // Ensure at least default categories for this mode
        ToolSetCategory.getDefaultCategoriesForMode(sessionContext.mode)
      }.toMutableSet()

      val added = enabledCategories - previousCategories
      val removed = previousCategories - enabledCategories

      changedTools = computeToolRefresh()

      buildString {
        append(primitiveWarning)
        append(warnings)
        if (added.isNotEmpty()) {
          appendLine("Added categories: ${added.joinToString { it.displayName }}")
        }
        if (removed.isNotEmpty()) {
          appendLine("Removed categories: ${removed.joinToString { it.displayName }}")
        }
        appendLine("Active categories: ${enabledCategories.joinToString { it.displayName }.ifEmpty { "(none)" }}")
        appendLine("Tools available: ${registeredToolClasses.size}")
        appendLine("Mode: ${sessionContext.mode.name}")
        appendLine("Include primitive tools: ${sessionContext.includePrimitiveTools}")
      }
    }
    // Invoke callback outside the lock to prevent potential deadlock
    changedTools?.let { onToolsChanged(it) }
    return result
  }

  /**
   * Adds a category to the enabled set.
   *
   * @param category The category to add
   * @return Description of what changed
   */
  fun addCategory(category: ToolSetCategory): String {
    val changedTools: Set<KClass<out TrailblazeTool>>?
    val result = synchronized(lock) {
      if (category in enabledCategories) {
        return "Category '${category.displayName}' is already enabled."
      }

      enabledCategories.add(category)
      changedTools = computeToolRefresh()

      "Added '${category.displayName}'. Active categories: ${enabledCategories.joinToString { it.displayName }}"
    }
    changedTools?.let { onToolsChanged(it) }
    return result
  }

  /**
   * Removes a category from the enabled set.
   *
   * @param category The category to remove
   * @return Description of what changed
   */
  fun removeCategory(category: ToolSetCategory): String {
    val changedTools: Set<KClass<out TrailblazeTool>>?
    val result = synchronized(lock) {
      if (category !in enabledCategories) {
        return "Category '${category.displayName}' is not currently enabled."
      }

      if (enabledCategories.size == 1) {
        return "Cannot remove the last category. At least one must remain enabled."
      }

      enabledCategories.remove(category)
      changedTools = computeToolRefresh()

      "Removed '${category.displayName}'. Active categories: ${enabledCategories.joinToString { it.displayName }}"
    }
    changedTools?.let { onToolsChanged(it) }
    return result
  }

  /**
   * Swaps to a specific category, disabling all others.
   * Useful when the subagent wants to focus on one type of task.
   *
   * @param category The category to focus on
   * @return Description of what changed
   */
  fun focusOnCategory(category: ToolSetCategory): String {
    return setCategories(setOf(category))
  }

  /**
   * Resets to the default categories for the current mode.
   * Respects the includePrimitiveTools setting.
   */
  fun resetToDefault(): String {
    return if (sessionContext.includePrimitiveTools) {
      setCategories(ToolSetCategory.getDefaultCategoriesForMode(sessionContext.mode))
    } else {
      setCategories(emptySet())
    }
  }

  /**
   * Called when the session mode changes.
   * Resets categories to the default for the new mode.
   * Respects the includePrimitiveTools setting.
   */
  fun onModeChanged() {
    val changedTools: Set<KClass<out TrailblazeTool>>?
    synchronized(lock) {
      enabledCategories = if (sessionContext.includePrimitiveTools) {
        ToolSetCategory.getDefaultCategoriesForMode(sessionContext.mode).toMutableSet()
      } else {
        mutableSetOf()
      }
      changedTools = computeToolRefresh()
    }
    changedTools?.let { onToolsChanged(it) }
  }

  /**
   * Called when includePrimitiveTools setting changes.
   * Enables or disables primitive tool categories accordingly.
   */
  fun onIncludePrimitiveToolsChanged() {
    val changedTools: Set<KClass<out TrailblazeTool>>?
    synchronized(lock) {
      if (sessionContext.includePrimitiveTools) {
        // Enable default categories for the current mode
        enabledCategories = ToolSetCategory.getDefaultCategoriesForMode(sessionContext.mode).toMutableSet()
      } else {
        // Disable all primitive tool categories
        enabledCategories = mutableSetOf()
      }
      changedTools = computeToolRefresh()
    }
    changedTools?.let { onToolsChanged(it) }
  }

  /**
   * Lists all available categories with descriptions.
   * Useful for the subagent to understand what's available.
   */
  fun listAvailableCategories(): String = generateCategorySummaryForLLM()

  /**
   * Refreshes the registered tools based on current categories.
   * Called internally when categories change (always within synchronized(lock)).
   *
   * Updates [registeredToolClasses] inside the lock but defers the [onToolsChanged]
   * callback to after the lock is released, preventing potential deadlock if the
   * callback reads back into this manager.
   *
   * @return The new tool classes if they changed, or null if no change.
   */
  private fun computeToolRefresh(): Set<KClass<out TrailblazeTool>>? {
    val newToolClasses = ToolSetCategoryMapping.getToolClasses(enabledCategories)

    return if (newToolClasses != registeredToolClasses) {
      registeredToolClasses = newToolClasses
      newToolClasses
    } else {
      null
    }
  }

  /**
   * Initializes with the current categories.
   * Call this after creating the manager to register initial tools.
   */
  fun initialize() {
    val changedTools: Set<KClass<out TrailblazeTool>>?
    synchronized(lock) {
      changedTools = computeToolRefresh()
    }
    changedTools?.let { onToolsChanged(it) }
  }
}
