package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities

/**
 * Pins when `trailblaze session save` refuses to delegate a MULTI-DEVICE save.
 *
 * The daemon's MCP `session` tool decodes its arguments leniently, so a daemon build that predates
 * the SAVE action's `configuration` argument drops it silently and saves the session WITHOUT its
 * multi-device cast — a wrong file reported as a pass. The build-version check does not cover it:
 * every source checkout reports "Developer Build", which compares equal.
 *
 * Two inputs reach that wrong file: an explicit `--configuration`, and a session holding a
 * named-device ROSTER, whose cast the daemon synthesizes under a DEFAULTED name with no flag typed.
 * Gating only the flag would leave the ordinary `session save -d seller` unguarded.
 */
class SessionSaveConfigurationRejectionTest {

  @Test
  fun `a save without --configuration never refuses, and never reads the daemon's status`() {
    // Every ordinary save goes through this call site — it must not pay a status round trip,
    // which is why the capabilities arrive as a lambda.
    var reads = 0
    assertNull(
      SessionSaveCommand.sessionSaveConfigurationRejection(
        configurationRequested = false,
        rosterPresent = false,
        daemonCapabilities = { reads++; emptySet() },
      ),
    )
    assertEquals(0, reads, "an ordinary save must not pay for a capability read")
  }

  @Test
  fun `a roster save with no --configuration is refused by an older daemon`() {
    // The configuration name DEFAULTS, so this is the common multi-device save and it produces the
    // same cast-less trail. Keying the gate on the flag alone let it through.
    val reason = SessionSaveCommand.sessionSaveConfigurationRejection(
      configurationRequested = false,
      rosterPresent = true,
      daemonCapabilities = { setOf(CliDaemonCapabilities.PER_RUN_DEVICE_BINDINGS) },
    )

    assertNotNull(reason, "a roster save must be refused by a daemon that would drop its cast")
    assertTrue(
      "config.devices" in reason,
      "the reason must name what the saved trail would be missing; got: \$reason",
    )
  }

  @Test
  fun `a capable daemon is delegated a roster save`() {
    assertNull(
      SessionSaveCommand.sessionSaveConfigurationRejection(
        configurationRequested = false,
        rosterPresent = true,
        daemonCapabilities = { CliDaemonCapabilities.ALL },
      ),
    )
  }

  @Test
  fun `a capable daemon is delegated to`() {
    assertNull(
      SessionSaveCommand.sessionSaveConfigurationRejection(
        configurationRequested = true,
        rosterPresent = false,
        daemonCapabilities = { CliDaemonCapabilities.ALL },
      ),
    )
  }

  @Test
  fun `a daemon that predates the argument is refused, naming what it would silently drop`() {
    val reason = SessionSaveCommand.sessionSaveConfigurationRejection(
      configurationRequested = true,
      rosterPresent = false,
      daemonCapabilities = { emptySet() },
    )

    assertNotNull(reason, "an older daemon must be refused, not silently sent the argument")
    assertTrue(
      "--configuration" in reason,
      "the reason must name the flag the daemon would ignore; got: $reason",
    )
  }

  @Test
  fun `a daemon advertising other capabilities but not this one is still refused`() {
    // Key on the specific capability, not "advertises anything at all" — a daemon new enough for
    // per-run bindings can still predate roster-based session save.
    assertNotNull(
      SessionSaveCommand.sessionSaveConfigurationRejection(
        configurationRequested = true,
        rosterPresent = false,
        daemonCapabilities = { setOf(CliDaemonCapabilities.PER_RUN_DEVICE_BINDINGS) },
      ),
    )
  }

  @Test
  fun `an unreadable status proceeds rather than blocking a working setup`() {
    // Null means the liveness probe succeeded but the status read didn't. Refusing here would turn
    // a transient hiccup into a failed save.
    assertNull(
      SessionSaveCommand.sessionSaveConfigurationRejection(
        configurationRequested = true,
        rosterPresent = true,
        daemonCapabilities = { null },
      ),
    )
  }
}
