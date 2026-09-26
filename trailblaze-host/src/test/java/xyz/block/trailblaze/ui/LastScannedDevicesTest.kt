package xyz.block.trailblaze.ui

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.TrailblazeDriverType.IOS_AXE
import xyz.block.trailblaze.devices.TrailblazeDriverType.IOS_HOST
import xyz.block.trailblaze.host.minimalDeviceManager
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LastScannedDevicesTest {

  private val tempDir = Files.createTempDirectory("last-scanned-devices").toFile()
  private val simulator = TrailblazeDeviceId("SIM-1", TrailblazeDevicePlatform.IOS)

  @AfterTest
  fun cleanUp() {
    tempDir.deleteRecursively()
  }

  private fun variant(type: TrailblazeDriverType) =
    TrailblazeConnectedDeviceSummary(type, simulator.instanceId, description = "iPhone")

  private fun manager(selected: TrailblazeDriverType) = minimalDeviceManager(
    tempDir,
    initialConfig = SavedTrailblazeAppConfig(
      selectedTrailblazeDriverTypes = mapOf(TrailblazeDevicePlatform.IOS to selected),
    ),
  )

  private fun TrailblazeDeviceManager.dispatchDriver() =
    getDeviceState(simulator)?.device?.trailblazeDriverType

  /**
   * Dispatch routes on the published device state, not on the remembered scan. Answering from the
   * scan without republishing left a switched driver in the tool list and the old one in dispatch.
   */
  @Test
  fun `a changed driver setting reaches the device state dispatch reads`() {
    val manager = manager(selected = IOS_HOST)
    manager.rememberScanForTest(listOf(variant(IOS_HOST), variant(IOS_AXE)))
    manager.lastScannedDevices()
    assertEquals(IOS_HOST, manager.dispatchDriver())

    manager.settingsRepo.updateAppConfig {
      it.copy(selectedTrailblazeDriverTypes = mapOf(TrailblazeDevicePlatform.IOS to IOS_AXE))
    }
    val devices = manager.lastScannedDevices()

    assertEquals(IOS_AXE, manager.dispatchDriver())
    assertEquals(setOf(IOS_HOST, IOS_AXE), devices.map { it.trailblazeDriverType }.toSet())
  }

}
