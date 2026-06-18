package xyz.block.trailblaze.mcp.toolsets

import xyz.block.trailblaze.mcp.executor.ConfigurableMockBridge
import xyz.block.trailblaze.mcp.executor.DirectMcpToolExecutor
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.allToolNames
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-surface parity guard for toolset-delivered scripted tools.
 *
 * A scripted (`.ts` / `.js`) tool listed by bare name in a toolset YAML (e.g. `openUrl` in
 * `navigation`) must appear on EVERY advertised tool surface — exactly like a class-backed or
 * YAML-defined tool. The repeated regression was a surface that read `toolClasses + yamlToolNames`
 * but forgot `scriptedToolNames`, silently dropping the scripted tool from advertisement, gating,
 * or docs. [xyz.block.trailblaze.toolcalls.TrailblazeToolSurface.allToolNames] is the single union
 * accessor that fixed that; these tests pin the invariant so a future surface can't re-introduce
 * the drop in just one place while the others stay correct.
 *
 * `openUrl` is the canary: the first framework tool delivered to a toolset as a scripted tool
 * rather than via a target's own `target.tools:` list. [DirectMcpToolExecutor] and
 * `ToolDiscoveryToolSet` both advertise from the same [ToolSetCategoryMapping.resolve] source, so
 * pinning the executor + the resolved surface covers the shared runtime contract.
 */
class ScriptedToolSurfaceParityTest {

  private val canary = ToolName("openUrl")
  private val canaryCategories = setOf(ToolSetCategory.NAVIGATION)

  @Test
  fun `the canary openUrl is delivered as a scripted tool via the navigation toolset`() {
    // Anchor: openUrl really is a SCRIPTED tool in the navigation catalog entry (not class/YAML).
    // If this fails, openUrl was moved/renamed/re-backed and the parity assertions below need
    // re-anchoring on whatever toolset-delivered scripted tool exists now.
    val navigation = TrailblazeToolSetCatalog.defaultEntries().firstOrNull { it.id == "navigation" }
    assertNotNull(navigation, "navigation catalog entry should exist")
    assertTrue(
      canary in navigation.scriptedToolNames,
      "openUrl should be a scripted tool in navigation. scriptedToolNames=${navigation.scriptedToolNames}",
    )
  }

  @Test
  fun `allToolNames unions the scripted partition of the resolved navigation surface`() {
    val resolved = ToolSetCategoryMapping.resolve(canaryCategories)
    // There is a scripted tool to lose...
    assertTrue(
      resolved.scriptedToolNames.isNotEmpty(),
      "navigation should resolve at least one scripted tool (the parity guard is vacuous otherwise)",
    )
    // ...and the single union accessor includes it alongside class-backed + YAML-defined tools.
    assertTrue(
      canary in resolved.allToolNames,
      "allToolNames must include the scripted openUrl. allToolNames=${resolved.allToolNames}",
    )
  }

  @Test
  fun `DirectMcpToolExecutor advertises every resolved tool name across all three backings`() {
    // The general invariant — not just openUrl: the executor's advertised surface must be a
    // superset of the resolved surface (class-backed + YAML-defined + scripted). A surface that
    // dropped the scripted partition would leave a resolved name unadvertised and fail here.
    val resolved = ToolSetCategoryMapping.resolve(canaryCategories)
    val advertised = DirectMcpToolExecutor(ConfigurableMockBridge(), canaryCategories)
      .getAvailableTools()
      .map { it.name }
      .toSet()

    val missing = resolved.allToolNames.map { it.toolName }.toSet() - advertised
    assertTrue(
      missing.isEmpty(),
      "DirectMcpToolExecutor must advertise every resolved tool (class/YAML/scripted). Missing: $missing",
    )
    assertTrue("openUrl" in advertised, "openUrl (scripted) must be advertised by DirectMcpToolExecutor")
  }

  @Test
  fun `catalog entry toolNames includes the scripted canary`() {
    // ToolSetCatalogEntry.toolNames is the reference union impl (delegates to allToolNames); it must
    // list scripted tools so name-based consumers (the target/external-config doc generators, the
    // CLI acceptance gates) see the same surface the runtime advertises.
    val navigation = TrailblazeToolSetCatalog.defaultEntries().first { it.id == "navigation" }
    assertTrue(
      "openUrl" in navigation.toolNames,
      "navigation.toolNames should include the scripted openUrl. toolNames=${navigation.toolNames}",
    )
  }
}
