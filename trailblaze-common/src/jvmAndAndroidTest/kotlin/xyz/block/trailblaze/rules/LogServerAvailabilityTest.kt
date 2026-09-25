package xyz.block.trailblaze.rules

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LogServerAvailabilityTest {

  @Test
  fun `a failed probe is trusted only until the retry window has passed`() {
    var now = 0L
    var probes = 0
    var serverUp = false
    val availability = LogServerAvailability(
      probe = { probes++; serverUp },
      initialRetryAfterMs = 100,
      nowMs = { now },
    )

    assertFalse(availability.isAvailable())
    assertEquals(1, probes)

    now = 50
    assertFalse(availability.isAvailable())
    assertEquals(1, probes, "inside the window the miss is trusted, not re-asked")

    serverUp = true
    now = 100
    assertTrue(availability.isAvailable(), "at the window's end the server is asked again")
    assertEquals(2, probes)
  }

  @Test
  fun `a reached server is never probed again`() {
    var probes = 0
    val availability = LogServerAvailability(probe = { probes++; true }, nowMs = { 0L })

    repeat(5) { assertTrue(availability.isAvailable()) }

    assertEquals(1, probes)
  }

  @Test
  fun `repeated misses back off, doubling up to the cap`() {
    var now = 0L
    val probeTimes = mutableListOf<Long>()
    val availability = LogServerAvailability(
      probe = { probeTimes += now; false },
      initialRetryAfterMs = 100,
      maxRetryAfterMs = 400,
      nowMs = { now },
    )

    // Ask at every tick; only the ticks a window expires on should reach the probe.
    while (now <= 1_600) {
      availability.isAvailable()
      now += 50
    }

    assertEquals(listOf(0L, 100L, 300L, 700L, 1_100L, 1_500L), probeTimes)
  }

  @Test
  fun `the backoff clock measures elapsed time, not the device's wall clock`() {
    // A device syncs its clock over the network shortly after boot, which is when a runner is
    // waiting on its log channel. On wall time a backward correction makes the elapsed backoff
    // negative, so every reprobe is suppressed until wall time climbs back — the indefinite
    // disk-only run this class exists to prevent. The wall clock cannot be moved from inside a
    // test, so assert what separates the two sources: this one counts from when the class loaded,
    // so it is small, where epoch milliseconds are ~1.7e12.
    val elapsed = LogServerAvailability.MONOTONIC_MS()

    assertTrue(
      elapsed < 365L * 24 * 60 * 60 * 1_000,
      "the backoff clock read $elapsed ms, which is wall-clock scale, not elapsed-time scale",
    )

    Thread.sleep(5)
    assertTrue(
      LogServerAvailability.MONOTONIC_MS() > elapsed,
      "and it must still advance in milliseconds",
    )
  }

  @Test
  fun `the probe's outcome and duration are reported`() {
    val reported = mutableListOf<Pair<Boolean, Long>>()
    var now = 0L
    val availability = LogServerAvailability(
      probe = { now += 7; true },
      nowMs = { now },
      onProbe = { available, tookMs -> reported += available to tookMs },
    )

    availability.isAvailable()

    assertEquals(listOf(true to 7L), reported)
  }
}
