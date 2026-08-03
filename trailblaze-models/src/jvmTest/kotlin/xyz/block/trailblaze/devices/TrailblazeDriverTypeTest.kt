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

  @Test
  fun `IOS_AXE is a host-native iOS driver`() {
    assertTrue(TrailblazeDriverType.IOS_AXE in TrailblazeDriverType.IOS_HOST_NATIVE_DRIVER_TYPES)
  }
}
