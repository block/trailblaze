package xyz.block.trailblaze.playwright

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.test.Test

/**
 * Pins the throughput floor the driver-bundle download enforces.
 *
 * The stall window is only evaluated when a read returns, so its real duration is whatever the
 * sender's pacing makes it — not the nominal 60s. The floor has to hold across that variation,
 * because the failure it exists to catch is a connection that trickles just enough to keep the
 * socket read timeout from ever firing.
 */
class PlaywrightDriverStallThresholdTest {

  private val oneMb = 1L * 1024 * 1024

  @Test
  fun `a nominal window requires the nominal minimum`() {
    assertThat(PlaywrightDriverManager.requiredBytesForWindow(60_000))
      .isEqualTo(oneMb)
  }

  @Test
  fun `a window that ran twice as long requires twice as much`() {
    // The concrete leak: a sender delivers a 1 MB burst, then goes quiet until just before the
    // 60s socket read timeout, so the window is evaluated at ~120s. Against a flat 1 MB
    // threshold that clears — at half the intended rate — and repeats indefinitely.
    assertThat(PlaywrightDriverManager.requiredBytesForWindow(120_000))
      .isEqualTo(2 * oneMb)
  }

  @Test
  fun `the enforced rate stays constant as the window stretches`() {
    // Whatever the window length, the bytes required must not sag below the proportional ideal.
    // Asserted with a one-byte tolerance rather than exact equality: the requirement is computed
    // with integer division, so a window length that isn't a multiple of the nominal one lands
    // fractionally under the ideal. That is truncation, not a rate sag — an exact-equality
    // assertion would read the rounding as a regression.
    //
    // 70_000 is in the list precisely because it is NOT a multiple of 60_000. Every other value
    // here divides evenly and truncates to nothing, so a divisibility-only set would pass under
    // exact equality while saying nothing about the general case.
    listOf(60_000L, 70_000L, 90_000L, 120_000L, 300_000L).forEach { elapsed ->
      val ideal = oneMb.toDouble() * elapsed / 60_000
      val required = PlaywrightDriverManager.requiredBytesForWindow(elapsed).toDouble()
      assertThat(required, "required bytes for a ${elapsed}ms window")
        .isBetween(ideal - 1.0, ideal)
    }
  }

  @Test
  fun `a burst that would have passed a flat threshold now fails`() {
    // 1 MB delivered across a 120s window: passes a flat 1 MB check, fails the scaled one.
    val windowBytes = oneMb
    assertThat(windowBytes).isLessThan(PlaywrightDriverManager.requiredBytesForWindow(120_000))
  }

  @Test
  fun `a genuinely slow but healthy link still passes`() {
    // The floor is 1 MiB/60s ≈ 17.1 KB/s, so a link running comfortably above it must survive a
    // stretched window — the check is meant to kill near-dead connections, not slow ones.
    val elapsed = 90_000L
    val delivered = 25L * 1024 * (elapsed / 1000)
    assertThat(delivered).isGreaterThan(PlaywrightDriverManager.requiredBytesForWindow(elapsed))
  }
}
