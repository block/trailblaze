package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import kotlin.math.abs
import kotlinx.datetime.Clock
import org.junit.Test

/**
 * Unit coverage for [PollingUtils].
 *
 * Every deadline test drives the loop through its injected clock and sleep, so the assertions are
 * about the budget arithmetic rather than about real elapsed time — a real-time assertion under a
 * minute flakes on a loaded CI agent, and the interesting case (one attempt outlasting the entire
 * budget) would otherwise take ten minutes to reproduce.
 */
class PollingUtilsTest {

  /** A clock the condition itself advances, standing in for time spent inside an attempt. */
  private class FakeClock(private var nowMs: Long = 1_000L) {
    val sleeps = mutableListOf<Long>()

    fun now(): Long = nowMs

    fun advance(byMs: Long) {
      nowMs += byMs
    }

    fun sleep(durationMs: Long) {
      sleeps += durationMs
      advance(durationMs)
    }
  }

  private fun FakeClock.poll(
    maxWaitMs: Long,
    intervalMs: Long = 200,
    condition: () -> Boolean,
  ): Boolean = PollingUtils.tryUntilSuccessOrTimeout(
    maxWaitMs = maxWaitMs,
    intervalMs = intervalMs,
    conditionDescription = "test condition",
    nowMs = { now() },
    sleepMs = { sleep(it) },
    condition = condition,
  )

  /**
   * The defect this file exists for: `forceStopApp` asks for 30s and each of its two shell
   * commands is only bounded at 300s, so a single attempt can outlast the whole budget. The
   * attempt in flight still finishes — nothing here truncates a shell read — but the loop must not
   * start another one or sleep out another interval on top of it.
   */
  @Test
  fun `an attempt that outlasts the whole budget is not followed by another`() {
    val clock = FakeClock()
    var attempts = 0

    val met = clock.poll(maxWaitMs = 30_000) {
      attempts++
      clock.advance(600_000)
      false
    }

    assertThat(met).isFalse()
    assertThat(attempts).isEqualTo(1)
    assertThat(clock.sleeps).isEmpty()
  }

  @Test
  fun `polling stops once the accumulated budget is spent`() {
    val clock = FakeClock()
    var attempts = 0

    // 10s per attempt plus a 200ms sleep: attempts start at 0ms, 10_200ms and 20_400ms, and the
    // fourth would start past the 30s deadline.
    val met = clock.poll(maxWaitMs = 30_000) {
      attempts++
      clock.advance(10_000)
      false
    }

    assertThat(met).isFalse()
    assertThat(attempts).isEqualTo(3)
    assertThat(clock.sleeps).isEqualTo(listOf(200L, 200L))
  }

  @Test
  fun `a condition met on the first attempt returns without sleeping`() {
    val clock = FakeClock()
    var attempts = 0

    val met = clock.poll(maxWaitMs = 30_000) {
      attempts++
      true
    }

    assertThat(met).isTrue()
    assertThat(attempts).isEqualTo(1)
    assertThat(clock.sleeps).isEmpty()
  }

  /**
   * Callers depend on getting one attempt for any positive budget — `waitForImeDismissed` and
   * `waitUntilAppInForeground` both pass a caller-supplied timeout that can be far smaller than
   * the check it wraps.
   */
  @Test
  fun `the first attempt runs even when it cannot fit inside the budget`() {
    val clock = FakeClock()
    var attempts = 0

    val met = clock.poll(maxWaitMs = 1) {
      attempts++
      clock.advance(10_000)
      true
    }

    assertThat(met).isTrue()
    assertThat(attempts).isEqualTo(1)
  }

  /**
   * A budget smaller than one poll interval must not be slept out: the sleep would end at or past
   * the deadline, so no attempt could follow it and the call would just have run long for nothing.
   */
  @Test
  fun `no sleep is issued when the interval outlasts what is left of the budget`() {
    val clock = FakeClock()
    var attempts = 0

    val met = clock.poll(maxWaitMs = 100, intervalMs = 200) {
      attempts++
      false
    }

    assertThat(met).isFalse()
    assertThat(attempts).isEqualTo(1)
    assertThat(clock.sleeps).isEmpty()
  }

  /** The other side of the same boundary: a zero budget buys no attempt at all. */
  @Test
  fun `a zero budget attempts nothing`() {
    val clock = FakeClock()
    var attempts = 0

    val met = clock.poll(maxWaitMs = 0) {
      attempts++
      true
    }

    assertThat(met).isFalse()
    assertThat(attempts).isEqualTo(0)
  }

  @Test
  fun `a condition that throws counts as not yet met and is retried`() {
    val clock = FakeClock()
    var attempts = 0

    val met = clock.poll(maxWaitMs = 30_000) {
      attempts++
      if (attempts == 1) error("device not ready") else true
    }

    assertThat(met).isTrue()
    assertThat(attempts).isEqualTo(2)
    assertThat(clock.sleeps).isEqualTo(listOf(200L))
  }

  /**
   * Pins the choice of clock, not just the arithmetic: a wall clock that a device corrects
   * mid-wait (a test device syncing time after boot) makes the loop overshoot its budget or give
   * up early. Reverting the helper to `Clock.System.now()` brings these two readings within
   * milliseconds of each other and turns this test red; nothing else in this file would notice,
   * since every other test supplies its own clock.
   *
   * The origin of [System.nanoTime] is unspecified, so this leans on both supported runtimes
   * deriving it from `CLOCK_MONOTONIC` (uptime, not epoch). An implementation free to pick an
   * epoch-aligned origin would make this a false failure — the year of slack keeps that to a
   * machine that booted in 1970.
   */
  @Test
  fun `the polling clock is monotonic, not the wall clock`() {
    val epochMs = Clock.System.now().toEpochMilliseconds()
    val oneYearMs = 365L * 24 * 60 * 60 * 1_000

    assertThat(abs(PollingUtils.monotonicNowMs() - epochMs)).isGreaterThan(oneYearMs)
  }
}
