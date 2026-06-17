package xyz.block.trailblaze.ui

import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the device-discovery resilience fix: a connected (`device`-state) Android serial must
 * appear in `device list` even when resolving its human-readable name is slow, blank, or fails.
 *
 * Before this, the per-property name probe (`getprop avd_name`) shared the same 10s budget as the
 * whole Android discovery, so a single wedged probe timed the discovery future out and returned an
 * empty list — making a perfectly healthy emulator silently "disappear". These tests pin the two
 * invariants that prevent that: (1) naming is best-effort and never removes a device, and (2) the
 * per-probe timeout is strictly smaller than the overall discovery budget.
 */
class DeviceNameResolutionTest {

  @Test
  fun resolvedNamesAreUsedWhenAvailable() {
    val result = TrailblazeDeviceManager.buildAndroidDeviceList(
      listOf("emulator-5554", "ABC123"),
    ) { serial ->
      when (serial) {
        "emulator-5554" -> "Pixel_6_API_33"
        "ABC123" -> "Pixel 7 Pro"
        else -> null
      }
    }
    assertEquals(
      listOf("emulator-5554" to "Pixel_6_API_33", "ABC123" to "Pixel 7 Pro"),
      result,
    )
  }

  @Test
  fun deviceSurvivesWhenNameResolutionReturnsNull() {
    // The exact failure we hit: the cosmetic getprop timed out (resolver returns null). The device
    // must still appear, falling back to its serial as the display name.
    val result = TrailblazeDeviceManager.buildAndroidDeviceList(listOf("emulator-5554")) { null }
    assertEquals(listOf("emulator-5554" to "emulator-5554"), result)
  }

  @Test
  fun deviceSurvivesWhenNameResolutionReturnsBlank() {
    val result = TrailblazeDeviceManager.buildAndroidDeviceList(listOf("emulator-5554")) { "   " }
    assertEquals(listOf("emulator-5554" to "emulator-5554"), result)
  }

  @Test
  fun deviceSurvivesWhenNameResolutionThrows() {
    // A wedged transport can surface as a thrown exception too; presence must not depend on naming.
    val result = TrailblazeDeviceManager.buildAndroidDeviceList(listOf("emulator-5554")) {
      throw RuntimeException("getprop wedged")
    }
    assertEquals(listOf("emulator-5554" to "emulator-5554"), result)
  }

  @Test
  fun everyDeviceIsPreservedEvenWhenSomeNamesFail() {
    // One healthy device, one whose name probe fails: both must remain in the list.
    val result = TrailblazeDeviceManager.buildAndroidDeviceList(
      listOf("emulator-5554", "emulator-5556"),
    ) { serial -> if (serial == "emulator-5554") "Healthy_AVD" else null }
    assertEquals(
      listOf("emulator-5554" to "Healthy_AVD", "emulator-5556" to "emulator-5556"),
      result,
    )
  }

  @Test
  fun emptyInputProducesEmptyOutput() {
    assertEquals(emptyList(), TrailblazeDeviceManager.buildAndroidDeviceList(emptyList()) { "x" })
  }

  @Test
  fun perProbeTimeoutIsStrictlyUnderTheOverallDiscoveryBudget() {
    // Invariant for Fix #2: one cosmetic probe can never consume the whole discovery budget, so it
    // cannot starve discovery of every device. If someone raises the probe timeout to meet/exceed
    // the discovery budget, this fails loudly.
    val overallBudgetMs = TrailblazeDeviceManager.DEVICE_DISCOVERY_TIMEOUT_SECONDS * 1_000L
    assertTrue(
      TrailblazeDeviceManager.DEVICE_NAME_RESOLUTION_TIMEOUT_MS < overallBudgetMs,
      "Per-probe name timeout (${TrailblazeDeviceManager.DEVICE_NAME_RESOLUTION_TIMEOUT_MS}ms) must be " +
        "strictly less than the overall discovery budget (${overallBudgetMs}ms)",
    )
  }

  // ── awaitNamesUnderSharedDeadline — the multi-device shared-deadline guard ──

  @Test
  fun resolvedNamesAreUsedWhenFuturesCompleteWithinDeadline() {
    // Clock never advances → the full budget remains → completed futures resolve to their names.
    val result = TrailblazeDeviceManager.awaitNamesUnderSharedDeadline(
      serials = listOf("emulator-5554", "emulator-5556"),
      budgetMs = 6_000L,
      nowNanos = { 0L },
    ) { serial -> CompletableFuture.completedFuture("avd-$serial") }
    assertEquals(
      listOf("emulator-5554" to "avd-emulator-5554", "emulator-5556" to "avd-emulator-5556"),
      result,
    )
  }

  @Test
  fun sharedDeadlineGatesEveryAwaitOnceExhausted() {
    // Regression guard for the multi-device P1: a SINGLE shared deadline must gate EVERY await. These
    // futures would resolve instantly if awaited, but the clock jumps past the shared deadline right
    // after it's computed, so every device must fall back to its serial. If someone reverts to a
    // fresh per-device budget, the completed futures would resolve and this test fails — which is
    // exactly the "several slow devices accumulate past the discovery budget" regression.
    var first = true
    val clock = {
      if (first) {
        first = false
        0L
      } else {
        Long.MAX_VALUE / 2
      }
    }
    val result = TrailblazeDeviceManager.awaitNamesUnderSharedDeadline(
      serials = listOf("emulator-5554", "emulator-5556"),
      budgetMs = 6_000L,
      nowNanos = clock,
    ) { serial -> CompletableFuture.completedFuture("avd-$serial") }
    assertEquals(
      listOf("emulator-5554" to "emulator-5554", "emulator-5556" to "emulator-5556"),
      result,
    )
  }

  @Test
  fun withinDeadlineASlowDeviceFallsBackWhileOthersResolve() {
    // Within the shared deadline, a failed/wedged probe degrades that one device to its serial; the
    // others still resolve, and no device is dropped.
    val result = TrailblazeDeviceManager.awaitNamesUnderSharedDeadline(
      serials = listOf("emulator-5554", "emulator-5556"),
      budgetMs = 6_000L,
      nowNanos = { 0L },
    ) { serial ->
      if (serial == "emulator-5554") {
        CompletableFuture.completedFuture("Healthy_AVD")
      } else {
        CompletableFuture.failedFuture<String>(RuntimeException("getprop wedged"))
      }
    }
    assertEquals(
      listOf("emulator-5554" to "Healthy_AVD", "emulator-5556" to "emulator-5556"),
      result,
    )
  }
}
