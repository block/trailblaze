package xyz.block.trailblaze.host.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import maestro.device.Device
import maestro.device.DeviceSpec
import maestro.device.Platform

class ConnectedDeviceLookupTest {

  private val simulator = device("SIM-1", Device.DeviceType.SIMULATOR)
  private val iphone = device("IPHONE-1", Device.DeviceType.REAL)

  private var now = 0L
  private var listings = 0
  private var bootedProbes = 0
  private var listed = listOf(simulator, iphone)
  private var booted = setOf("SIM-1")

  private val lookup = ConnectedDeviceLookup(
    ttlMs = 30_000L,
    listAll = { listings++; listed },
    bootedSimulatorIds = { bootedProbes++; booted },
    nowMs = { now },
  )

  private fun find(id: String) = lookup.find(id) { it.platform == Platform.IOS }

  @Test
  fun `a second call inside the ttl reuses the listing`() {
    find("SIM-1")
    now = 29_000L

    assertEquals(simulator, find("SIM-1"))
    assertEquals(1, listings)
    assertEquals(0, bootedProbes)
  }

  @Test
  fun `past the ttl a known simulator that is still booted is found without listing every device`() {
    find("SIM-1")
    now = 120_000L

    assertEquals(simulator, find("SIM-1"))
    assertEquals(1, listings)
    assertEquals(1, bootedProbes)
  }

  @Test
  fun `past the ttl a known simulator that shut down is looked up in a fresh listing`() {
    find("SIM-1")
    now = 120_000L
    booted = emptySet()
    listed = listOf(iphone)

    assertNull(find("SIM-1"))
    assertEquals(2, listings)
  }

  @Test
  fun `a physical device is always confirmed by a full listing past the ttl`() {
    find("IPHONE-1")
    now = 120_000L

    assertEquals(iphone, find("IPHONE-1"))
    assertEquals(2, listings)
    assertEquals(0, bootedProbes)
  }

  @Test
  fun `a failing booted probe falls back to the full listing`() {
    val failing = ConnectedDeviceLookup(
      ttlMs = 30_000L,
      listAll = { listings++; listed },
      bootedSimulatorIds = { error("simctl unavailable") },
      nowMs = { now },
    )
    failing.find("SIM-1") { true }
    now = 120_000L

    assertEquals(simulator, failing.find("SIM-1") { true })
    assertEquals(2, listings)
  }

  @Test
  fun `a known simulator the caller's filter rejects is not returned`() {
    find("SIM-1")
    now = 120_000L

    assertNull(lookup.find("SIM-1") { it.platform == Platform.ANDROID })
  }

  private fun device(id: String, type: Device.DeviceType) = Device.Connected(
    instanceId = id,
    deviceSpec = DeviceSpec.Ios(model = "iPhone 17 Pro", os = "iOS-26-0"),
    description = id,
    platform = Platform.IOS,
    deviceType = type,
  )
}
