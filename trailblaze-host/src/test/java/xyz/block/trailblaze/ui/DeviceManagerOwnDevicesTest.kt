package xyz.block.trailblaze.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.FakeHostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.lease.DeviceLeaseClient
import xyz.block.trailblaze.host.lease.LeasableDeviceStatus
import xyz.block.trailblaze.host.lease.RemoteDevicePools
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a lending daemon offers is its own devices, found without asking the daemons it borrows
 * from: two daemons paired both ways would otherwise each wait on the other's listing.
 */
class DeviceManagerOwnDevicesTest {

  private val tempDir: File = File.createTempFile("trailblaze-own-devices-", "").also {
    it.delete()
    it.mkdirs()
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val ownDevice = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.REVYL_ANDROID,
    instanceId = "own-device",
    description = "This machine's device",
  )

  private val peersDevice = LeasableDeviceStatus(
    id = "android/peer-device",
    driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    description = "The peer's device",
    leased = false,
  )

  private fun deviceManager(peer: DeviceLeaseClient): TrailblazeDeviceManager {
    val settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempDir, "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = emptySet(),
    )
    return TrailblazeDeviceManager(
      logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false),
      settingsRepo = settingsRepo,
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      currentTrailblazeLlmModelProvider = { error("LLM not available in tests") },
      initialAppTargets = emptySet(),
      runYamlLambda = { error("YAML runner not available in tests") },
      installedAppIdsProviderBlocking = { emptySet() },
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
      hostDriverDescriptors = HostDriverDescriptorRegistry(
        setOf(
          FakeHostDriverDescriptor(
            TrailblazeDriverType.REVYL_ANDROID,
            listingVisibility = DeviceListingVisibility.ADDRESSABLE_NOT_LISTED,
            devices = listOf(ownDevice),
          ),
        ),
      ),
      remoteDevicePools = RemoteDevicePools(poolUrls = { listOf(PEER) }, clientFor = { peer }),
    )
  }

  @Test
  fun `own devices are listed while a full scan is still waiting on a paired daemon`() {
    val asked = CountDownLatch(1)
    val answer = CountDownLatch(1)
    // The paired daemon, itself asking this one for its devices: it answers only once this one has.
    val peer = object : DeviceLeaseClient(PEER) {
      override fun devices(): List<LeasableDeviceStatus> {
        asked.countDown()
        answer.await()
        return listOf(peersDevice)
      }
    }
    val manager = deviceManager(peer)

    runBlocking {
      val scan = async(Dispatchers.IO) { manager.loadDevicesSuspend(applyDriverFilter = false) }
      asked.await()

      val own = manager.loadOwnDevicesSuspend()
      val scanStillWaiting = scan.isActive
      answer.countDown()
      scan.await()

      assertEquals(listOf(ownDevice.instanceId), own.map { it.instanceId })
      assertTrue(scanStillWaiting, "own devices must not wait for the scan that is asking the peer")
    }
  }

  @Test
  fun `own devices never include one a paired daemon offers`() {
    val peer = object : DeviceLeaseClient(PEER) {
      override fun devices(): List<LeasableDeviceStatus> = listOf(peersDevice)
    }
    val manager = deviceManager(peer)

    runBlocking {
      val everything = manager.loadDevicesSuspend(applyDriverFilter = true)
      assertTrue(everything.any { it.instanceId.startsWith("peer-device@") }, "the full scan lists the peer's device: $everything")

      assertEquals(listOf(ownDevice.instanceId), manager.loadOwnDevicesSuspend().map { it.instanceId })
    }
  }

  private companion object {
    const val PEER = "http://paired-peer:31900"
  }
}
