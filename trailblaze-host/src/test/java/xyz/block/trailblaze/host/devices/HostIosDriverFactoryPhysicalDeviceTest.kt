package xyz.block.trailblaze.host.devices

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import maestro.device.Device

class HostIosDriverFactoryPhysicalDeviceTest {

  @Test
  fun `a physical iPhone is refused with a message that says only simulators are supported`() {
    val error = assertFailsWith<UnsupportedOperationException> {
      HostIosDriverFactory.createIOS(
        deviceId = "00008110-000A1234",
        openDriver = true,
        driverHostPort = 1,
        reinstallDriver = false,
        platformConfiguration = null,
        deviceType = Device.DeviceType.REAL,
      )
    }

    assertContains(error.message.orEmpty(), "Only iOS simulators are supported")
    assertContains(error.message.orEmpty(), "00008110-000A1234")
  }
}
