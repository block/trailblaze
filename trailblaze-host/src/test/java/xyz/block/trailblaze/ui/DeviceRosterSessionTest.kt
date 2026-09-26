package xyz.block.trailblaze.ui

import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import maestro.Driver
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
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.BoundDeviceRosterMember
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * One Trailblaze session for a named cast, and one recording per device of it.
 *
 * An MCP session that binds `seller` and `buyer` used to get a session PER DEVICE: the first tool
 * call on the buyer after a `switchDevice` opened a second session, with its own `video.webm`, and
 * no report ever showed the handover. With a roster declared, whichever member opens the session
 * opens it for every member, and the companions are recorded into it.
 */
class DeviceRosterSessionTest {

  private val tempDir: File = Files.createTempDirectory("device-roster-session").toFile()

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  /** A recorder that remembers the device it filmed and whether it was stopped. */
  private class RecordingStream(private val basename: String, private val onStart: () -> Unit = {}) : CaptureStream {
    override val type: CaptureType = CaptureType.VIDEO_WEBM
    var deviceId: String? = null
    var stopped = false
    private var file: File? = null
    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      onStart()
      this.deviceId = deviceId
      file = File(sessionDir, "$basename.webm").apply { writeBytes(byteArrayOf(1)) }
    }
    override fun stop(options: CaptureOptions): CaptureArtifact? {
      stopped = true
      return file?.let { CaptureArtifact(it, type, startTimestampMs = 0, endTimestampMs = 1) }
    }
  }

  private val target = object : TrailblazeHostAppTarget(id = "pos", displayName = "POS") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = listOf("com.example.pos")
    override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val seller = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val buyer = TrailblazeDeviceId("emulator-5556", TrailblazeDevicePlatform.ANDROID)
  private val kitchen = TrailblazeDeviceId("emulator-5558", TrailblazeDevicePlatform.ANDROID)

  private fun member(name: String, device: TrailblazeDeviceId) = BoundDeviceRosterMember(name, device, targetId = null)

  /** Every recorder the coordinator started, by the basename it records under (`video`, `video-buyer`, ...). */
  private val recorders = linkedMapOf<String, RecordingStream>()

  /** Runs as a companion's recorder starts, before it records anything. */
  @Volatile private var onCompanionStart: () -> Unit = {}

  /** Runs as the session's own recorder starts, before its capture is committed. */
  @Volatile private var onPrimaryStart: () -> Unit = {}

  private fun manager(): TrailblazeDeviceManager {
    val logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false)
    return TrailblazeDeviceManager(
      logsRepo = logsRepo,
      settingsRepo = TrailblazeSettingsRepo(
        settingsFile = File(tempDir, "settings.json"),
        initialConfig = SavedTrailblazeAppConfig(
          selectedTrailblazeDriverTypes = emptyMap(),
          selectedTargetAppId = target.id,
          // What makes an interactive session record at all; companions follow the primary.
          captureVideo = true,
        ),
        defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
        allTargetApps = { setOf(target) },
        supportedDriverTypes = emptySet(),
      ),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      currentTrailblazeLlmModelProvider = { error("LLM not available in tests") },
      initialAppTargets = setOf(target),
      appIconProvider = AppIconProvider.DefaultAppIconProvider,
      deviceClassifierIconProvider = DefaultDeviceClassifierIconProvider,
      runYamlLambda = { error("YAML runner not available in tests") },
      installedAppIdsProviderBlocking = { emptySet() },
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
      hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
      sessionCaptureCoordinator = SessionCaptureCoordinator(
        logsRepo = logsRepo,
        companionCaptureFactory = { options, _, basename ->
          CaptureSession(listOf(RecordingStream(basename) { onCompanionStart() }.also { recorders[basename] = it }), options)
        },
        captureSessionFactory = { options, _ ->
          CaptureSession(listOf(RecordingStream("video") { onPrimaryStart() }.also { recorders["video"] = it }), options)
        },
      ),
    )
  }

  @Test
  fun `a session opened by one member of a cast is the session of every member`() {
    val manager = manager()
    assertNull(manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer))), "nobody has acted yet")

    val opened = manager.getOrCreateSessionResolution(seller)

    assertTrue(opened.isNewSession)
    assertEquals(opened.sessionId, manager.getCurrentSessionIdForDevice(buyer), "the buyer's next tool call lands in the same session")
    val onBuyer = manager.getOrCreateSessionResolution(buyer)
    assertEquals(false, onBuyer.isNewSession, "a handover must not open a second session")
    assertEquals(opened.sessionId, onBuyer.sessionId)
  }

  @Test
  fun `every device of the cast is recorded into the shared session`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))

    manager.getOrCreateSessionResolution(seller)

    assertEquals(listOf("video", "video-buyer"), recorders.keys.toList())
    assertEquals(seller.instanceId, recorders.getValue("video").deviceId)
    assertEquals(buyer.instanceId, recorders.getValue("video-buyer").deviceId)
  }

  /** The cast is not tied to who acts first: a session opened on a companion is shared the same way. */
  @Test
  fun `a session opened on a companion is shared and records the start device`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))

    val opened = manager.getOrCreateSessionResolution(buyer)

    assertEquals(opened.sessionId, manager.getCurrentSessionIdForDevice(seller))
    assertEquals(buyer.instanceId, recorders.getValue("video").deviceId)
    assertEquals(seller.instanceId, recorders.getValue("video-seller").deviceId)
  }

  @Test
  fun `a device bound while the session is live joins it and starts recording`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller)))
    val opened = manager.getOrCreateSessionResolution(seller)
    assertEquals(listOf("video"), recorders.keys.toList())

    val shared = manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))

    assertEquals(opened.sessionId, shared)
    assertEquals(opened.sessionId, manager.getCurrentSessionIdForDevice(buyer))
    assertEquals(buyer.instanceId, recorders.getValue("video-buyer").deviceId)
  }

  @Test
  fun `a device unbound mid-session leaves it, its recording stops, the session goes on`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val opened = manager.getOrCreateSessionResolution(seller)

    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller)))

    assertNull(manager.getCurrentSessionIdForDevice(buyer), "the buyer is no longer on the session")
    assertEquals(opened.sessionId, manager.getCurrentSessionIdForDevice(seller), "the seller still is")
    assertTrue(recorders.getValue("video-buyer").stopped)
    assertEquals(false, recorders.getValue("video").stopped, "the session's own recording is untouched")
  }

  @Test
  fun `renaming a member re-records it under the new name`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val opened = manager.getOrCreateSessionResolution(seller)

    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("customer", buyer)))

    assertEquals(opened.sessionId, manager.getCurrentSessionIdForDevice(buyer), "same device, still on the session")
    assertTrue(recorders.getValue("video-buyer").stopped)
    assertEquals(buyer.instanceId, recorders.getValue("video-customer").deviceId)
  }

  @Test
  fun `ending the session from any member ends it for all of them`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val opened = manager.getOrCreateSessionResolution(seller)

    assertEquals(opened.sessionId, manager.endSessionForDevice(buyer))

    assertNull(manager.getCurrentSessionIdForDevice(seller))
    assertNull(manager.getCurrentSessionIdForDevice(buyer))
    assertTrue(recorders.getValue("video").stopped)
    assertTrue(recorders.getValue("video-buyer").stopped)
    assertNull(manager.endSessionForDevice(seller), "nothing left to end")
  }

  @Test
  fun `a companion's first call waits until its recorder has started`() {
    val recorderStarting = CountDownLatch(1)
    val letRecorderStart = CountDownLatch(1)
    onCompanionStart = { recorderStarting.countDown(); letRecorderStart.await(10, TimeUnit.SECONDS) }
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val opener = thread { manager.getOrCreateSessionResolution(seller) }
    assertTrue(recorderStarting.await(10, TimeUnit.SECONDS), "the buyer's recorder is starting")

    // The buyer is already on the session; its call must not return (and act) before its recording runs.
    val joined = CompletableFuture.supplyAsync { manager.getOrCreateSessionResolution(buyer) }
    assertFailsWith<TimeoutException> { joined.get(300, TimeUnit.MILLISECONDS) }

    letRecorderStart.countDown()
    assertEquals(false, joined.get(10, TimeUnit.SECONDS).isNewSession)
    assertEquals(buyer.instanceId, recorders.getValue("video-buyer").deviceId)
    opener.join(10_000)
  }

  @Test
  fun `a device bound while the session's own recorder is starting is recorded once it has`() {
    val primaryStarting = CountDownLatch(1)
    val letPrimaryStart = CountDownLatch(1)
    onPrimaryStart = { primaryStarting.countDown(); letPrimaryStart.await(10, TimeUnit.SECONDS) }
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller)))
    val opener = thread { manager.getOrCreateSessionResolution(seller) }
    assertTrue(primaryStarting.await(10, TimeUnit.SECONDS), "the seller's recorder is starting")

    val bound = CompletableFuture.supplyAsync {
      manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    }
    // Recorded now, the buyer would be refused — the session's capture is not running yet.
    assertFailsWith<TimeoutException> { bound.get(300, TimeUnit.MILLISECONDS) }
    letPrimaryStart.countDown()

    assertEquals(manager.getCurrentSessionIdForDevice(seller), bound.get(10, TimeUnit.SECONDS))
    assertEquals(buyer.instanceId, recorders.getValue("video-buyer").deviceId)
    opener.join(10_000)
  }

  @Test
  fun `a device unbound while the session's own recorder is starting is not left recording`() {
    val primaryStarting = CountDownLatch(1)
    val letPrimaryStart = CountDownLatch(1)
    onPrimaryStart = { primaryStarting.countDown(); letPrimaryStart.await(10, TimeUnit.SECONDS) }
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val opener = thread { manager.getOrCreateSessionResolution(seller) }
    assertTrue(primaryStarting.await(10, TimeUnit.SECONDS), "the seller's recorder is starting")

    val unbound = CompletableFuture.supplyAsync { manager.setDeviceRoster("mcp-1", listOf(member("seller", seller))) }
    letPrimaryStart.countDown()
    unbound.get(10, TimeUnit.SECONDS)
    opener.join(10_000)

    assertNull(manager.getCurrentSessionIdForDevice(buyer), "the buyer left the session")
    assertEquals(true, recorders["video-buyer"]?.stopped ?: true, "the buyer is not being recorded")
  }

  @Test
  fun `a device unbound while its own recorder is starting is stopped once it has started`() {
    // The opener is already recording the cast when the unbind lands: stopping before that start
    // finishes would find nothing, and the recorder would run until the session ends.
    val buyerStarting = CountDownLatch(1)
    val letBuyerStart = CountDownLatch(1)
    onCompanionStart = { buyerStarting.countDown(); letBuyerStart.await(10, TimeUnit.SECONDS) }
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val opener = thread { manager.getOrCreateSessionResolution(seller) }
    assertTrue(buyerStarting.await(10, TimeUnit.SECONDS), "the buyer's recorder is starting")

    val unbound = CompletableFuture.supplyAsync { manager.setDeviceRoster("mcp-1", listOf(member("seller", seller))) }
    letBuyerStart.countDown()
    unbound.get(10, TimeUnit.SECONDS)
    opener.join(10_000)

    assertTrue(recorders.getValue("video-buyer").stopped, "the buyer is not left recording")
  }

  @Test
  fun `unbinding the device that opened the cast's session takes it off that session`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val cast = manager.getOrCreateSessionResolution(seller).sessionId

    manager.setDeviceRoster("mcp-1", listOf(member("buyer", buyer)))

    assertNull(manager.getCurrentSessionIdForDevice(seller), "the unbound opener is off the cast's session")
    assertEquals(cast, manager.getCurrentSessionIdForDevice(buyer), "the cast's session goes on with its members")
  }

  @Test
  fun `unbinding the device another client's session runs on leaves it on that session`() {
    val manager = manager()
    val theirs = manager.getOrCreateSessionResolution(seller).sessionId
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))

    manager.setDeviceRoster("mcp-1", listOf(member("buyer", buyer)))

    assertEquals(theirs, manager.getCurrentSessionIdForDevice(seller), "the other client keeps its session on its device")
  }

  @Test
  fun `a member forcing a new session moves the whole cast onto it`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val old = manager.getOrCreateSessionResolution(seller).sessionId
    val oldRecorders = recorders.toMap()

    val fresh = manager.getOrCreateSessionResolution(seller, forceNewSession = true).sessionId

    assertEquals(fresh, manager.getCurrentSessionIdForDevice(buyer), "the buyer's next call lands in the new session")
    assertTrue(oldRecorders.getValue("video").stopped, "the replaced session is released")
    assertTrue(oldRecorders.getValue("video-buyer").stopped, "and so is its recording of the buyer")
    assertEquals(buyer.instanceId, recorders.getValue("video-buyer").deviceId, "the new session records the buyer")
    assertEquals(false, recorders.getValue("video-buyer").stopped)
    assertTrue(old != fresh)
  }

  @Test
  fun `a member forcing a new session leaves another client's session running on its own device`() {
    val manager = manager()
    val theirs = manager.getOrCreateSessionResolution(seller).sessionId
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val theirRecording = recorders.getValue("video")
    val theirRecordingOfBuyer = recorders.getValue("video-buyer")

    val fresh = manager.getOrCreateSessionResolution(buyer, forceNewSession = true).sessionId

    assertEquals(fresh, manager.getCurrentSessionIdForDevice(buyer))
    assertEquals(theirs, manager.getCurrentSessionIdForDevice(seller), "the other client keeps its session")
    assertEquals(false, theirRecording.stopped, "and its recording")
    assertTrue(theirRecordingOfBuyer.stopped, "the buyer left it, so it no longer records the buyer")
  }

  @Test
  fun `a member forcing a new session is recorded in the session it left until its other calls end`() {
    val manager = manager()
    manager.getOrCreateSessionResolution(seller)
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val theirRecordingOfBuyer = recorders.getValue("video-buyer")
    val earlierCall = manager.beginRun(buyer)
    val forcingCall = manager.beginRun(buyer)

    manager.getOrCreateSessionResolution(buyer, forceNewSession = true, callerRun = forcingCall)
    manager.endRun(forcingCall)
    assertEquals(false, theirRecordingOfBuyer.stopped, "the earlier call is still logging into that session")

    manager.endRun(earlierCall)

    assertTrue(theirRecordingOfBuyer.stopped)
  }

  @Test
  fun `a replaced session is not released while the member that forced a new one still logs into it`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    manager.getOrCreateSessionResolution(seller)
    val oldRecording = recorders.getValue("video")
    val sellersEarlierCall = manager.beginRun(seller)
    val buyersCall = manager.beginRun(buyer)
    val forcingCall = manager.beginRun(seller)
    manager.getOrCreateSessionResolution(seller, forceNewSession = true, callerRun = forcingCall)
    manager.endRun(forcingCall)

    // The buyer follows the cast and leaves the old session with no device on it.
    manager.endRun(buyersCall)
    assertEquals(false, oldRecording.stopped, "the seller's earlier call is still logging into it")

    manager.endRun(sellersEarlierCall)

    assertTrue(oldRecording.stopped)
  }

  @Test
  fun `a member moved off another client's session stops being recorded there`() {
    val manager = manager()
    val theirs = manager.getOrCreateSessionResolution(seller).sessionId
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer), member("kitchen", kitchen)))
    val theirRecordingOfBuyer = recorders.getValue("video-buyer")

    val fresh = manager.getOrCreateSessionResolution(kitchen, forceNewSession = true).sessionId

    assertEquals(fresh, manager.getCurrentSessionIdForDevice(buyer), "the buyer follows the cast")
    assertEquals(theirs, manager.getCurrentSessionIdForDevice(seller))
    assertTrue(theirRecordingOfBuyer.stopped, "the session it left goes on without recording it")
    assertEquals(buyer.instanceId, recorders.getValue("video-buyer").deviceId, "the new session records the buyer")
    assertEquals(false, recorders.getValue("video-buyer").stopped)
  }

  @Test
  fun `a member whose run is executing follows the cast onto the new session once the run ends`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    val old = manager.getOrCreateSessionResolution(seller).sessionId
    val oldRecorders = recorders.toMap()
    val buyersRun = manager.beginRun(buyer)

    val fresh = manager.getOrCreateSessionResolution(seller, forceNewSession = true).sessionId

    assertEquals(old, manager.getCurrentSessionIdForDevice(buyer), "the buyer's run is still logging there")
    assertEquals(false, oldRecorders.getValue("video-buyer").stopped, "the running session keeps recording the buyer")

    manager.endRun(buyersRun)

    assertEquals(fresh, manager.getCurrentSessionIdForDevice(buyer), "the buyer's next call lands in the cast's session")
    assertTrue(oldRecorders.getValue("video-buyer").stopped, "the session it left is released")
    assertEquals(buyer.instanceId, recorders.getValue("video-buyer").deviceId, "the new session records the buyer")
    assertEquals(false, recorders.getValue("video-buyer").stopped)
  }

  @Test
  fun `a member following the cast after its run is recorded before a call on it acts`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    manager.getOrCreateSessionResolution(seller)
    val buyersRun = manager.beginRun(buyer)
    val fresh = manager.getOrCreateSessionResolution(seller, forceNewSession = true).sessionId
    val recorderStarting = CountDownLatch(1)
    val letRecorderStart = CountDownLatch(1)
    onCompanionStart = { recorderStarting.countDown(); letRecorderStart.await(10, TimeUnit.SECONDS) }
    val follower = thread { manager.endRun(buyersRun) }
    assertTrue(recorderStarting.await(10, TimeUnit.SECONDS), "the buyer's recorder in the new session is starting")

    // The buyer is on the new session already; its next call must not return (and act) before it is recorded.
    val call = CompletableFuture.supplyAsync { manager.getOrCreateSessionResolution(buyer) }
    assertFailsWith<TimeoutException> { call.get(300, TimeUnit.MILLISECONDS) }

    letRecorderStart.countDown()
    assertEquals(fresh, call.get(10, TimeUnit.SECONDS).sessionId)
    assertEquals(buyer.instanceId, recorders.getValue("video-buyer").deviceId)
    follower.join(10_000)
  }

  /** Both members force a new session at once: the one whose run held it back must not split the cast. */
  @Test
  fun `a member waiting to follow the cast that forces a new session takes the whole cast onto it`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    manager.getOrCreateSessionResolution(seller)
    val buyersRun = manager.beginRun(buyer)
    manager.getOrCreateSessionResolution(seller, forceNewSession = true)
    val sellersFirst = recorders.getValue("video")

    val latest = manager.getOrCreateSessionResolution(buyer, forceNewSession = true, callerRun = buyersRun).sessionId

    assertEquals(latest, manager.getCurrentSessionIdForDevice(seller), "the seller joins the buyer's session")
    assertTrue(sellersFirst.stopped, "the session the seller left is released")
    manager.endRun(buyersRun)
    assertEquals(latest, manager.getCurrentSessionIdForDevice(buyer))
    assertEquals(latest, manager.getCurrentSessionIdForDevice(seller))
  }

  /** A dispatcher holds its run from before it resolves the session; that run is not a sibling of its own. */
  @Test
  fun `a run forcing a new session still releases the one it replaced`() {
    val manager = manager()
    manager.getOrCreateSessionResolution(seller)
    val replaced = recorders.getValue("video")
    val dispatching = manager.beginRun(seller)

    manager.getOrCreateSessionResolution(seller, forceNewSession = true, callerRun = dispatching)

    assertTrue(replaced.stopped)
  }

  @Test
  fun `a replaced session kept for a sibling run is released once that run ends`() {
    val manager = manager()
    manager.getOrCreateSessionResolution(seller)
    val replaced = recorders.getValue("video")
    val sibling = manager.beginRun(seller)

    manager.getOrCreateSessionResolution(seller, forceNewSession = true)
    assertEquals(false, replaced.stopped, "the sibling may still be logging into it")

    manager.endRun(sibling)

    assertTrue(replaced.stopped)
  }

  /** A driver that only answers `close()`, and remembers it was asked. */
  private class ClosableDriver {
    var closed = false
    val driver: Driver = Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, method, _ ->
      if (method.name == "close") closed = true else error("unexpected ${method.name}")
      null
    } as Driver
  }

  @Test
  fun `ending or cancelling a shared session releases every member's driver`() {
    for (endIt in listOf<(TrailblazeDeviceManager) -> Unit>({ it.endSessionForDevice(seller) }, { it.cancelSessionForDevice(seller) })) {
      val manager = manager()
      manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
      manager.getOrCreateSessionResolution(seller)
      val sellerDriver = ClosableDriver().also { manager.setActiveDriverForDevice(seller, it.driver) }
      val buyerDriver = ClosableDriver().also { manager.setActiveDriverForDevice(buyer, it.driver) }
      // A device outside the cast, on a session of its own, keeps its driver.
      manager.getOrCreateSessionResolution(kitchen)
      val kitchenDriver = ClosableDriver().also { manager.setActiveDriverForDevice(kitchen, it.driver) }

      endIt(manager)

      assertTrue(sellerDriver.closed)
      assertTrue(buyerDriver.closed, "the buyer left the session with the seller")
      assertNull(manager.getActiveDriverForDevice(buyer))
      assertEquals(false, kitchenDriver.closed)
    }
  }

  @Test
  fun `a dissolved cast lets go of the devices it brought onto another client's session`() {
    val manager = manager()
    // Another client's session, opened before any cast existed.
    val theirs = manager.getOrCreateSessionResolution(seller).sessionId
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    assertEquals(theirs, manager.getCurrentSessionIdForDevice(buyer), "the cast shares the session its member is on")

    // The MCP session closed without ending that session — it was never its to end.
    manager.setDeviceRoster("mcp-1", emptyList())

    assertNull(manager.getCurrentSessionIdForDevice(buyer), "the buyer leaves with the cast that brought it")
    assertTrue(recorders.getValue("video-buyer").stopped)
    assertEquals(theirs, manager.getCurrentSessionIdForDevice(seller), "the session goes on on its own device")
    assertEquals(false, recorders.getValue("video").stopped)
  }

  /** Bookkeeping only: once the MCP session is gone its cast must not pull a stranger's session onto every member. */
  @Test
  fun `a dissolved cast stops sharing sessions`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))
    manager.getOrCreateSessionResolution(seller).also { manager.endSessionForDevice(seller) }
    manager.setDeviceRoster("mcp-1", emptyList())

    recorders.clear()
    manager.getOrCreateSessionResolution(seller)

    assertNull(manager.getCurrentSessionIdForDevice(buyer), "the buyer is nobody's companion any more")
    assertEquals(listOf("video"), recorders.keys.toList(), "only the start device is recorded")
  }

  /** A member with live work of its own keeps it; it is left off the shared session, not torn out. */
  @Test
  fun `a device bound while it runs a session of its own stays on that session`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller)))
    val shared = manager.getOrCreateSessionResolution(seller)
    val own = manager.getOrCreateSessionResolution(kitchen)

    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("kitchen", kitchen)))

    assertEquals(own.sessionId, manager.getCurrentSessionIdForDevice(kitchen))
    assertEquals(shared.sessionId, manager.getCurrentSessionIdForDevice(seller))
    assertNull(recorders["video-kitchen"], "a device on its own session is not recorded into the cast's as well")
  }

  @Test
  fun `a target set on the start device does not re-target a companion`() {
    val manager = manager()
    manager.setDeviceRoster("mcp-1", listOf(member("seller", seller), member("buyer", buyer)))

    val assignment = manager.setTargetForActiveSession(seller, target.id)

    assertEquals(true, assignment.isNewSession)
    assertEquals(target.id, manager.getTargetForActiveSession(seller))
    assertNull(manager.getTargetForActiveSession(buyer), "the buyer shares the session, not the seller's --target")
    assertEquals(target.id, manager.getTargetForSession(assignment.sessionId!!), "session-wide readers still see the override")
  }
}
