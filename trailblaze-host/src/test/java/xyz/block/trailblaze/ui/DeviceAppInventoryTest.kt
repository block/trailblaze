package xyz.block.trailblaze.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.AppVersionInfo

class DeviceAppInventoryTest {

  // Matches production wiring (TrailblazeDeviceManager.loadDevicesScope): the class contract
  // requires a supervisor scope so one failing probe can't cancel every later one.
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private val android = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val ios = TrailblazeDeviceId("SIM-UDID", TrailblazeDevicePlatform.IOS)

  private fun versionInfo(versionName: String) =
    AppVersionInfo(trailblazeDeviceId = android, versionCode = "1", versionName = versionName)

  @After
  fun tearDown() {
    scope.cancel()
  }

  @Test
  fun `refresh returns installed ids and publishes both flows`() = runBlocking {
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = { setOf("com.example.app", "com.other.app") },
      appVersionInfoProvider = { _, appId ->
        if (appId == "com.example.app") versionInfo("1.2.3") else null
      },
    )

    val installed = inventory.refresh(android, relevantAppIds = setOf("com.example.app"))

    assertEquals(setOf("com.example.app", "com.other.app"), installed)
    assertEquals(installed, inventory.installedAppIdsByDeviceFlow.value[android])
    assertEquals(
      "1.2.3",
      inventory.appVersionInfoByDeviceFlow.value.getVersionInfo(android, "com.example.app")?.versionName,
    )
    // Version info is probed only for relevant apps — the non-relevant app has no entry.
    assertNull(inventory.appVersionInfoByDeviceFlow.value.getVersionInfo(android, "com.other.app"))
  }

  @Test
  fun `concurrent refreshes for the same device share one probe`() = runBlocking {
    val probeStarted = CountDownLatch(1)
    val releaseProbe = CountDownLatch(1)
    val probeCount = AtomicInteger(0)
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = {
        probeCount.incrementAndGet()
        probeStarted.countDown()
        releaseProbe.await(10, TimeUnit.SECONDS)
        setOf("com.example.app")
      },
      appVersionInfoProvider = { _, _ -> null },
    )

    // First caller on a real multi-thread dispatcher: the test thread blocks on latches below,
    // which would starve runBlocking's single-threaded dispatcher.
    val first = async(Dispatchers.Default) { inventory.refresh(android, emptySet()) }
    assertTrue(probeStarted.await(10, TimeUnit.SECONDS))
    // UNDISPATCHED runs the second caller synchronously on this thread until it suspends —
    // i.e. it has deterministically reached refresh() and joined the in-flight probe (which is
    // still latched) before this line returns. No sleep, no race.
    val second = async(start = CoroutineStart.UNDISPATCHED) { inventory.refresh(android, emptySet()) }
    releaseProbe.countDown()

    assertEquals(setOf("com.example.app"), first.await())
    assertEquals(setOf("com.example.app"), second.await())
    assertEquals(1, probeCount.get())
  }

  @Test
  fun `cancelling one caller does not cancel the probe another caller joined`() = runBlocking {
    val probeStarted = CountDownLatch(1)
    val releaseProbe = CountDownLatch(1)
    val probeCount = AtomicInteger(0)
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = {
        probeCount.incrementAndGet()
        probeStarted.countDown()
        releaseProbe.await(10, TimeUnit.SECONDS)
        setOf("com.example.app")
      },
      appVersionInfoProvider = { _, _ -> null },
    )

    // Caller A starts the probe; caller B joins it (UNDISPATCHED = deterministically suspended
    // on the same in-flight probe before this returns).
    val callerA = launch(Dispatchers.Default) { inventory.refresh(android, emptySet()) }
    assertTrue(probeStarted.await(10, TimeUnit.SECONDS))
    val callerB = async(start = CoroutineStart.UNDISPATCHED) { inventory.refresh(android, emptySet()) }

    // A walks away mid-probe. Because the probe runs on the shared scope rather than A's, B
    // must still get its result — this is what a refactor to `coroutineScope { async { … } }`
    // would silently break.
    callerA.cancel()
    releaseProbe.countDown()

    assertEquals(setOf("com.example.app"), callerB.await())
    assertEquals(1, probeCount.get())
  }

  @Test
  fun `each new refresh re-probes`() = runBlocking {
    val results = ArrayDeque(listOf(setOf("com.v1"), setOf("com.v1", "com.v2")))
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = { results.removeFirst() },
      appVersionInfoProvider = { _, _ -> null },
    )

    assertEquals(setOf("com.v1"), inventory.refresh(android, emptySet()))
    assertEquals(setOf("com.v1", "com.v2"), inventory.refresh(android, emptySet()))
    assertEquals(setOf("com.v1", "com.v2"), inventory.installedAppIdsByDeviceFlow.value[android])
  }

  @Test
  fun `a failed probe returns null distinct from an empty set and is not sticky`() = runBlocking {
    var fail = true
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = {
        if (fail) error("device query failed") else setOf("com.example.app")
      },
      appVersionInfoProvider = { _, _ -> null },
    )

    // Null, NOT empty: an empty set means "no apps installed", which callers act on (a launch
    // gated on installation would be rejected outright).
    assertNull(inventory.refresh(android, emptySet()))
    fail = false
    assertEquals(setOf("com.example.app"), inventory.refresh(android, emptySet()))
  }

  @Test
  fun `a probe that reports no apps returns an empty set, not null`() = runBlocking {
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = { emptySet() },
      appVersionInfoProvider = { _, _ -> null },
    )

    assertEquals(emptySet(), inventory.refresh(android, emptySet()))
  }

  @Test
  fun `a failed probe leaves previously known inventory intact`() = runBlocking {
    var fail = false
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = { if (fail) error("device query failed") else setOf("com.example.app") },
      appVersionInfoProvider = { _, _ -> versionInfo("1.0") },
    )
    inventory.refresh(android, relevantAppIds = setOf("com.example.app"))

    fail = true
    assertNull(inventory.refresh(android, relevantAppIds = setOf("com.example.app")))

    // Publishing empty over good inventory would make an installed app read as uninstalled.
    assertEquals(setOf("com.example.app"), inventory.installedAppIdsByDeviceFlow.value[android])
    assertEquals("1.0", inventory.appVersionInfoByDeviceFlow.value.getVersionInfo(android, "com.example.app")?.versionName)
  }

  @Test
  fun `re-probe drops the version entry of an app that is no longer installed`() = runBlocking {
    var installed = setOf("com.example.app")
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = { installed },
      appVersionInfoProvider = { _, _ -> versionInfo("1.0") },
    )
    inventory.refresh(android, relevantAppIds = setOf("com.example.app"))

    // App uninstalled: the stale version entry must not linger after a re-probe.
    installed = emptySet()
    inventory.refresh(android, relevantAppIds = setOf("com.example.app"))

    assertNull(inventory.appVersionInfoByDeviceFlow.value.getVersionInfo(android, "com.example.app"))
  }

  @Test
  fun `an ID-only probe keeps version entries for apps that are still installed`() = runBlocking {
    val versionProbeCount = AtomicInteger(0)
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = { setOf("com.example.app") },
      appVersionInfoProvider = { _, _ ->
        versionProbeCount.incrementAndGet()
        versionInfo("1.0")
      },
    )
    inventory.refresh(android, relevantAppIds = setOf("com.example.app"))

    // Skipping the version fan-out (relevantAppIds empty) must not double as clearing the
    // cache — this runs on every agent launchApp, and the UI reads these entries.
    inventory.refresh(android, relevantAppIds = emptySet())

    assertEquals(1, versionProbeCount.get())
    assertEquals("1.0", inventory.appVersionInfoByDeviceFlow.value.getVersionInfo(android, "com.example.app")?.versionName)
  }

  @Test
  fun `a version probe that returns null drops the entry, by design`() = runBlocking {
    var versionKnown = true
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = { setOf("com.example.app") },
      appVersionInfoProvider = { _, _ -> if (versionKnown) versionInfo("1.0") else null },
    )
    inventory.refresh(android, relevantAppIds = setOf("com.example.app"))

    // Stating the intent rather than assuming it: unlike a failed *installed-apps* probe (which
    // returns null and keeps everything), a version probe that answers "no version" is taken at
    // its word, so the badge can't keep showing a version the device no longer reports. The cost
    // is that a transient version-probe timeout blanks the badge until the next probe.
    versionKnown = false
    inventory.refresh(android, relevantAppIds = setOf("com.example.app"))

    assertNull(inventory.appVersionInfoByDeviceFlow.value.getVersionInfo(android, "com.example.app"))
    assertEquals(setOf("com.example.app"), inventory.installedAppIdsByDeviceFlow.value[android])
  }

  @Test
  fun `a torn-down scope reports probe failure and never wedges`() = runBlocking {
    val deadScope = CoroutineScope(Dispatchers.IO)
    deadScope.cancel()
    val inventory = DeviceAppInventory(
      scope = deadScope,
      installedAppIdsProvider = { setOf("com.example.app") },
      appVersionInfoProvider = { _, _ -> null },
    )

    // Probes launched on the cancelled scope never run; the caller must get the failure signal,
    // not a CancellationException it didn't cause — and repeatedly, i.e. the dead in-flight
    // entry must not wedge the device's slot.
    assertNull(inventory.refresh(android, emptySet()))
    assertNull(inventory.refresh(android, emptySet()))
  }

  @Test
  fun `prune drops disconnected devices and keeps connected ones`() = runBlocking {
    val inventory = DeviceAppInventory(
      scope = scope,
      installedAppIdsProvider = { deviceId -> setOf("app.on.${deviceId.instanceId}") },
      appVersionInfoProvider = { _, _ -> versionInfo("1.0") },
    )
    inventory.refresh(android, relevantAppIds = setOf("app.on.${android.instanceId}"))
    inventory.refresh(ios, relevantAppIds = setOf("app.on.${ios.instanceId}"))

    inventory.prune(connectedDeviceIds = setOf(android))

    assertEquals(setOf(android), inventory.installedAppIdsByDeviceFlow.value.keys)
    assertEquals(setOf(android), inventory.appVersionInfoByDeviceFlow.value.keys.map { it.deviceId }.toSet())
  }
}
