package xyz.block.trailblaze.mcp

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Unit-level contract pins for [McpToolProfile].
 *
 * Catches regressions to the set of tools exposed under MINIMAL — the profile
 * used by the CLI (`CliMcpClient` defaults to MINIMAL) as well as by external
 * MCP clients like Claude Code and Goose. Each entry below corresponds to a
 * CLI command that calls the named MCP tool via [CliMcpClient.callTool], so
 * removing one would silently break a public CLI surface; the integration
 * smoke tests would catch it but only after a device/emulator boot — these
 * unit assertions catch it before the build leaves the JVM.
 */
class McpToolProfileTest {

  @Test
  fun `MINIMAL profile exposes setSessionTargetForBoundDevice`() {
    // The CLI's `device connect --target` / `device rebind --target` paths
    // invoke this tool via `CliMcpClient.setSessionTargetForBoundDevice`.
    // Dropping it from MINIMAL surfaces in CI as
    // `Tool setSessionTargetForBoundDevice not found` on any smoke test that
    // exercises those commands.
    assertTrue(
      McpToolProfile.TOOL_SET_SESSION_TARGET in McpToolProfile.MINIMAL_TOOL_NAMES,
      "setSessionTargetForBoundDevice must be in MINIMAL_TOOL_NAMES — " +
        "the CLI's device connect/rebind --target paths depend on it.",
    )
  }
}
