package xyz.block.trailblaze.compose.driver.tools

/**
 * Canonical YAML toolset IDs for the Compose Desktop driver.
 *
 * Single source of truth for every caller that needs the Compose LLM tool surface
 * (host base test, host runner, MCP bridge). Mirrors [xyz.block.trailblaze.revyl.tools.RevylToolSetIds]
 * and [xyz.block.trailblaze.playwright.tools.WebToolSetIds].
 *
 * Resolve these via [xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog.resolveForDriver]
 * with [xyz.block.trailblaze.devices.TrailblazeDriverType.COMPOSE].
 */
object ComposeToolSetIds {
  val ALL: List<String> = listOf("compose_core", "compose_verification", "memory")
}
