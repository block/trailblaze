package xyz.block.trailblaze.scripting.subprocess

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubprocessIoCapacityTest {

  /**
   * The reason [SubprocessIoCapacity] exists, asserted as behavior rather than described in a
   * comment: a blocking read holds its dispatcher permit until it returns, so filling a capped
   * dispatcher with blocking reads stops *unrelated* work from ever being scheduled on it.
   *
   * This is what a session with one subprocess per scripted tool does to `Dispatchers.IO`, and why
   * the guard counts subprocesses against the cap instead of against CPU or memory. Uses an
   * explicit small `limitedParallelism` view so the case is deterministic and independent of the
   * host's real IO cap.
   */
  @Test
  fun `blocking calls hold dispatcher permits and starve unrelated work`() {
    val parallelism = 4
    @Suppress("OPT_IN_USAGE")
    val cappedDispatcher = Dispatchers.IO.limitedParallelism(parallelism)
    val scope = CoroutineScope(SupervisorJob())

    // `release.await()` stands in for the subprocess stdout read: an ordinary blocking call that
    // does not suspend, so it holds its permit rather than yielding it. Asserted with latches and
    // no coroutine timeout machinery — cancelling a coroutine that never received a permit is its
    // own tarpit, and the property under test does not need it.
    val release = CountDownLatch(1)
    val parkedStarted = CountDownLatch(parallelism)
    val extraRan = CountDownLatch(1)

    try {
      repeat(parallelism) {
        scope.launch(cappedDispatcher) {
          parkedStarted.countDown()
          release.await()
        }
      }
      assertTrue(
        parkedStarted.await(60, TimeUnit.SECONDS),
        "the parked tasks never started; every assertion below would be vacuous",
      )

      // Every permit is now held. Unrelated work on the same dispatcher is not merely slow — it
      // never runs at all. This is the daemon's state once one subprocess per scripted tool has
      // taken every Dispatchers.IO permit.
      scope.launch(cappedDispatcher) { extraRan.countDown() }
      assertTrue(
        !extraRan.await(3, TimeUnit.SECONDS),
        "work ran on a dispatcher whose every permit was held by a blocking call — if this is " +
          "reachable, blocking calls no longer pin permits and this guard is unnecessary",
      )

      // Releasing the blocking calls returns the permits, proving the starvation above was the
      // parking itself and not a dispatcher that simply never schedules.
      release.countDown()
      assertTrue(
        extraRan.await(60, TimeUnit.SECONDS),
        "permits were not released after the blocking calls returned",
      )
    } finally {
      release.countDown()
      scope.cancel()
    }
  }

  /** Guards the process-wide tally: a case that leaked a reservation would corrupt every later one. */
  @AfterTest
  fun noLeakedReservations() {
    assertEquals(
      0,
      SubprocessIoCapacity.outstandingPermits(),
      "a case left permits reserved; every later case in this JVM would see a shrunken budget",
    )
  }

  @Test
  fun `a launch that would consume the host's reserved permits is refused`() {
    val cap = 64
    val fits = cap - SubprocessIoCapacity.HOST_RESERVED_IO_PERMITS

    // At the boundary it is allowed: the host keeps exactly its reserve.
    val reservation = SubprocessIoCapacity.reserve(subprocessCount = fits, ioParallelism = cap)
    try {
      val failure = assertFailsWith<SubprocessIoCapacityException> {
        SubprocessIoCapacity.reserve(subprocessCount = 1, ioParallelism = cap)
      }
      assertEquals(1, failure.subprocessCount)
      assertEquals(fits, failure.alreadyHeldPermits)
      assertEquals(cap, failure.ioParallelism)
    } finally {
      reservation.release()
    }
  }

  /**
   * The permits are a JVM-wide pool, so the accounting has to be too. A daemon holds one runtime
   * per live MCP session and a multi-target run launches one per plan entry; checking each launch
   * against the whole cap in isolation admits any number of batches that individually fit and
   * together exhaust it — the exact state this guard exists to make impossible.
   */
  @Test
  fun `concurrent runtimes are counted together, not each against the full cap`() {
    val cap = 64
    val half = (cap - SubprocessIoCapacity.HOST_RESERVED_IO_PERMITS) / 2

    val first = SubprocessIoCapacity.reserve(subprocessCount = half, ioParallelism = cap)
    val second = SubprocessIoCapacity.reserve(subprocessCount = half, ioParallelism = cap)
    try {
      assertEquals(half * 2, SubprocessIoCapacity.outstandingPermits())
      // A third batch the same size as the two that were just admitted.
      assertFailsWith<SubprocessIoCapacityException> {
        SubprocessIoCapacity.reserve(subprocessCount = half, ioParallelism = cap)
      }
    } finally {
      first.release()
      second.release()
    }

    // Shutting a runtime down returns its permits, or a long-lived daemon would refuse launches
    // against a budget that only ever shrinks.
    assertEquals(0, SubprocessIoCapacity.outstandingPermits())
    SubprocessIoCapacity.reserve(subprocessCount = half, ioParallelism = cap).release()
  }

  @Test
  fun `releasing a reservation twice does not hand back permits that are still parked`() {
    // Teardown paths overlap: a partial-launch failure unwinds its own reservation, and a caller
    // holding the runtime may still call shutdownAll. Double-counting a release is worse than
    // leaking one — it lets the guard admit a launch that genuinely does not fit.
    val reservation = SubprocessIoCapacity.reserve(subprocessCount = 8, ioParallelism = 64)
    reservation.release()
    reservation.release()

    assertEquals(0, SubprocessIoCapacity.outstandingPermits())
  }

  @Test
  fun `permits still parked by a live subprocess stay counted after a partial release`() {
    // A subprocess that outlives SIGKILL still has a transport parking its permit. Refunding it
    // would let the next launch be admitted against IO capacity that is not there.
    val reservation = SubprocessIoCapacity.reserve(subprocessCount = 8, ioParallelism = 64)
    val survivors = List(3) { FakeProcess(survivesSigkill = true, pid = 5100L + it) }

    reservation.releaseAllBut(survivors)
    assertEquals(3, SubprocessIoCapacity.outstandingPermits())
    assertEquals(3, reservation.outstanding())

    // Asking again for the same survivors releases nothing more — the accounting is a floor, not a
    // second refund.
    reservation.releaseAllBut(survivors)
    assertEquals(3, SubprocessIoCapacity.outstandingPermits())

    // Naming children this reservation is no longer counting cannot re-reserve permits that were
    // already returned.
    reservation.releaseAllBut(survivors + List(2) { FakeProcess(survivesSigkill = true, pid = 5200L + it) })
    assertEquals(3, SubprocessIoCapacity.outstandingPermits())

    // Once the last survivor is gone, the rest comes back.
    reservation.release()
    assertEquals(0, SubprocessIoCapacity.outstandingPermits())
  }

  /**
   * The window between a child dying and its exit callback running belongs to no path in
   * particular, and both paths want to refund the same permit in it. A teardown that runs in that
   * window sees the child gone and hands its permit back; the callback then arrives and hands back
   * one more — and that one is a sibling that is still parking it. The tally's floor cannot catch
   * it, because it is nowhere near zero while the sibling is counted.
   */
  @Test
  fun `a child that dies between teardowns is not refunded twice`() {
    val dying = FakeProcess(survivesSigkill = true, pid = 5301L, manualExitNotification = true)
    val sibling = FakeProcess(survivesSigkill = true, pid = 5302L, manualExitNotification = true)
    val reservation = SubprocessIoCapacity.reserve(subprocessCount = 2, ioParallelism = 64)

    try {
      // First teardown: both children outlived SIGKILL, so both permits stay counted and both
      // children are signed up for a refund when the OS finally reaps them.
      reservation.releaseAllBut(listOf(dying, sibling))
      reservation.refundWhenExited(dying)
      reservation.refundWhenExited(sibling)
      assertEquals(2, SubprocessIoCapacity.outstandingPermits())

      // One child is reaped. Its callback has not run yet — the state a second teardown can catch.
      dying.exit()
      reservation.releaseAllBut(listOf(sibling))
      assertEquals(1, SubprocessIoCapacity.outstandingPermits())

      // Now the callback arrives for a permit that has already come back. Handing one back here
      // would be the sibling's, which is still parked.
      dying.notifyExitListeners()
      assertEquals(1, SubprocessIoCapacity.outstandingPermits())
      assertTrue(sibling.isAlive, "the sibling must still be parking the permit under test")

      // And the sibling's own refund still works: the guard is per child, not a dead man's switch.
      sibling.exit()
      sibling.notifyExitListeners()
      assertEquals(0, SubprocessIoCapacity.outstandingPermits())
    } finally {
      reservation.release()
    }
  }

  /**
   * The message is the deliverable. It replaces a daemon that answers `/ping` while every other
   * route hangs — a symptom that names nothing — so it has to carry every number that went into
   * the decision and the knob that changes it, or the failure is no more actionable than the hang.
   */
  @Test
  fun `the refusal names each count separately and the property to raise`() {
    // Distinct values throughout, so a message that dropped one of them can't be satisfied by
    // another one's digits.
    val held = SubprocessIoCapacity.reserve(subprocessCount = 21, ioParallelism = 100)
    try {
      val failure = assertFailsWith<SubprocessIoCapacityException> {
        SubprocessIoCapacity.reserve(subprocessCount = 77, ioParallelism = 100)
      }
      val message = assertNotNull(failure.message)
      assertTrue("77" in message, "the refusal must name the count being launched: $message")
      assertTrue("21" in message, "the refusal must name what live sessions already hold: $message")
      assertTrue("100" in message, "the refusal must name this JVM's cap: $message")
      assertTrue(
        "${SubprocessIoCapacity.HOST_RESERVED_IO_PERMITS}" in message,
        "the refusal must name the host's reserve: $message",
      )
      // 100 - 16 reserved - 21 held. Naming what WOULD have fit is the difference between
      // "raise the cap" and "raise it to what".
      assertTrue("63" in message, "the refusal must name how many were available: $message")
    } finally {
      held.release()
    }
  }

  @Test
  fun `the effective cap honors the parallelism property`() {
    withIoParallelismProperty("512") {
      assertEquals(512, SubprocessIoCapacity.effectiveIoParallelism())
    }
  }

  /**
   * kotlinx reads this property through `systemProp(name, default, minValue = 1)`, which calls
   * `error()` on anything that is not an int >= 1 — so a JVM carrying a malformed value is already
   * doomed the moment something touches `Dispatchers.IO`. Planning against the 64 default here
   * would describe a cap that JVM is never going to have, and would do it silently.
   */
  @Test
  fun `a malformed parallelism property is rejected rather than treated as unset`() {
    for (bad in listOf("not-a-number", "0", "-1", "12.5")) {
      withIoParallelismProperty(bad) {
        val failure = assertFailsWith<IllegalStateException> {
          SubprocessIoCapacity.effectiveIoParallelism()
        }
        val message = assertNotNull(failure.message)
        assertTrue(bad in message, "the failure must quote the offending value: $message")
        assertTrue(
          "TRAILBLAZE_IO_PARALLELISM" in message,
          "the failure must name the env var an operator actually set, not just the system " +
            "property kotlinx reports: $message",
        )
      }
    }
  }

  /**
   * The values that look like nothing are the ones that get mishandled. `-Dfoo=` on a command line
   * sets the property to the empty string — a SET property with no usable value — and kotlinx does
   * not trim before parsing, so `""`, `" "` and `" 512 "` are all fatal there. Treating any of them
   * as "unset" here would plan against a 64 the JVM is never going to have, because it is going to
   * die the moment anything touches `Dispatchers.IO`.
   *
   * Reachable in practice: a `TRAILBLAZE_IO_PARALLELISM=` in the environment that builds the
   * desktop app or starts a Gradle `run`.
   */
  @Test
  fun `a blank or padded parallelism property is rejected, not treated as unset`() {
    for (blank in listOf("", " ", "\t", " 512 ")) {
      withIoParallelismProperty(blank) {
        val failure = assertFailsWith<IllegalStateException> {
          SubprocessIoCapacity.effectiveIoParallelism()
        }
        val message = assertNotNull(failure.message)
        assertTrue(
          "TRAILBLAZE_IO_PARALLELISM" in message,
          "the failure must name the env var an operator actually set: $message",
        )
      }
    }
  }

  /**
   * A launch of nothing must not consult a cap it will never spend. Otherwise a session with no
   * scripted tools fails at startup over a property it had no use for.
   */
  @Test
  fun `reserving zero does not read the parallelism property`() {
    withIoParallelismProperty("not-a-number") {
      assertEquals(0, SubprocessIoCapacity.reserve(subprocessCount = 0).permits)
    }
  }

  private fun withIoParallelismProperty(value: String, block: () -> Unit) {
    val property = SubprocessIoCapacity.IO_PARALLELISM_PROPERTY
    val original = System.getProperty(property)
    try {
      System.setProperty(property, value)
      block()
    } finally {
      if (original == null) System.clearProperty(property) else System.setProperty(property, original)
    }
  }
}
