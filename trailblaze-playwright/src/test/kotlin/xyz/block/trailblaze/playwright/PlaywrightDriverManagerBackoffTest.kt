package xyz.block.trailblaze.playwright

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Pin the backoff schedule for `playwright install chromium` retries. The motivating
 * incident: the original fixed 5s backoff meant 3 attempts finished within ~10 seconds,
 * which is shorter than most CDN/network blips that cause the install to flake on CI.
 * Exponential growth (5s → 15s → 45s) gives transient failures a real chance to clear
 * without changing the success-path latency at all.
 */
class PlaywrightDriverManagerBackoffTest {

  @Test
  fun `first retry waits the initial backoff`() {
    assertEquals(5L, PlaywrightDriverManager.backoffForAttempt(1).seconds)
  }

  @Test
  fun `second retry triples the wait via exponential growth`() {
    assertEquals(15L, PlaywrightDriverManager.backoffForAttempt(2).seconds)
  }

  @Test
  fun `third retry continues exponential growth`() {
    assertEquals(45L, PlaywrightDriverManager.backoffForAttempt(3).seconds)
  }

  @Test
  fun `fourth retry is capped at the ceiling not the unbounded 135 seconds`() {
    // Boundary: 5*3*3*3 = 135s would be the unbounded value, but the cap kicks
    // in at 60s. Pin this transition specifically — it's where off-by-ones in
    // the cap-comparison most often hide.
    assertEquals(60L, PlaywrightDriverManager.backoffForAttempt(4).seconds)
  }

  @Test
  fun `backoff is capped at the configured ceiling`() {
    // Far past any realistic attempt count — proves the cap is real, not just a
    // theoretical upper bound that the formula could exceed.
    val backoff = PlaywrightDriverManager.backoffForAttempt(20).seconds
    assertEquals(60L, backoff)
  }

  @Test
  fun `backoff is monotonic non-decreasing`() {
    var previous = 0L
    for (attempt in 1..10) {
      val current = PlaywrightDriverManager.backoffForAttempt(attempt).seconds
      assertTrue(current >= previous, "backoff regressed at attempt $attempt: $previous -> $current")
      previous = current
    }
  }

  @Test
  fun `backoff at attempt zero is non-negative`() {
    // Defensive: callers pass `attempt` (1-indexed), but if a future caller passes 0
    // by mistake the formula must not underflow / negative-shift.
    assertTrue(PlaywrightDriverManager.backoffForAttempt(0).seconds >= 0)
  }
}
