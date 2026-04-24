package xyz.block.trailblaze.playwright.tools

/**
 * Canonical YAML toolset IDs for the Playwright-backed web drivers (both `PLAYWRIGHT_NATIVE`
 * and `PLAYWRIGHT_ELECTRON` — the underlying YAML toolsets list both driver keys).
 *
 * Single source of truth for every caller that needs the web LLM tool surface
 * (host base tests, host runner recording generation, MCP bridge). Mirrors
 * [xyz.block.trailblaze.revyl.tools.RevylToolSetIds] and
 * [xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds].
 *
 * Resolve these via [xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog.resolveForDriver]
 * with the appropriate [xyz.block.trailblaze.devices.TrailblazeDriverType].
 */
object WebToolSetIds {
  val ALL: List<String> = listOf("web_core", "web_verification", "memory")
}
