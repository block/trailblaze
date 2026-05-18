package xyz.block.trailblaze.host.ios

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins the device-id append the host-side wrapper adds on top of the shared
 * [TrailblazeHostAppTarget.requireInstalledAppIdForDevice] resolver. The shared resolver throws a
 * diagnostic with target id + declared ids + installed set, but no device id (it has no
 * [TrailblazeDeviceId] dependency by design — it lives in `commonMain`). Multi-device runs need
 * to know *which* device's inventory was wrong, so [MobileDeviceUtils.findInstalledAppIdForTarget]
 * wraps the helper call and appends `(device='<instanceId>')`. Without this test pinning the
 * append, a future simplification of the wrapper could quietly drop the device label and reduce
 * a parallel-shard failure back to "which emulator was it on, again?" guess-work.
 */
class MobileDeviceUtilsFindInstalledAppIdForTargetTest {

  // WEB-platform target: [MobileDeviceUtils.getInstalledAppIds] returns an empty set for WEB
  // devices without shelling out, which lets this test exercise the no-match throw path without
  // any device dependency. The actual platform doesn't matter for the diagnostic — what matters
  // is that the wrapper's catch fires and appends the device id.
  private object WebOnlyTarget :
    TrailblazeHostAppTarget(id = "web-only", displayName = "Web-Only") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      when (platform) {
        TrailblazeDevicePlatform.WEB -> listOf("https://example.com")
        else -> null
      }

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  @Test
  fun `findInstalledAppIdForTarget appends device id to error message and preserves cause`() {
    val deviceId = TrailblazeDeviceId(
      instanceId = "test-web-device-42",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
    )
    val ex = assertFailsWith<IllegalStateException> {
      MobileDeviceUtils.findInstalledAppIdForTarget(WebOnlyTarget, deviceId)
    }
    val msg = ex.message ?: ""
    // The shared helper's diagnostic must still be present — wrapping it should *augment*,
    // not replace.
    assertTrue("missing 'Could not find' diagnostic in: $msg") { "Could not find" in msg }
    assertTrue("missing target display name in: $msg") { "Web-Only" in msg }
    // The wrapper's device-id append — the actual point of this test. Format is
    // `(device='<instanceId>')` per [MobileDeviceUtils.findInstalledAppIdForTarget].
    assertTrue("missing device label in: $msg") { "(device='test-web-device-42')" in msg }
    // Cause-chain preservation: the original ISE the helper threw must be reachable via
    // [Throwable.cause] so stack traces still surface the underlying diagnostic.
    val cause = ex.cause
    assertTrue("expected IllegalStateException cause, got ${cause?.let { it::class.simpleName }}") {
      cause is IllegalStateException
    }
  }
}
