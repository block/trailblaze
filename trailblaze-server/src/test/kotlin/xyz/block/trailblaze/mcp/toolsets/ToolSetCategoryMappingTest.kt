package xyz.block.trailblaze.mcp.toolsets

import org.junit.Test
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the new bundled category resolvers on [ToolSetCategoryMapping].
 *
 * These pin the shape the production consumers (DirectMcpToolExecutor, SubagentOrchestrator,
 * inner-agent fallback in TrailblazeMcpServer, DynamicToolSetManager) rely on — namely that
 * `resolve(...)` covers both class-backed and YAML-defined tools in one call, so a class-only
 * refactor can't silently drop the YAML half like the pressBack migration did.
 */
class ToolSetCategoryMappingTest {

  @Test
  fun `resolve NAVIGATION includes pressBack in yaml tool names`() {
    val resolved = ToolSetCategoryMapping.resolve(ToolSetCategory.NAVIGATION)
    assertTrue(
      resolved.yamlToolNames.any { it.toolName == "pressBack" },
      "NAVIGATION category should expose pressBack (YAML-defined). Got: ${resolved.yamlToolNames}",
    )
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
