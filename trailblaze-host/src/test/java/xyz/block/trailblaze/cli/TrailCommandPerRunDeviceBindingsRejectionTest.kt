package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities

/**
 * Pins when `trailblaze run --bind` / `--configuration` refuses to delegate.
 *
 * A daemon decodes run requests with `ignoreUnknownKeys`, so one that predates these fields drops
 * them and resolves the device set from its own startup `TRAILBLAZE_DEVICE_BINDINGS` — a run
 * against the wrong devices that reports a pass. The build-version check does not cover it: every
 * source checkout reports "Developer Build", which compares equal.
 */
class TrailCommandPerRunDeviceBindingsRejectionTest {

  @Test
  fun `a run with no per-run device fields never refuses, and never reads the daemon's status`() {
    // Every ordinary run goes through this call site, so a capability check that could reject one
    // would be a regression in the common path — and the status round trip must not be spent on a
    // run that doesn't depend on it, which is why the capabilities arrive as a lambda.
    var reads = 0
    assertNull(
      TrailCommand.perRunDeviceBindingsRejection(
        requestsPerRunDeviceBindings = false,
        daemonCapabilities = { reads++; emptySet() },
      ),
    )
    assertEquals(0, reads, "an ordinary run must not pay for a capability read")
  }

  @Test
  fun `a capable daemon is delegated to`() {
    assertNull(
      TrailCommand.perRunDeviceBindingsRejection(
        requestsPerRunDeviceBindings = true,
        daemonCapabilities = { CliDaemonCapabilities.ALL },
      ),
    )
  }

  @Test
  fun `a daemon that predates the fields is refused, naming the env var it would fall back to`() {
    val reason = TrailCommand.perRunDeviceBindingsRejection(
      requestsPerRunDeviceBindings = true,
      daemonCapabilities = { emptySet() },
    )

    assertNotNull(reason, "an older daemon must be refused, not silently sent the fields")
    assertTrue(
      "TRAILBLAZE_DEVICE_BINDINGS" in reason,
      "the reason must name the fallback that would drive the wrong devices; got: $reason",
    )
  }

  @Test
  fun `a daemon advertising other capabilities but not this one is still refused`() {
    // The check must key on the specific capability, not on "advertises anything at all" — a
    // future capability set would otherwise make a daemon look able to honor bindings.
    assertNotNull(
      TrailCommand.perRunDeviceBindingsRejection(
        requestsPerRunDeviceBindings = true,
        daemonCapabilities = { setOf("some.other.capability") },
      ),
    )
  }

  @Test
  fun `an unreadable status proceeds rather than blocking a working setup`() {
    // Null means the liveness probe succeeded but the status read didn't. Refusing here would turn
    // a transient hiccup into a failed run; `checkAndRestartStaleDaemon` treats the same read the
    // same way.
    assertNull(
      TrailCommand.perRunDeviceBindingsRejection(
        requestsPerRunDeviceBindings = true,
        daemonCapabilities = { null },
      ),
    )
  }
}
