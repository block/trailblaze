package xyz.block.trailblaze.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/**
 * Pins the pure decision behind [cliTryStartDaemon]: whether a failed daemon connection is allowed
 * to spawn a daemon, and whether it is worth waiting for one instead.
 *
 * The regression this guards is daemon pile-up. A failed connect does not distinguish "no daemon"
 * from "a daemon that is alive but wedged" — both throw the same exception at the CLI. Auto-starting
 * on that signal alone meant every retried `trailblaze run` against one wedged daemon spawned
 * another one: `waitForDaemon` then went green off the *original* daemon's still-answering `/ping`,
 * the connect failed again, and the fresh JVM stayed behind. Ten of them ended up contending for a
 * single emulator.
 *
 * So the veto is keyed on port ownership, not on daemon health: if anything holds the port, this
 * CLI must not put a second daemon on it, no matter how unresponsive the incumbent is.
 *
 * All six combinations are pinned. The kill switch only ever decides whether to *spawn*, so it
 * changes the answer for exactly one of the three holds — and the two it must not change are the
 * ones this file is mostly here to hold still.
 */
class DaemonAutoStartActionTest {

  @Test
  fun `a free port with auto-start enabled spawns`() {
    assertThat(daemonAutoStartAction(autoStartDisabled = false, hold = DaemonPortHold.FREE))
      .isEqualTo(DaemonAutoStartAction.SPAWN)
  }

  @Test
  fun `the kill-switch refuses a free port`() {
    assertThat(daemonAutoStartAction(autoStartDisabled = true, hold = DaemonPortHold.FREE))
      .isEqualTo(DaemonAutoStartAction.REFUSE_AUTOSTART_DISABLED)
  }

  @Test
  fun `a listening port is waited for rather than spawned past`() {
    // The pile-up case: the connect that got us here already failed, and the port still has a
    // listener. Anything other than waiting here stacks daemons on one port and one device.
    assertThat(daemonAutoStartAction(autoStartDisabled = false, hold = DaemonPortHold.LISTENING))
      .isEqualTo(DaemonAutoStartAction.WAIT_FOR_INCUMBENT)
  }

  @Test
  fun `a listening port is still waited for when the kill-switch forbids spawning`() {
    // The kill switch says "do not spawn", and waiting spawns nothing — so it must not turn a
    // listener into a refusal. This is the combination where waiting matters MOST: the switch is
    // how the user is told to run a daemon by hand (`trailblaze app`, which is also what every
    // Gradle `Test` task's environment expects), and a cold uber-jar binds the port 30s+ before it
    // serves. Refusing here refuses the very daemon the error text asks for, and only during its
    // boot window, which reads as flaky rather than as a rule.
    assertThat(daemonAutoStartAction(autoStartDisabled = true, hold = DaemonPortHold.LISTENING))
      .isEqualTo(DaemonAutoStartAction.WAIT_FOR_INCUMBENT)
  }

  @Test
  fun `a held-but-silent port is refused rather than waited for`() {
    // Nothing accepted a connection across the settle window, so there is no listener here to
    // send the `/ping` a wait would be waiting for — a daemon still finishing its boot accepts as
    // soon as it binds, which reads as LISTENING instead.
    assertThat(daemonAutoStartAction(autoStartDisabled = false, hold = DaemonPortHold.HELD_SILENT))
      .isEqualTo(DaemonAutoStartAction.REFUSE_PORT_HELD)
  }

  @Test
  fun `a held port is reported as held even with the kill-switch set`() {
    // The CI-default path: ordering the kill switch first sent a wedged-daemon run down the "not
    // running, start one" message — the misdirection this decision removes. Neither refusal spawns
    // anything, so answering with the accurate one costs the kill switch nothing.
    assertThat(daemonAutoStartAction(autoStartDisabled = true, hold = DaemonPortHold.HELD_SILENT))
      .isEqualTo(DaemonAutoStartAction.REFUSE_PORT_HELD)
  }
}
