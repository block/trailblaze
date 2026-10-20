package xyz.block.trailblaze.mcp.toolsets

import org.junit.Test
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the new bundled category resolvers on [ToolSetCategoryMapping].
 *
 * These pin the shape the production consumers (DirectMcpToolExecutor, SubagentOrchestrator,
 * inner-agent fallback in TrailblazeMcpServer) rely on — namely that
 * `resolve(...)` covers both class-backed and YAML-defined tools in one call, so a class-only
 * refactor can't silently drop the YAML half like the pressBack migration did.
 */
class ToolSetCategoryMappingTest {

  @Test
  fun `resolve CORE_INTERACTION includes class-backed eraseText`() {
    val resolved = ToolSetCategoryMapping.resolve(ToolSetCategory.CORE_INTERACTION)
    assertTrue(
      EraseTextTrailblazeTool::class in resolved.toolClasses,
      "CORE_INTERACTION category should expose eraseText. Got: ${resolved.toolClasses}",
    )
  }

  @Test
  fun `resolve CORE_INTERACTION carries the entry's yaml tool names alongside its classes`() {
    // No built-in toolset lists a YAML-defined tool, so a workspace overlay adds one.
    val coreInteraction = TrailblazeToolSetCatalog.defaultEntries().single { it.id == "core_interaction" }
    val yamlTool = ToolName("fixture_yamlTool")
    TrailblazeToolSetCatalog.registerWorkspaceToolSets(
      listOf(coreInteraction.copy(yamlToolNames = coreInteraction.yamlToolNames + yamlTool)),
    )
    try {
      val resolved = ToolSetCategoryMapping.resolve(ToolSetCategory.CORE_INTERACTION)
      assertEquals(coreInteraction.yamlToolNames + yamlTool, resolved.yamlToolNames)
      assertEquals(coreInteraction.toolClasses, resolved.toolClasses)
    } finally {
      TrailblazeToolSetCatalog.registerWorkspaceToolSets(emptyList())
    }
  }

  @Test
  fun `resolve SESSION has empty yaml tool names`() {
    // SESSION is Koog-ToolSet-backed and intentionally empty for class + yaml paths.
    val resolved = ToolSetCategoryMapping.resolve(ToolSetCategory.SESSION)
    assertEquals(emptySet(), resolved.yamlToolNames)
    assertEquals(emptySet(), resolved.toolClasses)
  }

  @Test
  fun `resolve ALL yaml tool names equals flat union across catalog entries`() {
    val expected = TrailblazeToolSetCatalog.defaultEntries().flatMap { it.yamlToolNames }.toSet()
    val resolved = ToolSetCategoryMapping.resolve(ToolSetCategory.ALL)
    assertEquals(expected, resolved.yamlToolNames)
  }

  @Test
  fun `resolve combined categories unions the yaml tool names`() {
    val navigation = ToolSetCategoryMapping.resolve(ToolSetCategory.NAVIGATION).yamlToolNames
    val coreInteraction = ToolSetCategoryMapping.resolve(ToolSetCategory.CORE_INTERACTION).yamlToolNames
    val combined = ToolSetCategoryMapping.resolve(
      setOf(ToolSetCategory.NAVIGATION, ToolSetCategory.CORE_INTERACTION),
    ).yamlToolNames
    assertEquals(navigation + coreInteraction, combined)
  }
}
