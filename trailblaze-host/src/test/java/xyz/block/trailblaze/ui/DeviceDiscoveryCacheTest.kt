package xyz.block.trailblaze.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavior of the short-TTL device-discovery cache: within the TTL the several sequential passes
 * one CLI command fans out into share a single real discovery; past the TTL, and on an explicit
 * topology-change invalidation, discovery re-runs. Driven with an injected clock so the freshness
 * policy is verified without a real device or wall-clock sleeps — we assert the observable outcome
 * (how many real discoveries happen), never private call counts.
 */
class DeviceDiscoveryCacheTest {

  /** Models the [TrailblazeDeviceManager.loadDevicesSuspend] decision: run discovery only on a miss. */
  private class Harness(ttlMs: Long, private val clock: () -> Long) {
    val cache = DeviceDiscoveryCache(ttlMs = ttlMs, nowMs = clock)
    var discoveries = 0
      private set

    fun load(forceRefresh: Boolean = false) {
      if (!forceRefresh && cache.isFresh()) return
      discoveries++
      cache.markRefreshed()
    }
  }

  @Test
  fun `one command's sequential passes collapse to a single discovery`() {
    var now = 0L
    val h = Harness(ttlMs = 1_500L) { now }

    // device connect / snapshot fan out into ~4-6 back-to-back passes at effectively one instant.
    repeat(6) { h.load() }

    assertEquals(1, h.discoveries, "sequential passes within the TTL must reuse one discovery")
  }

  @Test
  fun `a later command still within the ttl reuses, and past it re-runs`() {
    var now = 0L
    val h = Harness(ttlMs = 1_500L) { now }

    h.load()
    assertEquals(1, h.discoveries)

    now = 1_499L
    h.load()
    assertEquals(1, h.discoveries, "still fresh just before the TTL edge")

    now = 1_500L
    h.load()
    assertEquals(2, h.discoveries, "at the TTL edge the result is stale — re-run")

    now = 5_000L
    repeat(3) { h.load() }
    assertEquals(3, h.discoveries, "a fresh window again collapses to one discovery")
  }

  @Test
  fun `forceRefresh always re-runs even when fresh`() {
    var now = 0L
    val h = Harness(ttlMs = 10_000L) { now }

    h.load()
    assertEquals(1, h.discoveries)
    h.load(forceRefresh = true)
    assertEquals(2, h.discoveries, "explicit refresh (UI button / topology change) bypasses the cache")
  }

  @Test
  fun `invalidate makes the next pass re-run within the ttl`() {
    var now = 100L
    val cache = DeviceDiscoveryCache(ttlMs = 1_000L, nowMs = { now })

    cache.markRefreshed()
    assertTrue(cache.isFresh())

    cache.invalidate()
    assertFalse(cache.isFresh(), "a topology-change invalidation drops freshness immediately")
  }

  @Test
  fun `ttl of zero disables the cache entirely`() {
    val cache = DeviceDiscoveryCache(ttlMs = 0L, nowMs = { 0L })
    cache.markRefreshed()
    assertFalse(cache.isFresh(), "kill switch: every pass re-enumerates")
  }

  @Test
  fun `freshness is false before any discovery has run`() {
    val cache = DeviceDiscoveryCache(ttlMs = 1_000L, nowMs = { 0L })
    assertFalse(cache.isFresh())
  }

  @Test
  fun `a backwards clock adjustment is treated as stale, not fresh`() {
    var now = 10_000L
    val cache = DeviceDiscoveryCache(ttlMs = 1_000L, nowMs = { now })

    cache.markRefreshed()
    assertTrue(cache.isFresh())

    // NTP / manual adjustment moves the wall clock backwards. A negative elapsed time must not
    // read as fresh — otherwise the cache would stay fresh past the TTL and mask a topology change.
    now = 5_000L
    assertFalse(cache.isFresh(), "a clock that moved backwards must invalidate freshness, not extend it")
  }
}
