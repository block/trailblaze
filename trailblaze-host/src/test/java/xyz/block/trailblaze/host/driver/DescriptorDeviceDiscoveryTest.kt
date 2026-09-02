package xyz.block.trailblaze.host.driver

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Descriptors are the extension point, so [DescriptorDeviceDiscovery] is where a misbehaving
 * plug-in gets contained: one that throws or hangs costs the user that driver's devices, never the
 * whole device list.
 */
class DescriptorDeviceDiscoveryTest {

  private fun device(instanceId: String) = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.REVYL_ANDROID,
    instanceId = instanceId,
    description = instanceId,
  )

  /** A descriptor whose discovery is whatever [discover] does; run/screen are unreachable. */
  private class ScriptedDescriptor(
    private val discover: suspend (HostDeviceInventory) -> List<TrailblazeConnectedDeviceSummary>,
  ) : HostDriverDescriptor {
    override val driverTypes = setOf(TrailblazeDriverType.REVYL_ANDROID)
    override val listingVisibility = DeviceListingVisibility.LISTED
    override suspend fun discoverDevices(inventory: HostDeviceInventory) = discover(inventory)
    override suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams): HostYamlRunResult =
      error("not a run test")
    override suspend fun screenState(driverType: TrailblazeDriverType, deviceId: TrailblazeDeviceId, deps: HostScreenStateDeps): ScreenState? =
      error("not a screen test")
  }

  @Test
  fun `every descriptor's devices land in one list`() {
    val discovered = runBlocking {
      DescriptorDeviceDiscovery.discoverAll(
        descriptors = setOf(
          ScriptedDescriptor { listOf(device("first")) },
          ScriptedDescriptor { listOf(device("second")) },
        ),
        inventory = HostDeviceInventory.EMPTY,
      )
    }

    assertEquals(setOf("first", "second"), discovered.map { it.instanceId }.toSet())
  }

  /**
   * The inventory a descriptor receives is the one the caller enumerated — a descriptor that maps
   * `adb devices` output can only work if the real enumeration reaches it.
   */
  @Test
  fun `descriptors receive the caller's inventory`() {
    val inventory = HostDeviceInventory(
      adbDevices = listOf(ConnectedAdbDevice(serial = "emulator-5554", description = "Pixel")),
    )

    val discovered = runBlocking {
      DescriptorDeviceDiscovery.discoverAll(
        descriptors = setOf(
          ScriptedDescriptor { inv -> inv.adbDevices.map { device(it.serial) } },
        ),
        inventory = inventory,
      )
    }

    assertEquals(listOf("emulator-5554"), discovered.map { it.instanceId })
  }

  /** A plug-in that throws costs its own devices, not everyone else's. */
  @Test
  fun `a throwing descriptor does not sink the others`() {
    val logs = mutableListOf<String>()

    val discovered = runBlocking {
      DescriptorDeviceDiscovery.discoverAll(
        descriptors = setOf(
          ScriptedDescriptor { error("this plug-in is broken") },
          ScriptedDescriptor { listOf(device("healthy")) },
        ),
        inventory = HostDeviceInventory.EMPTY,
        log = { logs.add(it) },
      )
    }

    assertEquals(listOf("healthy"), discovered.map { it.instanceId })
    assertTrue(
      logs.any { it.contains("this plug-in is broken") },
      "the failure must be observable in the logs: $logs",
    )
  }

  /**
   * A plug-in that hangs is cut off at the discovery budget. The short timeout here is the code
   * under test's own parameter, not a wall-clock bet — the production value is generous hang
   * containment ([DescriptorDeviceDiscovery.DISCOVERY_TIMEOUT_MS]), not a performance budget.
   */
  @Test
  fun `a hanging descriptor is timed out and the others still answer`() {
    val logs = mutableListOf<String>()

    val discovered = runBlocking {
      DescriptorDeviceDiscovery.discoverAll(
        descriptors = setOf(
          ScriptedDescriptor {
            // 600x the injected budget: unreachable margin for the pass case, and cheap enough
            // that a mutant which ignores the timeout is killed in a minute, not ten.
            delay(60_000)
            listOf(device("too-late"))
          },
          ScriptedDescriptor { listOf(device("prompt")) },
        ),
        inventory = HostDeviceInventory.EMPTY,
        timeoutMs = 100,
        log = { logs.add(it) },
      )
    }

    assertEquals(listOf("prompt"), discovered.map { it.instanceId })
    assertTrue(
      logs.any { it.contains("timed out") },
      "the timeout must be observable in the logs: $logs",
    )
  }

  /**
   * The hard case, and why the timeout bounds the await rather than the descriptor: a plug-in
   * stuck in a BLOCKING call ([Thread.sleep] here; a subprocess wait or `Future.get` in life)
   * never reaches a suspension point, so a cooperative timeout around the descriptor itself
   * would fire only when the blocking call finally returned. The worker is abandoned instead.
   */
  @Test
  fun `a descriptor stuck in a blocking call is abandoned, not waited for`() {
    val logs = mutableListOf<String>()

    val discovered = runBlocking {
      DescriptorDeviceDiscovery.discoverAll(
        descriptors = setOf(
          ScriptedDescriptor {
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(10_000)
            listOf(device("blocked"))
          },
          ScriptedDescriptor { listOf(device("prompt")) },
        ),
        inventory = HostDeviceInventory.EMPTY,
        timeoutMs = 100,
        log = { logs.add(it) },
      )
    }

    assertEquals(listOf("prompt"), discovered.map { it.instanceId })
    assertTrue(
      logs.any { it.contains("timed out") },
      "the timeout must be observable in the logs: $logs",
    )
  }
}
