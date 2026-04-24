package xyz.block.trailblaze.toolcalls

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [TrailblazeToolSetCatalog.entryYamlToolNames].
 *
 * The catalog-level yaml-name lookup is the load-bearing primitive for every live MCP
 * consumer that advertises YAML-defined tools alongside class-backed ones (inner-agent
 * fallback, subagent orchestrator, dynamic toolset manager, direct executor). These
 * assertions pin the contract so a refactor that swaps the lookup shape can't silently
 * drop the YAML half.
 */
class TrailblazeToolSetCatalogTest {

  @Test
  fun `entryYamlToolNames returns pressBack for navigation`() {
    val yamlNames = TrailblazeToolSetCatalog.entryYamlToolNames("navigation")
    assertTrue(
      yamlNames.any { it.toolName == "pressBack" },
      "navigation catalog entry should expose pressBack (YAML-defined). Got: $yamlNames",
    )
  }

  @Test
  fun `entryYamlToolNames returns empty set for unknown id`() {
    // Mirrors the lenient 'missing id is an empty set, not an error' semantics called out
    // in entryYamlToolNames' KDoc — downstream callers rely on this to compose resolutions
    // without wrapping in try/catch.
    assertEquals(emptySet(), TrailblazeToolSetCatalog.entryYamlToolNames("does-not-exist"))
  }
}
