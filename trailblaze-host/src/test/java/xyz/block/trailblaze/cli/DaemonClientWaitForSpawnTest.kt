package xyz.block.trailblaze.cli

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [DaemonClient.waitForDaemon]'s spawn-liveness escape hatch.
 *
 * Auto-start spawns a child to *become* the daemon, then polls a port. If that child dies on
 * startup the port will never come up, but the poll loop had no way to know: it printed progress
 * dots for the full 120s budget and then blamed a slow build. That's how a broken spawn argv
 * reached users looking like a timeout.
 *
 * These tests use an unbound port so `isRunningBlocking()` always fails, isolating the loop's
 * exit conditions.
 */
class DaemonClientWaitForSpawnTest {

  /**
   * Port nothing listens on, so every readiness check fails.
   *
   * Taken by binding an ephemeral port and releasing it, rather than hard-coding a number: a
   * fixed high port is inside Linux's ephemeral range and could already be held by another job
   * on a shared CI worker. Even then these tests would hold — a foreign listener fails the
   * readiness check the same way a closed port does — but a port that was demonstrably free at
   * test start keeps the failure mode out of the picture entirely.
   */
  private val deadPort = ServerSocket(0).use { it.localPort }

  @Test
  fun `a dead spawn ends the wait without burning the timeout budget`() {
    val polls = AtomicInteger(0)

    val started = DaemonClient(port = deadPort).use {
      it.waitForDaemon(
        maxWaitMs = 60_000,
        pollIntervalMs = 10,
        isSpawnAlive = { false },
        onPoll = { polls.incrementAndGet() },
      )
    }

    assertFalse(started, "a spawn that already exited can never bind the port")
    assertEquals(
      0,
      polls.get(),
      "the loop must bail before reporting progress — dots imply work still in flight",
    )
  }

  @Test
  fun `a live spawn keeps polling until the timeout`() {
    val polls = AtomicInteger(0)

    val started = DaemonClient(port = deadPort).use {
      it.waitForDaemon(
        maxWaitMs = 120,
        pollIntervalMs = 10,
        isSpawnAlive = { true },
        onPoll = { polls.incrementAndGet() },
      )
    }

    assertFalse(started, "nothing is listening, so this still times out")
    // At-least-one is the contract that separates this from the dead-spawn case above (exactly
    // zero). Asserting a specific count would just be measuring how long one failed connect
    // takes on the host.
    assertTrue(polls.get() >= 1, "a live spawn should keep polling; polls=${polls.get()}")
  }

  @Test
  fun `a spawn that dies mid-wait stops the loop at that point`() {
    val alive = AtomicBoolean(true)
    val polls = AtomicInteger(0)

    val started = DaemonClient(port = deadPort).use {
      it.waitForDaemon(
        maxWaitMs = 60_000,
        pollIntervalMs = 10,
        isSpawnAlive = { alive.get() },
        onPoll = { if (polls.incrementAndGet() >= 3) alive.set(false) },
      )
    }

    assertFalse(started)
    assertEquals(3, polls.get(), "the loop should stop on the first poll after the child exits")
  }

  @Test
  fun `callers that spawned nothing are unaffected by the default`() {
    val polls = AtomicInteger(0)

    // No isSpawnAlive argument: waiting on a daemon someone else owns must keep the old
    // poll-until-timeout behavior rather than exiting immediately.
    val started = DaemonClient(port = deadPort).use {
      it.waitForDaemon(maxWaitMs = 120, pollIntervalMs = 10, onPoll = { polls.incrementAndGet() })
    }

    assertFalse(started)
    assertTrue(polls.get() >= 1, "default must not short-circuit; polls=${polls.get()}")
  }
}
