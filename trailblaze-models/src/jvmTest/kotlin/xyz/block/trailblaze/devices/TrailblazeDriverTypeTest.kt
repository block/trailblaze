package xyz.block.trailblaze.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrailblazeDriverTypeTest {

  /**
   * Every gate keyed off [TrailblazeDriverType.IOS_HOST_NATIVE_DRIVER_TYPES] (host-runner
   * guard, Maestro-driver registration skip, manual scroll loop) assumes its members are
   * host-resident iOS drivers. A member violating either property would silently route
   * through the wrong plumbing.
   */
  @Test
  fun `host-native iOS drivers are iOS platform and host-resident`() {
    TrailblazeDriverType.IOS_HOST_NATIVE_DRIVER_TYPES.forEach { driverType ->
      assertEquals(TrailblazeDevicePlatform.IOS, driverType.platform, "$driverType must target iOS")
      assertTrue(driverType.requiresHost, "$driverType must be host-resident")
    }
  }

  /**
   * Exact-membership tripwire. `when` branches over [TrailblazeDriverType] spell this set's
   * members out individually so the `when` stays compile-time exhaustive (e.g. the screen-state
   * capture arm in `TrailblazeDeviceManager.getCurrentScreenState`). A brand-new enum entry
   * breaks those `when`s at compile time, but adding an EXISTING enum entry to this set would
   * silently route it through whichever arm already handles it. If this test fails because you
   * added a member, update every branch that spells out the set's members, then this expectation.
   */
  @Test
  fun `IOS_HOST_NATIVE_DRIVER_TYPES membership is pinned`() {
    assertEquals(
      setOf(TrailblazeDriverType.IOS_AXE),
      TrailblazeDriverType.IOS_HOST_NATIVE_DRIVER_TYPES,
    )
  }
}
