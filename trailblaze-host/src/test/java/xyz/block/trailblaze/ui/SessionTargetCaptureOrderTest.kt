package xyz.block.trailblaze.ui

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.capture.SessionCaptureCoordinator
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Which app capture is scoped to when a session's target is set.
 *
 * Setting a per-session target can be what creates the session, and capture resolves the app it
 * samples while that session is being created. The two have to agree: if capture resolves first it
 * binds to the daemon-wide target, and the run then drives one app while the memory readings
 * describe another — silently, because a reading against the wrong package just comes back with no
 * app figures at all.
 */
class SessionTargetCaptureOrderTest {

  private val tempDir: File = Files.createTempDirectory("session-target-capture").toFile()

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  /** Records the app id capture was started with — the thing this class resolves. */
  private class RecordingStream : CaptureStream {
    override val type = CaptureType.MEMORY
    var startedWithAppId: String? = null
    var started = false

    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      started = true
      startedWithAppId = appId
    }

    override fun stop(options: CaptureOptions): CaptureArtifact? = null
  }

  private fun target(id: String, androidAppId: String): TrailblazeHostAppTarget =
    object : TrailblazeHostAppTarget(id = id, displayName = "Target $id") {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
        if (platform == TrailblazeDevicePlatform.ANDROID) listOf(androidAppId) else null

      override fun internalGetCustomToolsForDriver(
        driverType: TrailblazeDriverType,
      ): Set<KClass<out TrailblazeTool>> = emptySet()
    }

  private val daemonWide = target("daemon-wide", "com.example.daemonwide")
  private val sessionTarget = target("session-target", "com.example.sessiontarget")

  /** A target with two flavors on one platform — the case where picking needs the device. */
  private val twoFlavorTarget = object : TrailblazeHostAppTarget(id = "kiosk", displayName = "Kiosk") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf("com.example.kiosk.development", "com.example.kiosk")

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private fun manager(
    stream: RecordingStream,
    installedAppIdsOnDevice: (TrailblazeDeviceId) -> Set<String> = { emptySet() },
  ): TrailblazeDeviceManager {
    val logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false)
    return TrailblazeDeviceManager(
      logsRepo = logsRepo,
      settingsRepo = TrailblazeSettingsRepo(
        settingsFile = File(tempDir, "settings.json"),
        // The daemon-wide selection: what capture binds to when it cannot see the session's target.
        initialConfig = SavedTrailblazeAppConfig(
          selectedTrailblazeDriverTypes = emptyMap(),
          selectedTargetAppId = daemonWide.id,
        ),
        defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
        allTargetApps = { setOf(daemonWide, sessionTarget) },
        supportedDriverTypes = emptySet(),
      ),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      currentTrailblazeLlmModelProvider = { error("LLM not available in tests") },
      initialAppTargets = setOf(daemonWide, sessionTarget),
      appIconProvider = AppIconProvider.DefaultAppIconProvider,
      deviceClassifierIconProvider = DefaultDeviceClassifierIconProvider,
      runYamlLambda = { error("YAML runner not available in tests") },
      installedAppIdsProviderBlocking = installedAppIdsOnDevice,
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
      hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
      sessionCaptureCoordinator = SessionCaptureCoordinator(
        logsRepo = logsRepo,
        captureSessionFactory = { options, _ -> CaptureSession(listOf(stream), options) },
      ),
    )
  }

  @Test
  fun `capture is scoped to the session's target, not the daemon-wide one, when setting it starts the session`() {
    val stream = RecordingStream()
    val manager = manager(stream)
    val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

    // No session yet, so this call creates one. The target it sets has to be the one capture sees.
    val assignment = manager.setTargetForActiveSession(device, sessionTarget.id)

    assertEquals(true, assignment.isNewSession, "this call is what created the session")
    assertEquals(true, stream.started, "capture started for the new session")
    assertEquals(
      "com.example.sessiontarget",
      stream.startedWithAppId,
      "capture must sample the app the session targets, not the daemon-wide selection",
    )
  }

  @Test
  fun `capture picks the declared app id the device actually has, not the first one declared`() {
    // A kiosk target declares its development flavor first and its production one second, and the
    // device in front of us only has the production build. Scoping capture to the first declared
    // id reports the app as not running for the whole session — device memory and nothing else —
    // because `pidof` against an absent package simply answers nothing.
    val manager = manager(RecordingStream())
    val device = TrailblazeDeviceId("kiosk-0001", TrailblazeDevicePlatform.ANDROID)

    val resolved = manager.resolveCaptureAppId(
      trailblazeDeviceId = device,
      target = twoFlavorTarget,
      candidateAppIds = twoFlavorTarget.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID),
      installedAppIdsProvider = { setOf("com.example.kiosk", "com.android.settings") },
    )

    assertEquals("com.example.kiosk", resolved)
  }

  @Test
  fun `a device probe that fails falls back to the first declared id rather than dropping capture`() {
    val manager = manager(RecordingStream())
    val device = TrailblazeDeviceId("kiosk-0001", TrailblazeDevicePlatform.ANDROID)

    // An adb failure answers an empty set — no worse than never asking, so capture still gets an
    // app id and a reading can still find the app if that guess happens to be the installed one.
    assertEquals(
      "com.example.kiosk.development",
      manager.resolveCaptureAppId(
        trailblazeDeviceId = device,
        target = twoFlavorTarget,
        candidateAppIds = twoFlavorTarget.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID),
        installedAppIdsProvider = { emptySet() },
      ),
    )
  }

  @Test
  fun `a single declared app id needs no device probe`() {
    var probed = false
    val manager = manager(RecordingStream())
    val resolved = manager.resolveCaptureAppId(
      trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      target = sessionTarget,
      candidateAppIds = listOf("com.example.sessiontarget"),
      installedAppIdsProvider = { probed = true; emptySet() },
    )
    assertEquals("com.example.sessiontarget", resolved)
    assertEquals(false, probed, "with nothing to choose between, the device is not asked")
  }

  // -- The default probe: what resolveCaptureAppId asks when no provider is injected --

  @Test
  fun `the capture probe asks the device rather than answering from a stale inventory`() {
    // The inventory is whatever the last probe saw. A device that had the development flavor when
    // it was last enumerated, and has since been reflashed with the production one, would otherwise
    // scope capture to a package that is no longer installed — the silent failure where every
    // reading reports the app as not running and the session records device memory only.
    val device = TrailblazeDeviceId("kiosk-0001", TrailblazeDevicePlatform.ANDROID)
    var probes = 0
    val manager = manager(RecordingStream()) {
      probes++
      // First answer seeds the inventory; the reinstall happens between the two.
      if (probes == 1) setOf("com.example.kiosk.development") else setOf("com.example.kiosk")
    }
    runBlocking { manager.refreshAppInventory(device, includeVersionInfo = false) }
    assertEquals(
      setOf("com.example.kiosk.development"),
      manager.installedAppIdsByDeviceFlow.value[device],
      "precondition: the inventory now holds the pre-reinstall answer",
    )

    assertEquals(setOf("com.example.kiosk"), manager.probeInstalledAppIdsForCapture(device))
  }

  @Test
  fun `a capture probe that never answers falls back to the inventory instead of blocking`() {
    // The probe used to be a direct blocking call with no deadline, so an uncached multi-id target
    // against a wedged transport held up session startup for as long as the transport stayed wedged.
    // The bound is passed IN here, so this waits on a deadline the test owns rather than betting on
    // how loaded the machine is.
    val device = TrailblazeDeviceId("kiosk-0001", TrailblazeDevicePlatform.ANDROID)
    var probes = 0
    val manager = manager(RecordingStream()) {
      probes++
      if (probes == 1) setOf("com.example.kiosk") else Thread.sleep(TimeUnit.MINUTES.toMillis(5)).let { emptySet() }
    }
    runBlocking { manager.refreshAppInventory(device, includeVersionInfo = false) }

    assertEquals(
      setOf("com.example.kiosk"),
      manager.probeInstalledAppIdsForCapture(device, timeoutSeconds = 1),
      "a wedged device leaves the last inventory as the best available answer",
    )
  }

  @Test
  fun `a capture probe that comes back empty falls back to the inventory too`() {
    // The layer under the probe reports an adb failure by returning nothing, not by throwing, so an
    // empty answer is the shape the commonest failure arrives in — and a running device always has
    // packages. Taking empty at face value would discard a usable cached inventory and scope
    // capture to the first declared id, which is the failure this probe exists to prevent.
    val device = TrailblazeDeviceId("kiosk-0001", TrailblazeDevicePlatform.ANDROID)
    var probes = 0
    val manager = manager(RecordingStream()) {
      probes++
      if (probes == 1) setOf("com.example.kiosk") else emptySet()
    }
    runBlocking { manager.refreshAppInventory(device, includeVersionInfo = false) }

    assertEquals(
      setOf("com.example.kiosk"),
      manager.probeInstalledAppIdsForCapture(device),
      "a probe that answered nothing leaves the last inventory as the best available answer",
    )
  }

  @Test
  fun `a capture probe that never answers and has no inventory answers empty`() {
    // Empty is what resolveCaptureAppId reads as "could not tell", which keeps the first declared
    // id. Answering with a partial or invented set would scope capture to a guess presented as fact.
    val device = TrailblazeDeviceId("kiosk-0001", TrailblazeDevicePlatform.ANDROID)
    val manager = manager(RecordingStream()) {
      Thread.sleep(TimeUnit.MINUTES.toMillis(5))
      setOf("com.example.kiosk")
    }

    assertEquals(emptySet(), manager.probeInstalledAppIdsForCapture(device, timeoutSeconds = 1))
  }

  @Test
  fun `clearing the target removes the per-session override`() {
    // A blank target is a caller removing the override, which is not a statement about a different
    // app: with no override in place the daemon-wide selection is what later reads answer with.
    // This asserts the override is gone, which is all this path does — it starts no capture.
    val stream = RecordingStream()
    val manager = manager(stream)
    val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    manager.trackActiveSession(device, xyz.block.trailblaze.logs.model.SessionId("pre-existing"))

    manager.setTargetForActiveSession(device, "")

    assertEquals(null, manager.getTargetForActiveSession(device), "the override is cleared")
  }
}
