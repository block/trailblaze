package xyz.block.trailblaze.mcp.newtools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.junit.Test
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.mcp.toolsets.DynamicToolSetManager
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Tests for [ToolSetManagementToolSet] and [AdvancedToolSetManagementToolSet].
 *
 * Verifies that tool category management works correctly:
 * - Enabling categories via the `tools` tool
 * - Error handling when manager is null
 * - Advanced management: add, remove, focus, reset, presets
 */
class ToolSetManagementToolSetTest {

  private val testSessionId = McpSessionId("test-session")

  private fun createSessionContext(
    includePrimitiveTools: Boolean = true,
    mode: TrailblazeMcpMode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
  ) = TrailblazeMcpSessionContext(
    mcpServerSession = null,
    mcpSessionId = testSessionId,
    mode = mode,
    includePrimitiveTools = includePrimitiveTools,
  )

  private fun createMcpServer(): Server = Server(
    Implementation(name = "test", version = "1.0"),
    ServerOptions(capabilities = ServerCapabilities()),
  )

  private var lastToolsChanged: Set<KClass<out TrailblazeTool>> = emptySet()

  private fun createManager(
    sessionContext: TrailblazeMcpSessionContext = createSessionContext(),
  ): DynamicToolSetManager {
    return DynamicToolSetManager(
      mcpServer = createMcpServer(),
      sessionContext = sessionContext,
      mcpSessionId = testSessionId,
      onToolsChanged = { lastToolsChanged = it },
    )
  }

  // ── ToolSetManagementToolSet ──────────────────────────────────────────────

  @Test
  fun `tools returns error when manager is null`() {
    val toolSet = ToolSetManagementToolSet(toolSetManager = null)
    val result = toolSet.tools(enable = listOf(ToolSetCategory.CORE_INTERACTION))
    assertContains(result, "not available")
  }

  @Test
  fun `tools returns error when empty categories provided`() {
    val manager = createManager()
    val toolSet = ToolSetManagementToolSet(toolSetManager = manager)
    val result = toolSet.tools(enable = emptyList())
    assertContains(result, "No categories provided")
  }

  @Test
  fun `tools enables requested categories`() {
    val manager = createManager()
    val toolSet = ToolSetManagementToolSet(toolSetManager = manager)

    val result = toolSet.tools(
      enable = listOf(ToolSetCategory.CORE_INTERACTION, ToolSetCategory.NAVIGATION),
    )

    assertContains(result, "Active categories")
    val enabled = manager.getEnabledCategories()
    assertEquals(true, enabled.contains(ToolSetCategory.CORE_INTERACTION))
    assertEquals(true, enabled.contains(ToolSetCategory.NAVIGATION))
  }

  // ── AdvancedToolSetManagementToolSet ───────────────────────────────────────

  @Test
  fun `listToolCategories returns category descriptions`() {
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = null)
    val result = toolSet.listToolCategories()
    // Should list all categories with descriptions
    assertContains(result, "CORE_INTERACTION")
  }

  @Test
  fun `addToolCategory returns error when manager is null`() {
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = null)
    val result = toolSet.addToolCategory(ToolSetCategory.VERIFICATION)
    assertContains(result, "not available")
  }

  @Test
  fun `addToolCategory adds category to enabled set`() {
    val manager = createManager()
    manager.initialize()
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    val result = toolSet.addToolCategory(ToolSetCategory.MEMORY)
    assertContains(result, "Added")
    assertEquals(true, manager.getEnabledCategories().contains(ToolSetCategory.MEMORY))
  }

  @Test
  fun `removeToolCategory removes category from enabled set`() {
    val manager = createManager()
    // Set multiple categories so we can remove one
    manager.setCategories(setOf(ToolSetCategory.CORE_INTERACTION, ToolSetCategory.NAVIGATION))
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    val result = toolSet.removeToolCategory(ToolSetCategory.NAVIGATION)
    assertContains(result, "Removed")
    assertEquals(false, manager.getEnabledCategories().contains(ToolSetCategory.NAVIGATION))
  }

  @Test
  fun `removeToolCategory refuses to remove last category`() {
    val manager = createManager()
    manager.setCategories(setOf(ToolSetCategory.CORE_INTERACTION))
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    val result = toolSet.removeToolCategory(ToolSetCategory.CORE_INTERACTION)
    assertContains(result, "Cannot remove the last category")
  }

  @Test
  fun `focusOnCategory switches to single category`() {
    val manager = createManager()
    manager.setCategories(
      setOf(ToolSetCategory.CORE_INTERACTION, ToolSetCategory.NAVIGATION, ToolSetCategory.MEMORY),
    )
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    toolSet.focusOnCategory(ToolSetCategory.VERIFICATION)
    assertEquals(setOf(ToolSetCategory.VERIFICATION), manager.getEnabledCategories())
  }

  @Test
  fun `resetToolCategories restores defaults`() {
    val sessionContext = createSessionContext(includePrimitiveTools = true)
    val manager = createManager(sessionContext)
    manager.setCategories(setOf(ToolSetCategory.MEMORY))
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    toolSet.resetToolCategories()
    val defaults = ToolSetCategory.getDefaultCategoriesForMode(sessionContext.mode)
    assertEquals(defaults, manager.getEnabledCategories())
  }

  @Test
  fun `useMinimalTools enables only CORE_INTERACTION`() {
    val manager = createManager()
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    toolSet.useMinimalTools()
    assertEquals(true, manager.getEnabledCategories().contains(ToolSetCategory.CORE_INTERACTION))
  }

  @Test
  fun `useStandardTools enables standard set`() {
    val manager = createManager()
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    toolSet.useStandardTools()
    val enabled = manager.getEnabledCategories()
    assertEquals(true, enabled.contains(ToolSetCategory.CORE_INTERACTION))
    assertEquals(true, enabled.contains(ToolSetCategory.NAVIGATION))
    assertEquals(true, enabled.contains(ToolSetCategory.OBSERVATION))
  }

  @Test
  fun `useTestingTools enables testing set`() {
    val manager = createManager()
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    toolSet.useTestingTools()
    val enabled = manager.getEnabledCategories()
    assertEquals(true, enabled.contains(ToolSetCategory.VERIFICATION))
    assertEquals(true, enabled.contains(ToolSetCategory.MEMORY))
  }

  @Test
  fun `getEnabledCategories returns current state`() {
    val manager = createManager()
    manager.setCategories(setOf(ToolSetCategory.CORE_INTERACTION))
    val toolSet = AdvancedToolSetManagementToolSet(toolSetManager = manager)

    val result = toolSet.getEnabledCategories()
    assertContains(result, "Enabled Tool Categories")
    assertContains(result, "Core Interaction")
  }
}
