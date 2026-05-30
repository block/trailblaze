package xyz.block.trailblaze.cli

import org.junit.Test
import xyz.block.trailblaze.mcp.newtools.SessionToolSet
import kotlin.test.assertEquals

/**
 * Pins the contract that [CliMcpClient.TARGET_SOURCE_SESSION_OVERRIDE] and the
 * server-side [SessionToolSet.TARGET_SOURCE_SESSION_OVERRIDE] — the wire string
 * that `session(action=INFO)` writes into its `targetSource` field — stay in
 * sync.
 *
 * If these drift, the announcing variant of
 * [CliMcpClient.setSessionTargetForBoundDeviceAnnouncingChange] silently
 * regresses: the gating condition `priorSource == TARGET_SOURCE_SESSION_OVERRIDE`
 * goes false on the host side because the daemon now writes a different
 * literal, so warm `device connect <same-device> --target Y` reconnects stop
 * announcing the target change — exactly the regression the announcing helper
 * was added to prevent. A compile-and-test-time pin is the cheapest way to
 * keep that from happening unnoticed.
 *
 * Sibling test pattern to [CliMcpClientClientNameSyncTest].
 */
class CliMcpClientTargetSourceSyncTest {
  @Test
  fun `CliMcpClient TARGET_SOURCE_SESSION_OVERRIDE matches server-side SessionToolSet`() {
    assertEquals(
      SessionToolSet.TARGET_SOURCE_SESSION_OVERRIDE,
      CliMcpClient.TARGET_SOURCE_SESSION_OVERRIDE,
      "Drift would silently disable the warm-reuse target-swap announcement " +
        "in setSessionTargetForBoundDeviceAnnouncingChange",
    )
  }
}
