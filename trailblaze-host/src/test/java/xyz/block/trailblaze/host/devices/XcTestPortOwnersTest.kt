package xyz.block.trailblaze.host.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XcTestPortOwnersTest {

  private val live = { true }
  private val dead = { false }

  @Test
  fun `a second device on a live device's port is refused, naming both devices and the port`() {
    val owners = XcTestPortOwners()
    owners.claim(port = 7123, deviceId = "sim-a", portIsLive = live)
    owners.connected(port = 7123, deviceId = "sim-a")

    val refusal = assertFailsWith<IllegalStateException> {
      owners.claim(port = 7123, deviceId = "sim-b", portIsLive = live)
    }

    val message = refusal.message.orEmpty()
    assertTrue(
      "sim-a" in message && "sim-b" in message && "7123" in message,
      "the refusal should name both devices and the port; got: $message",
    )
  }

  @Test
  fun `a device still building on the port is never taken over, even though its port is dead`() {
    val owners = XcTestPortOwners()
    owners.claim(port = 7123, deviceId = "sim-a", portIsLive = live)

    assertFailsWith<IllegalStateException> {
      owners.claim(port = 7123, deviceId = "sim-b", portIsLive = dead)
    }
  }

  @Test
  fun `a connected device whose port died is taken over and named, so its driver can be discarded`() {
    val owners = XcTestPortOwners()
    owners.claim(port = 7123, deviceId = "sim-a", portIsLive = live)
    owners.connected(port = 7123, deviceId = "sim-a")

    assertEquals("sim-a", owners.claim(port = 7123, deviceId = "sim-b", portIsLive = dead))
    assertFailsWith<IllegalStateException>("the taken-over device must not reclaim a port being rebuilt") {
      owners.claim(port = 7123, deviceId = "sim-a", portIsLive = dead)
    }
  }

  @Test
  fun `the owning device may reconnect on its own port`() {
    val owners = XcTestPortOwners()
    owners.claim(port = 7123, deviceId = "sim-a", portIsLive = live)
    owners.connected(port = 7123, deviceId = "sim-a")

    assertNull(owners.claim(port = 7123, deviceId = "sim-a", portIsLive = live))
  }

  @Test
  fun `a released port can be taken by another device`() {
    val owners = XcTestPortOwners()
    owners.claim(port = 7123, deviceId = "sim-a", portIsLive = live)
    owners.release(port = 7123, deviceId = "sim-a")

    assertNull(owners.claim(port = 7123, deviceId = "sim-b", portIsLive = live))
  }

  @Test
  fun `releasing a port another device took over leaves the new owner's claim`() {
    val owners = XcTestPortOwners()
    owners.claim(port = 7123, deviceId = "sim-a", portIsLive = live)
    owners.connected(port = 7123, deviceId = "sim-a")
    owners.claim(port = 7123, deviceId = "sim-b", portIsLive = dead)
    owners.connected(port = 7123, deviceId = "sim-b")

    owners.release(port = 7123, deviceId = "sim-a")

    assertFailsWith<IllegalStateException>("sim-b must still own the port") {
      owners.claim(port = 7123, deviceId = "sim-c", portIsLive = live)
    }
  }

  @Test
  fun `releasing one port leaves the device's claim on another`() {
    val owners = XcTestPortOwners()
    owners.claim(port = 7123, deviceId = "sim-a", portIsLive = live)
    owners.claim(port = 7124, deviceId = "sim-a", portIsLive = live)

    owners.release(port = 7123, deviceId = "sim-a")

    assertFailsWith<IllegalStateException>("sim-a must still own port 7124") {
      owners.claim(port = 7124, deviceId = "sim-b", portIsLive = live)
    }
  }
}
