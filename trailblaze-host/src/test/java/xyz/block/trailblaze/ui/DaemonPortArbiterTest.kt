package xyz.block.trailblaze.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the duplicate-daemon exit decision: a process that loses the daemon-port bind must exit —
 * as a benign duplicate when a rival daemon answers on the port (handing off window-show intent
 * for non-headless launches), or as a startup failure when nothing answers.
 */
class DaemonPortArbiterTest {

  @Test
  fun `rival daemon running on non-headless launch exits as duplicate and shows winner window`() {
    assertEquals(
      PortBindFailureAction.ExitAsDuplicate(requestShowWindow = true),
      classifyPortBindFailure(rivalDaemonIsRunning = true, headless = false),
    )
  }

  @Test
  fun `rival daemon running on headless launch exits as duplicate without showing a window`() {
    assertEquals(
      PortBindFailureAction.ExitAsDuplicate(requestShowWindow = false),
      classifyPortBindFailure(rivalDaemonIsRunning = true, headless = true),
    )
  }

  @Test
  fun `no rival daemon means the bind failure is a genuine startup error`() {
    assertEquals(
      PortBindFailureAction.ExitAsStartupFailure,
      classifyPortBindFailure(rivalDaemonIsRunning = false, headless = false),
    )
    assertEquals(
      PortBindFailureAction.ExitAsStartupFailure,
      classifyPortBindFailure(rivalDaemonIsRunning = false, headless = true),
    )
  }
}
