package xyz.block.trailblaze.ui

import xyz.block.trailblaze.devices.TrailblazePortRangeConflictException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the duplicate-daemon exit decision: a process that loses the daemon-port bind must exit —
 * as a benign duplicate when a rival daemon answers on the port (handing off window-show intent
 * for non-headless launches), or as a startup failure when nothing answers. A port the
 * configuration made unusable is a third outcome that must never reach the probe at all.
 */
class DaemonPortArbiterTest {

  private val bindFailure = IOException("Address already in use")

  @Test
  fun `rival daemon running on non-headless launch exits as duplicate and shows winner window`() {
    assertEquals(
      PortBindFailureAction.ExitAsDuplicate(requestShowWindow = true),
      classifyPortBindFailure(cause = bindFailure, headless = false, probeForRivalDaemon = { true }),
    )
  }

  @Test
  fun `rival daemon running on headless launch exits as duplicate without showing a window`() {
    assertEquals(
      PortBindFailureAction.ExitAsDuplicate(requestShowWindow = false),
      classifyPortBindFailure(cause = bindFailure, headless = true, probeForRivalDaemon = { true }),
    )
  }

  @Test
  fun `no rival daemon means the bind failure is a genuine startup error`() {
    assertEquals(
      PortBindFailureAction.ExitAsStartupFailure,
      classifyPortBindFailure(cause = bindFailure, headless = false, probeForRivalDaemon = { false }),
    )
    assertEquals(
      PortBindFailureAction.ExitAsStartupFailure,
      classifyPortBindFailure(cause = bindFailure, headless = true, probeForRivalDaemon = { false }),
    )
  }

  /**
   * The port is one a device can be allocated, so a device's own `adb forward` can answer the
   * probe. Probing would classify a configuration error as "another daemon owns this port" and
   * exit 0 — a silent success in place of the message the user needs.
   */
  @Test
  fun `a configured port that no bind could win is a config error and is never probed`() {
    var probed = false
    listOf(false, true).forEach { headless ->
      assertEquals(
        PortBindFailureAction.ExitAsConfigError,
        classifyPortBindFailure(
          cause = TrailblazePortRangeConflictException("TRAILBLAZE_PORT is 52900"),
          headless = headless,
          probeForRivalDaemon = { probed = true; true },
        ),
      )
    }
    assertFalse(probed, "A config error must not consult the port — a device can answer on it")
  }
}
