package xyz.block.trailblaze.host.devices

import org.junit.Test
import kotlin.test.assertEquals

class HostIosDriverFactoryDiscardTest {

  @Test
  fun `discarding a device's driver tells every listener, even after one fails`() {
    val told = mutableListOf<String>()
    HostIosDriverFactory.onDiscard { error("listener failed") }
    HostIosDriverFactory.onDiscard { if (it == "DISCARD-TEST-UDID") told += it }

    HostIosDriverFactory.discard("DISCARD-TEST-UDID")

    assertEquals(listOf("DISCARD-TEST-UDID"), told)
  }
}
