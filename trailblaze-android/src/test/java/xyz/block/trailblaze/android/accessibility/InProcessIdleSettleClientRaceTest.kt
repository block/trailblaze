package xyz.block.trailblaze.android.accessibility

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the observable contract of [InProcessIdleSettleClient.raceIdleAgainstHeuristic] — the settle
 * race that powers "extreme speed mode". Pure JVM: the idle detector probe is injected, so no device and
 * no live idle detector socket are needed. Asserts what callers actually depend on (which arm wins, that
 * the loser is signaled to stop, and that heuristic errors propagate), not how the race is
 * implemented.
 */
class InProcessIdleSettleClientRaceTest {

  /** Idle detector answers IDLE first → its label is returned AND the heuristic arm is told to bail. */
  @Test
  fun inProcessIdleWins_returnsInProcessIdleLabel_andSignalsHeuristicToExit() {
    val heuristicSawEarlyExit = AtomicBoolean(false)
    val heuristicFinished = CountDownLatch(1)
    val heuristic: (earlyExit: () -> Boolean) -> Boolean = { earlyExit ->
      // Model the real heuristic: poll earlyExit and bail as soon as the idle detector wins,
      // reporting "did not settle" for the cancelled arm (waitForSettled's contract).
      val deadline = System.nanoTime() + 2_000_000_000L
      while (!earlyExit() && System.nanoTime() < deadline) {
        Thread.sleep(2)
      }
      heuristicSawEarlyExit.set(earlyExit())
      heuristicFinished.countDown()
      !earlyExit()
    }

    val winner =
      InProcessIdleSettleClient.raceIdleAgainstHeuristic(
        timeoutMs = 5_000,
        heuristic = heuristic,
        inProcessIdleProbe = { "IDLE 3" },
      )

    assertEquals("inprocess-idle IDLE 3", winner)
    // The race returns to the caller the moment the idle detector wins — the losing heuristic arm may
    // still be mid-poll. Wait for it to record what it observed before asserting on it.
    assertTrue(heuristicFinished.await(5, TimeUnit.SECONDS), "losing heuristic arm never finished")
    assertTrue(heuristicSawEarlyExit.get(), "losing heuristic arm must observe earlyExit == true")
  }

  /** No idle detector attached (probe returns null) → the heuristic wins and its label is returned. */
  @Test
  fun inProcessIdleUnavailable_heuristicWins() {
    val winner =
      InProcessIdleSettleClient.raceIdleAgainstHeuristic(
        timeoutMs = 5_000,
        heuristic = { true },
        inProcessIdleProbe = { null },
      )

    assertEquals("event-quiet heuristic", winner)
  }

  /**
   * A non-IDLE idle detector reply (TIMEOUT) does not win — the heuristic still decides. Guards against the
   * idle detector arm racing ahead on a timeout verdict.
   */
  @Test
  fun inProcessIdleTimeoutReply_doesNotWin() {
    val winner =
      InProcessIdleSettleClient.raceIdleAgainstHeuristic(
        timeoutMs = 5_000,
        heuristic = { false },
        inProcessIdleProbe = { "TIMEOUT 2000" },
      )

    assertEquals("timeout (neither arm settled)", winner)
  }

  /**
   * A heuristic that timed out (returned false) must NOT claim the win — a genuine settle timeout
   * surfaces as the timeout label instead of masquerading as a heuristic win in the logs. Also
   * pins that the caller is released as soon as the heuristic finishes, not at the slack window.
   */
  @Test
  fun heuristicTimesOut_neitherArmSettled() {
    val startNs = System.nanoTime()
    val winner =
      InProcessIdleSettleClient.raceIdleAgainstHeuristic(
        timeoutMs = 30_000,
        heuristic = { false },
        inProcessIdleProbe = { null },
      )

    assertEquals("timeout (neither arm settled)", winner)
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
    assertTrue(elapsedMs < 5_000, "caller must be released when the heuristic finishes, took ${elapsedMs}ms")
  }

  /**
   * A heuristic that THROWS (e.g. a wedged UiAutomation handle) has its error rethrown to the
   * caller when the idle detector didn't win — the same propagation the non-race path has — instead of
   * being swallowed into a silent full-slack stall.
   */
  @Test
  fun heuristicThrows_inProcessIdleSilent_errorPropagates() {
    val startNs = System.nanoTime()
    val thrown =
      assertFailsWith<IllegalStateException> {
        InProcessIdleSettleClient.raceIdleAgainstHeuristic(
          timeoutMs = 30_000,
          heuristic = { throw IllegalStateException("UiAutomation wedged") },
          inProcessIdleProbe = { null },
        )
      }

    assertEquals("UiAutomation wedged", thrown.message)
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
    assertTrue(elapsedMs < 5_000, "error must propagate promptly, not after the slack window (${elapsedMs}ms)")
  }

  /** When the idle detector wins, a late heuristic error is a cancellation byproduct — the win stands. */
  @Test
  fun heuristicThrows_afterInProcessIdleWon_inProcessIdleLabelStands() {
    val winner =
      InProcessIdleSettleClient.raceIdleAgainstHeuristic(
        timeoutMs = 5_000,
        heuristic = { earlyExit ->
          // Wait until the idle detector has won, then die — models an arm interrupted mid-cancellation.
          val deadline = System.nanoTime() + 2_000_000_000L
          while (!earlyExit() && System.nanoTime() < deadline) {
            Thread.sleep(2)
          }
          throw IllegalStateException("late failure during cancellation")
        },
        inProcessIdleProbe = { "IDLE 5" },
      )

    assertEquals("inprocess-idle IDLE 5", winner)
  }

  /**
   * Off by default: with no Android `SystemProperties` on the JVM test classpath the gate reads
   * unset, so the standard settle path runs unchanged. This is the "disabling the sysprop yields
   * today's behavior" guarantee.
   */
  @Test
  fun disabledByDefault_offDevice() {
    assertEquals(false, InProcessIdleSettleClient.isEnabled())
  }

  /** The accepted sysprop values: `1`/`true` case-insensitive, everything else off. */
  @Test
  fun parseEnabled_acceptedValues() {
    assertTrue(InProcessIdleSettleClient.parseEnabled("1"))
    assertTrue(InProcessIdleSettleClient.parseEnabled("true"))
    assertTrue(InProcessIdleSettleClient.parseEnabled("TRUE"))
    assertTrue(InProcessIdleSettleClient.parseEnabled("True"))
    assertFalse(InProcessIdleSettleClient.parseEnabled(""))
    assertFalse(InProcessIdleSettleClient.parseEnabled("0"))
    assertFalse(InProcessIdleSettleClient.parseEnabled("false"))
    assertFalse(InProcessIdleSettleClient.parseEnabled("yes"))
  }
}
