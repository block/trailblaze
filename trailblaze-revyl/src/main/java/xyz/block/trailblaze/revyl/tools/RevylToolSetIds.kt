package xyz.block.trailblaze.revyl.tools

/**
 * Canonical YAML toolset IDs for the Revyl driver.
 *
 * Single source of truth for every caller that needs the Revyl LLM tool surface
 * (host runner, CLI recording generator, MCP bridge). Mirrors
 * [xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds] and
 * [xyz.block.trailblaze.playwright.tools.WebToolSetIds].
 *
 * Resolve these via [xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog.resolveForDriver]
 * with either [xyz.block.trailblaze.devices.TrailblazeDriverType.REVYL_ANDROID] or
 * [xyz.block.trailblaze.devices.TrailblazeDriverType.REVYL_IOS] — both YAML toolsets list both
 * Revyl drivers under `drivers:`.
 */
object RevylToolSetIds {
  val ALL: List<String> = listOf("revyl_core", "revyl_verification", "memory")
}
