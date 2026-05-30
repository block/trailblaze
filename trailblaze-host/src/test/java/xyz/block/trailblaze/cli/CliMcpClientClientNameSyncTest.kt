package xyz.block.trailblaze.cli

import org.junit.Test
import xyz.block.trailblaze.mcp.TRAILBLAZE_CLI_CLIENT_NAME
import kotlin.test.assertEquals

/**
 * Pins the contract that [CliMcpClient.CLIENT_NAME] and the server-side
 * [TRAILBLAZE_CLI_CLIENT_NAME] constant — used by
 * `TrailblazeMcpServer.pinMostRecentUnboundMcpSession` to discriminate "real
 * MCP clients" from CLI one-shots — stay in sync.
 *
 * If these values drift, every shell `device connect` would silently start
 * pinning *its own* CLI session to the device (because the filter would no
 * longer recognize the CLI's `clientInfo.name`). This test catches that
 * regression at compile-and-test time instead of in production.
 */
class CliMcpClientClientNameSyncTest {
  @Test
  fun `CliMcpClient CLIENT_NAME matches server-side TRAILBLAZE_CLI_CLIENT_NAME`() {
    assertEquals(
      TRAILBLAZE_CLI_CLIENT_NAME,
      CliMcpClient.CLIENT_NAME,
      "Drift would break MCP-session-pinning's real-vs-CLI client filter",
    )
  }
}
