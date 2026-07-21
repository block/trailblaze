package xyz.block.trailblaze.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/**
 * Pins the pure decision behind [checkAndRestartStaleDaemon]: whether a version-mismatched
 * daemon is restarted, kept because it's busy, or left alone because versions match. The
 * busy case is the one that matters most — restarting a daemon with in-flight runs severs
 * them (the daemon may be mid-run for another shell/checkout on the machine-global port),
 * which is the exact regression this guards against.
 */
class StaleDaemonActionTest {

  @Test
  fun `matching versions keep the daemon`() {
    assertThat(staleDaemonAction("v2", "v2", activeRuns = 0)).isEqualTo(StaleDaemonAction.KEEP_OK)
    // Even with runs in flight, a matching version needs no action.
    assertThat(staleDaemonAction("v2", "v2", activeRuns = 3)).isEqualTo(StaleDaemonAction.KEEP_OK)
  }

  @Test
  fun `null daemon version keeps the daemon`() {
    // Older daemon that doesn't report a version — nothing to compare, so don't restart.
    assertThat(staleDaemonAction("v2", null, activeRuns = 0)).isEqualTo(StaleDaemonAction.KEEP_OK)
  }

  @Test
  fun `mismatch with no in-flight runs restarts`() {
    assertThat(staleDaemonAction("v2", "v1", activeRuns = 0)).isEqualTo(StaleDaemonAction.RESTART)
  }

  @Test
  fun `mismatch with in-flight runs keeps the daemon busy`() {
    assertThat(staleDaemonAction("v2", "v1", activeRuns = 1)).isEqualTo(StaleDaemonAction.KEEP_BUSY)
    assertThat(staleDaemonAction("v2", "v1", activeRuns = 5)).isEqualTo(StaleDaemonAction.KEEP_BUSY)
  }
}
