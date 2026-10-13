package xyz.block.trailblaze.mcp.newtools

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins [deviceClassifiersFor], which supplies the MCP `TrailExecutor`'s per-classifier device list
 * for lowering a unified trail's recordings. Only the platform is known from the session's
 * associated device, so the result is the platform-level classifier (enough for platform-keyed
 * recordings like `android:`/`ios:`); an unbound session yields an empty list, which makes
 * `decodeTrail`'s guard ask the user to bind a device rather than silently lowering the trail
 * with no recordings (which the deterministic executor would then fail step by step).
 */
class TrailExecutorDeviceClassifiersTest {

  @Test
  fun `no associated device yields empty classifiers`() {
    assertEquals(emptyList(), deviceClassifiersFor(null))
  }

  @Test
  fun `android device yields the android platform classifier`() {
    assertEquals(
      listOf(TrailblazeDeviceClassifier("android")),
      deviceClassifiersFor(TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)),
    )
  }

  @Test
  fun `ios device yields the ios platform classifier`() {
    assertEquals(
      listOf(TrailblazeDeviceClassifier("ios")),
      deviceClassifiersFor(TrailblazeDeviceId("SIM-UUID", TrailblazeDevicePlatform.IOS)),
    )
  }

  @Test
  fun `a cached runtime re-probes only a platform-only classification, and not on every call`() {
    // Platform-only is what a failed probe degrades to, and it hides classifier-gated tools — so a
    // cached runtime built on it must ask again. A specific answer came from a probe that worked.
    val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    val platformOnly = listOf(TrailblazeDeviceClassifier("android"))
    val specific = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))
    val interval = PLATFORM_ONLY_CLASSIFIER_REPROBE_INTERVAL_MS

    assertEquals(true, platformOnlyClassifiersDueForReprobe(platformOnly, device, probedAtMs = 0, nowMs = interval))
    assertEquals(false, platformOnlyClassifiersDueForReprobe(platformOnly, device, probedAtMs = 0, nowMs = interval - 1))
    assertEquals(false, platformOnlyClassifiersDueForReprobe(specific, device, probedAtMs = 0, nowMs = interval * 10))
  }
}
