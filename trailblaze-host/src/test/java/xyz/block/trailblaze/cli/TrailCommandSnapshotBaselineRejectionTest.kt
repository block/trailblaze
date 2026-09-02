package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities

/**
 * Pins when `trailblaze run --snapshot-baseline` refuses to delegate.
 *
 * A daemon decodes run requests with `ignoreUnknownKeys`, so one that predates the field drops it
 * and runs the trail with no comparison — and a gate that never ran is indistinguishable from one
 * that passed. The build-version check does not cover it: every source checkout reports
 * "Developer Build", which compares equal.
 */
class TrailCommandSnapshotBaselineRejectionTest {

  @Test
  fun `a run with no baseline never refuses, and never reads the daemon's status`() {
    var reads = 0
    assertNull(
      TrailCommand.snapshotBaselineRejection(
        requestsSnapshotBaseline = false,
        daemonCapabilities = { reads++; emptySet() },
      ),
    )
    assertEquals(0, reads, "an ordinary run must not pay for a capability read")
  }

  @Test
  fun `a capable daemon is delegated to`() {
    assertNull(
      TrailCommand.snapshotBaselineRejection(
        requestsSnapshotBaseline = true,
        daemonCapabilities = { CliDaemonCapabilities.ALL },
      ),
    )
  }

  @Test
  fun `a daemon that predates the flag is refused, saying no comparison would happen`() {
    val reason = TrailCommand.snapshotBaselineRejection(
      requestsSnapshotBaseline = true,
      daemonCapabilities = { emptySet() },
    )

    assertNotNull(reason, "an older daemon must be refused, not silently sent the field")
    assertTrue(
      "--snapshot-baseline" in reason,
      "the reason must name the flag that would be ignored; got: $reason",
    )
  }

  @Test
  fun `a daemon advertising other capabilities but not this one is still refused`() {
    // Keying on "advertises anything at all" would make a daemon that only knows about device
    // bindings look able to honor a baseline.
    assertNotNull(
      TrailCommand.snapshotBaselineRejection(
        requestsSnapshotBaseline = true,
        daemonCapabilities = { setOf(CliDaemonCapabilities.PER_RUN_DEVICE_BINDINGS) },
      ),
    )
  }

  @Test
  fun `an unreadable status proceeds rather than blocking a working setup`() {
    assertNull(
      TrailCommand.snapshotBaselineRejection(
        requestsSnapshotBaseline = true,
        daemonCapabilities = { null },
      ),
    )
  }
}
