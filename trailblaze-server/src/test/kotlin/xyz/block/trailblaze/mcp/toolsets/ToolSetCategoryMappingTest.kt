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
 * `resolve(...)` covers both class-backed and scripted tools in one call, so a class-only
 * refactor can't silently drop the scripted half.
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
  fun `resolve CORE_INTERACTION carries the entry's scripted tool names alongside its classes`() {
    val coreInteraction = TrailblazeToolSetCatalog.defaultEntries().single { it.id == "core_interaction" }
    val resolved = ToolSetCategoryMapping.resolve(ToolSetCategory.CORE_INTERACTION)
    assertTrue(
      ToolName("openUrl") in resolved.scriptedToolNames,
      "Catalog sanity: core_interaction delivers the scripted openUrl. Got: ${resolved.scriptedToolNames}",
    )
    assertEquals(coreInteraction.scriptedToolNames, resolved.scriptedToolNames)
    assertEquals(coreInteraction.toolClasses, resolved.toolClasses)
  }

  @Test
  fun `resolve SESSION has empty scripted tool names`() {
    // SESSION is Koog-ToolSet-backed and intentionally empty for class + scripted paths.
    val resolved = ToolSetCategoryMapping.resolve(ToolSetCategory.SESSION)
    assertEquals(emptySet(), resolved.scriptedToolNames)
    assertEquals(emptySet(), resolved.toolClasses)
  }

  @Test
  fun `resolve ALL scripted tool names equals flat union across catalog entries`() {
    val expected = TrailblazeToolSetCatalog.defaultEntries().flatMap { it.scriptedToolNames }.toSet()
    val resolved = ToolSetCategoryMapping.resolve(ToolSetCategory.ALL)
    assertEquals(expected, resolved.scriptedToolNames)
  }

  @Test
  fun `resolve combined categories unions the scripted tool names`() {
    val navigation = ToolSetCategoryMapping.resolve(ToolSetCategory.NAVIGATION).scriptedToolNames
    val coreInteraction = ToolSetCategoryMapping.resolve(ToolSetCategory.CORE_INTERACTION).scriptedToolNames
    val combined = ToolSetCategoryMapping.resolve(
      setOf(ToolSetCategory.NAVIGATION, ToolSetCategory.CORE_INTERACTION),
    ).scriptedToolNames
    assertEquals(navigation + coreInteraction, combined)
  }
}
