package xyz.block.trailblaze.host.capture

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.ToolCallAwareCaptureStream
import xyz.block.trailblaze.capture.ToolCallPhase
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.capture.video.PlaywrightVideoRecordDir
import xyz.block.trailblaze.capture.video.WebScreencastFeedRegistry
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.ToolCallObservers
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Unit tests for the per-`SessionId` capture coordinator that #3077 introduced. The
 * production behavior under test:
 *  - Idempotency: a second `startForSession` for the same id is a no-op, even under
 *    concurrent threads.
 *  - Platform gating: `WEB` short-circuits without touching the registry (Playwright
 *    self-instruments).
 *  - Null platform / disabled platform: when the factory returns null (iOS today,
 *    null/Compose), the call is a clean no-op.
 *  - Exception cleanup: a throw from `CaptureSession.startAll` removes the
 *    reservation AND runs a best-effort `stopAll` so the partially-started subprocess
 *    doesn't leak.
 *  - Stop on unknown session: `stopForSession` returns false cleanly.
 *
 * Tests use an injectable `captureSessionFactory` to swap in a fake [CaptureSession]
 * built from a [FakeStream], so the coordinator can be exercised end-to-end without
 * spawning real `screenrecord` / `xcrun` subprocesses.
 */
class SessionCaptureCoordinatorTest {

  private lateinit var tempDir: File
  private lateinit var logsRepo: LogsRepo

  @BeforeTest
  fun setUp() {
    tempDir = java.nio.file.Files.createTempDirectory("session-capture-coord-").toFile()
    logsRepo = LogsRepo(logsDir = tempDir, watchFileSystem = false)
  }

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  // --- Helpers -----------------------------------------------------------------

  private class FakeStream(
    override val type: CaptureType = CaptureType.VIDEO,
    val throwOnStart: Boolean = false,
  ) : CaptureStream {
    val startCalls = AtomicInteger(0)
    val stopCalls = AtomicInteger(0)
    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      startCalls.incrementAndGet()
      if (throwOnStart) throw RuntimeException("simulated startAll failure")
    }
    override fun stop(options: CaptureOptions): CaptureArtifact? {
      stopCalls.incrementAndGet()
      return null
    }
  }

  /**
   * A stream whose `stop()` throws — used to pin the tombstone path in
   * `stopForSession`. Unlike `FakeStream(throwOnStart=true)`, this throw DOES escape
   * to the coordinator because `CaptureSession.stopAll()` rethrows the last per-stream
   * exception after calling stop on every stream.
   */
  private class ThrowOnStopStream(
    override val type: CaptureType = CaptureType.VIDEO,
  ) : CaptureStream {
    val startCalls = AtomicInteger(0)
    val stopCalls = AtomicInteger(0)
    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      startCalls.incrementAndGet()
    }
    override fun stop(options: CaptureOptions): CaptureArtifact? {
      stopCalls.incrementAndGet()
      throw RuntimeException("simulated stopAll failure — process kill timed out")
    }
  }

  private fun coordinatorWith(
    fakeStream: FakeStream = FakeStream(),
    factory: (CaptureOptions, TrailblazeDevicePlatform) -> CaptureSession? = { opts, _ ->
      CaptureSession(listOf(fakeStream), opts)
    },
  ): Pair<SessionCaptureCoordinator, FakeStream> {
    val coord = SessionCaptureCoordinator(
      logsRepo = logsRepo,
      captureSessionFactory = factory,
    )
    return coord to fakeStream
  }

  private fun sessionId(id: String = "test-session"): SessionId = SessionId(id)

  // --- Tests -------------------------------------------------------------------

  @Test
  fun `startForSession returns true the first time and registers in active map`() {
    val (coord, stream) = coordinatorWith()
    val id = sessionId()
    val started = coord.startForSession(id, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
    assertTrue(started, "first call should return true")
    assertTrue(coord.isActive(id), "session should be active after start")
    assertEquals(1, stream.startCalls.get(), "stream.start should fire exactly once")
  }

  @Test
  fun `startForSession is idempotent — second call for same id is a no-op`() {
    val (coord, stream) = coordinatorWith()
    val id = sessionId()
    assertTrue(coord.startForSession(id, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions()))
    val second = coord.startForSession(id, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
    assertFalse(second, "second call should return false")
    assertEquals(1, stream.startCalls.get(), "stream.start should only fire on the first call")
  }

  @Test
  fun `startForSession skips WEB platform without touching the registry`() {
    val factoryCalls = AtomicInteger(0)
    val (coord, _) = coordinatorWith(factory = { opts, platform ->
      factoryCalls.incrementAndGet()
      CaptureSession(listOf(FakeStream()), opts)
    })
    val id = sessionId()
    val started = coord.startForSession(id, "playwright-native", TrailblazeDevicePlatform.WEB, CaptureOptions())
    assertFalse(started, "WEB should short-circuit before the factory")
    assertFalse(coord.isActive(id), "WEB should NOT mark the session active")
    assertEquals(0, factoryCalls.get(), "factory shouldn't even be called for WEB")
  }

  @Test
  fun `startForSession returns false cleanly when fromOptions returns null`() {
    // Mirrors iOS today (`CaptureSession.fromOptions` returns null because the iOS
    // branch is commented out) and the "unknown platform" / disabled-by-config case.
    val (coord, _) = coordinatorWith(factory = { _, _ -> null })
    val id = sessionId()
    val started = coord.startForSession(id, "device-x", TrailblazeDevicePlatform.IOS, CaptureOptions())
    assertFalse(started)
    assertFalse(coord.isActive(id))
  }

  @Test
  fun `stopForSession returns false for an unknown sessionId`() {
    val (coord, stream) = coordinatorWith()
    val stopped = coord.stopForSession(sessionId("never-started"))
    assertFalse(stopped)
    assertEquals(0, stream.stopCalls.get())
  }

  @Test
  fun `stopForSession returns true and stops the stream for an active session`() {
    val (coord, stream) = coordinatorWith()
    val id = sessionId()
    coord.startForSession(id, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
    val stopped = coord.stopForSession(id)
    assertTrue(stopped)
    assertEquals(1, stream.stopCalls.get(), "stream.stop should fire exactly once")
    assertFalse(coord.isActive(id), "session should no longer be active after stop")
  }

  @Test
  fun `stopForSession is idempotent — second call returns false without re-stopping streams`() {
    val (coord, stream) = coordinatorWith()
    val id = sessionId()
    coord.startForSession(id, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
    assertTrue(coord.stopForSession(id), "first stop wins")
    assertFalse(coord.stopForSession(id), "second stop returns false")
    assertEquals(1, stream.stopCalls.get(), "stream.stop should only fire once")
  }

  /** A recorder that writes its recording into the session dir the moment it starts, numbered. */
  private class NumberedRecordingStream : CaptureStream {
    override val type: CaptureType = CaptureType.VIDEO
    val starts = AtomicInteger(0)
    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      File(sessionDir, "video.webm").writeText("recording #${starts.incrementAndGet()}")
    }
    override fun stop(options: CaptureOptions): CaptureArtifact? = null
  }

  @Test
  fun `a session whose capture was stopped never records again`() {
    // A host run's cleanup stops capture as it releases the device, and the runner then fires its
    // post-run capture start for the same session. Accepting that start recorded a one-second clip
    // at teardown that replaced the session's real recording.
    val stream = NumberedRecordingStream()
    val (coord, _) = coordinatorWith(factory = { opts, _ -> CaptureSession(listOf(stream), opts) })
    val id = sessionId()
    assertTrue(coord.startForSession(id, "ios-1", TrailblazeDevicePlatform.IOS, CaptureOptions()))
    assertTrue(coord.stopForSession(id))

    val lateStart = coord.startForSession(id, "ios-1", TrailblazeDevicePlatform.IOS, CaptureOptions())

    assertFalse(lateStart, "a start after the session's stop is refused")
    assertFalse(coord.isActive(id), "and leaves nothing recording")
    assertEquals(
      "recording #1",
      File(logsRepo.getSessionDir(id), "video.webm").readText(),
      "the session keeps the recording it made while it ran",
    )
  }

  @Test
  fun `a session stopped before its capture started never records`() {
    // The device manager creates a session and only then asks for its capture. A session that ends
    // in that gap finds nothing to stop, and the start that then arrives would record after it ended.
    val (coord, stream) = coordinatorWith()
    val id = sessionId()
    assertFalse(coord.stopForSession(id), "nothing was recording yet")

    val lateStart = coord.startForSession(id, "ios-1", TrailblazeDevicePlatform.IOS, CaptureOptions())

    assertFalse(lateStart, "a start after the session's stop is refused")
    assertFalse(coord.isActive(id), "and leaves nothing recording")
    assertEquals(0, stream.startCalls.get(), "no recorder was started")
  }

  @Test
  fun `a stop that only guessed at its session leaves that session free to record`() {
    // A run that ends without its session id guesses one, and the guess can be a concurrent run's
    // session on another device whose capture has not started yet. That run must still record.
    val (coord, stream) = coordinatorWith()
    val id = sessionId()
    assertFalse(coord.stopForSession(id, markEnded = false), "nothing was recording yet")

    assertTrue(
      coord.startForSession(id, "ios-1", TrailblazeDevicePlatform.IOS, CaptureOptions()),
      "the session's own capture start still records",
    )
    assertEquals(1, stream.startCalls.get())
  }

  @Test
  fun `a stopped session does not stop other sessions from recording`() {
    val (coord, stream) = coordinatorWith()
    val first = sessionId("first")
    assertTrue(coord.startForSession(first, "ios-1", TrailblazeDevicePlatform.IOS, CaptureOptions()))
    assertTrue(coord.stopForSession(first))

    assertTrue(
      coord.startForSession(sessionId("second"), "ios-1", TrailblazeDevicePlatform.IOS, CaptureOptions()),
      "the next session on the same device records as usual",
    )
    assertEquals(2, stream.startCalls.get())
  }

  @Test
  fun `concurrent startForSession calls for the same id result in exactly one start`() {
    // Pins the race fix — two threads calling startForSession with the same id at
    // the same time must not both spawn a subprocess. The check is twofold: the
    // shared FakeStream sees exactly one start call, and exactly one of the
    // returned-true paths wins.
    val stream = FakeStream()
    val (coord, _) = coordinatorWith(stream)
    val id = sessionId()
    val threads = 8
    val pool = Executors.newFixedThreadPool(threads)
    val gate = CountDownLatch(1)
    val results = mutableListOf<Boolean>()
    val resultsLock = Any()
    val done = CountDownLatch(threads)
    repeat(threads) {
      pool.submit {
        try {
          gate.await()
          val ok = coord.startForSession(id, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
          synchronized(resultsLock) { results.add(ok) }
        } finally {
          done.countDown()
        }
      }
    }
    gate.countDown()
    assertTrue(done.await(5, TimeUnit.SECONDS), "all submissions should complete")
    pool.shutdownNow()
    assertEquals(1, results.count { it }, "exactly one caller should report a fresh start")
    assertEquals(1, stream.startCalls.get(), "underlying stream should start exactly once")
    assertTrue(coord.isActive(id))
  }

  // Note on exception-cleanup paths in `SessionCaptureCoordinator`:
  //
  // `CaptureSession.startAll` and `CaptureSession.stopAll` both swallow per-stream
  // exceptions internally (see CaptureSession.kt:23-25 and :40-42) — neither
  // rethrows. As a result, the coordinator's `try { startAll } catch` block in Step 4
  // and the `try { stopAll } catch + tombstone` block in [stopForSession] are
  // defense-in-depth that never fires under the current CaptureSession contract.
  // They exist so that if `CaptureSession` is ever changed to propagate failures
  // (the more honest design — a coordinator can't react to a failed capture if it
  // never hears about it), the coordinator already does the right thing.
  //
  // Because both code paths are unreachable through the public CaptureSession API,
  // they have no test here. The `FakeStream(throwOnStart=true)` constructor and the
  // `ThrowOnStopStream` helper below stay in the test file so the tests are easy to
  // add the day someone makes CaptureSession propagate exceptions.

  @Test
  fun `startForSession returns false cleanly when sessionDir cannot be created`() {
    // mkdirs() returns false when the target path collides with a regular file.
    // Pins the guard added so a disk-full / permission-denied / path-collision case
    // surfaces a clean false instead of letting `startAll` proceed on a missing dir.
    //
    // Strategy: pre-create a *file* at the exact path `LogsRepo.getSessionDir` will
    // return BEFORE calling `startForSession`. `LogsRepo.getSessionDir` only calls
    // `mkdirs()` if the path doesn't already exist (LogsRepo.kt:306), so the file
    // survives and the coordinator's own `isDirectory || mkdirs` guard sees the
    // collision and bails.
    val collidingId = sessionId("colliding")
    val collidingPath = File(tempDir, collidingId.value)
    collidingPath.writeText("not-a-dir") // path is now a regular file
    val (coord, stream) = coordinatorWith()
    val started = coord.startForSession(collidingId, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
    assertFalse(started, "mkdir collision should produce a clean false")
    assertFalse(coord.isActive(collidingId))
    assertEquals(0, stream.startCalls.get(), "stream.start should not run when sessionDir creation fails")
  }

  @Test
  fun `shutdownAll stops every still-active session`() {
    val stream1 = FakeStream()
    val stream2 = FakeStream()
    val coord = SessionCaptureCoordinator(
      logsRepo = logsRepo,
      captureSessionFactory = { opts, _ ->
        // Returns a different fake-backed CaptureSession on each call so each
        // session has its own stream we can assert against.
        val pickedStream = if (coordAlternator.getAndIncrement() % 2 == 0) stream1 else stream2
        CaptureSession(listOf(pickedStream), opts)
      },
    )
    val a = sessionId("alpha")
    val b = sessionId("beta")
    coord.startForSession(a, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
    coord.startForSession(b, "android-2", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
    coord.shutdownAll()
    assertFalse(coord.isActive(a))
    assertFalse(coord.isActive(b))
    assertEquals(1, stream1.stopCalls.get())
    assertEquals(1, stream2.stopCalls.get())
  }

  private val coordAlternator = AtomicInteger(0)

  @Test
  fun `a coordinator with no capture running is not on the tool-dispatch path`() {
    // The observer registry holds by identity for the life of the JVM, so registering in the
    // constructor puts every coordinator ever built on the dispatch thread forever — harmless for
    // one daemon-lifetime instance, a leak the moment one is built per run or per test.
    // Registration follows the captures instead: with nothing to forward to, nothing listens.
    // Counted as a delta: this registry is process-global, so being the only registrant is not
    // something a unit test in a shared JVM can assume.
    val before = ToolCallObservers.registeredCount
    val coord = SessionCaptureCoordinator(
      logsRepo = logsRepo,
      captureSessionFactory = { opts, _ -> CaptureSession(listOf(FakeStream()), opts) },
    )
    assertEquals(before, ToolCallObservers.registeredCount, "constructing a coordinator registers nothing")

    val id = sessionId("observer-lifecycle")
    coord.startForSession(id, "android-1", TrailblazeDevicePlatform.ANDROID, CaptureOptions())
    assertEquals(before + 1, ToolCallObservers.registeredCount, "a running capture listens for tool calls")

    coord.stopForSession(id)
    assertEquals(before, ToolCallObservers.registeredCount, "and stops listening once its last capture ends")
  }

  // --- Tool-call routing --------------------------------------------------------

  private class ToolCallRecordingStream : CaptureStream, ToolCallAwareCaptureStream {
    val calls = mutableListOf<String>()
    override val type: CaptureType = CaptureType.MEMORY
    override fun start(sessionDir: File, deviceId: String, appId: String?) = Unit
    override fun stop(options: CaptureOptions): CaptureArtifact? = null
    override fun onToolCall(phase: ToolCallPhase, toolName: String, traceId: String?) {
      calls += "${phase.name} $toolName $traceId"
    }
  }

  @Test
  fun `tool calls for a started session reach its capture, and stop after the session ends`() {
    val stream = ToolCallRecordingStream()
    val coordinator = SessionCaptureCoordinator(logsRepo) { options, _ -> CaptureSession(listOf(stream), options) }
    try {
      val session = SessionId("routed-session")
      val other = SessionId("some-other-session")
      val trace = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL)
      // Before the session's capture starts nothing is routed: the loop may already be dispatching
      // pre-actions while the capture is still being reserved.
      ToolCallObservers.notifyBefore(session, "launchApp", trace)
      assertTrue(coordinator.startForSession(session, "emulator-5554", TrailblazeDevicePlatform.ANDROID, CaptureOptions()))
      ToolCallObservers.notifyBefore(session, "tapOnElement", trace)
      ToolCallObservers.notifyAfter(session, "tapOnElement", trace)
      ToolCallObservers.notifyBefore(other, "tapOnElement", trace)
      assertTrue(coordinator.stopForSession(session))
      ToolCallObservers.notifyAfter(session, "tapOnElement", trace)
      // The trace comes through as the plain id the stream writes into its rows.
      assertEquals(
        listOf("BEFORE tapOnElement ${trace.traceId}", "AFTER tapOnElement ${trace.traceId}"),
        stream.calls,
      )
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun `a closed coordinator no longer listens for tool calls`() {
    val stream = ToolCallRecordingStream()
    val coordinator = SessionCaptureCoordinator(logsRepo) { options, _ -> CaptureSession(listOf(stream), options) }
    val session = SessionId("closed-coordinator")
    assertTrue(coordinator.startForSession(session, "emulator-5554", TrailblazeDevicePlatform.ANDROID, CaptureOptions()))
    coordinator.close()
    ToolCallObservers.notifyBefore(session, "tapOnElement", traceId = null)
    assertTrue(stream.calls.isEmpty())
    coordinator.stopForSession(session)
  }

  // --- Multi-device sessions ----------------------------------------------------

  /**
   * A recorder that leaves a real, non-empty file under [basename] and reports it, so the merged
   * `capture_metadata.json` the coordinator writes can be read back and checked device by device.
   */
  private class RecordingStream(private val basename: String) : CaptureStream {
    override val type: CaptureType = CaptureType.VIDEO_WEBM
    val devicesStarted = mutableListOf<String>()
    val stopCalls = AtomicInteger(0)
    private var file: File? = null
    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      devicesStarted += deviceId
      file = File(sessionDir, "$basename.webm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }
    override fun stop(options: CaptureOptions): CaptureArtifact? {
      stopCalls.incrementAndGet()
      return file?.let { CaptureArtifact(it, type, startTimestampMs = 1_000, endTimestampMs = 2_000) }
    }
  }

  private val videoOn = CaptureOptions(captureVideo = true)
  private val seller = SessionCaptureCoordinator.BoundDevice("seller", TrailblazeDeviceId("emulator-5560", TrailblazeDevicePlatform.ANDROID))
  private val buyer = SessionCaptureCoordinator.BoundDevice("buyer", TrailblazeDeviceId("emulator-5562", TrailblazeDevicePlatform.ANDROID))

  /** A coordinator whose primary records `video.webm` and whose companions each record under the basename they are given. */
  private fun multiDeviceCoordinator(
    primary: RecordingStream = RecordingStream("video"),
    companions: MutableMap<String, RecordingStream> = linkedMapOf(),
    companionFactory: (CaptureOptions, TrailblazeDevicePlatform, String) -> CaptureSession? = { options, _, basename ->
      CaptureSession(listOf(RecordingStream(basename).also { companions[basename] = it }), options)
    },
  ): SessionCaptureCoordinator = SessionCaptureCoordinator(
    logsRepo = logsRepo,
    captureSessionFactory = { options, _ -> CaptureSession(listOf(primary), options) },
    companionCaptureFactory = companionFactory,
  )

  private fun metadataEntries(id: SessionId): List<Map<String, String?>> {
    val text = File(logsRepo.getSessionDir(id), "capture_metadata.json").readText()
    val artifacts = kotlinx.serialization.json.Json.parseToJsonElement(text)
      .jsonObject.getValue("artifacts").jsonArray
    return artifacts.map { entry ->
      entry.jsonObject.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    }
  }

  @Test
  fun `every companion display of a multi-device session is recorded into its own file`() {
    val companions = linkedMapOf<String, RecordingStream>()
    val coord = multiDeviceCoordinator(companions = companions)
    val id = sessionId("x2-pair")
    assertTrue(coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn))

    assertEquals(1, coord.bindDevices(id, listOf(seller, buyer)), "the start device is already recording; only the buyer starts")

    val buyerRecorder = assertNotNull(companions["video-buyer"], "the companion records under its own basename")
    assertEquals(listOf("emulator-5562"), buyerRecorder.devicesStarted, "and it is pointed at the companion's device")
    assertTrue(coord.stopForSession(id))
    assertEquals(1, buyerRecorder.stopCalls.get(), "ending the session stops the companion too")

    val entries = metadataEntries(id)
    assertEquals<Map<String?, String?>>(
      mapOf("video.webm" to "seller", "video-buyer.webm" to "buyer"),
      entries.associate { it.getValue("filename") to it["deviceName"] },
      "one metadata file names every recording's device, the start device's included",
    )
    assertEquals<Map<String?, String?>>(
      mapOf("video.webm" to "emulator-5560", "video-buyer.webm" to "emulator-5562"),
      entries.associate { it.getValue("filename") to it["deviceId"] },
    )
  }

  @Test
  fun `re-sending the roster does not start a second recording of a companion`() {
    val companions = linkedMapOf<String, RecordingStream>()
    val coord = multiDeviceCoordinator(companions = companions)
    val id = sessionId("rebind")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
    assertEquals(1, coord.bindDevices(id, listOf(seller, buyer)))
    assertEquals(0, coord.bindDevices(id, listOf(seller, buyer)), "the buyer is already recording")
    assertEquals(1, companions.size)
    assertEquals(1, companions.getValue("video-buyer").devicesStarted.size)
    coord.stopForSession(id)
  }

  @Test
  fun `companions are not recorded when the session itself records no video`() {
    val companions = linkedMapOf<String, RecordingStream>()
    val coord = multiDeviceCoordinator(companions = companions)
    val id = sessionId("video-off")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, CaptureOptions(captureVideo = false))
    assertEquals(0, coord.bindDevices(id, listOf(seller, buyer)))
    assertTrue(companions.isEmpty(), "a session that opted out of video has not asked for footage of any display")
    coord.stopForSession(id)
  }

  private val dashboard =
    SessionCaptureCoordinator.BoundDevice("dashboard", TrailblazeDeviceId("web-dashboard", TrailblazeDevicePlatform.WEB))

  @Test
  fun `a web companion is recorded under its own name`() {
    val companions = linkedMapOf<String, RecordingStream>()
    val platforms = mutableListOf<TrailblazeDevicePlatform>()
    val coord = multiDeviceCoordinator(
      companions = companions,
      companionFactory = { options, platform, basename ->
        platforms += platform
        CaptureSession(listOf(RecordingStream(basename).also { companions[basename] = it }), options)
      },
    )
    val id = sessionId("web-companion")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
    assertEquals(1, coord.bindDevices(id, listOf(seller, dashboard)))
    assertEquals(listOf(TrailblazeDevicePlatform.WEB), platforms, "the browser gets a recorder of its own platform")
    assertEquals(listOf("web-dashboard"), companions.getValue("video-dashboard").devicesStarted)
    assertTrue(coord.stopForSession(id))

    assertEquals<Map<String?, String?>>(
      mapOf("video.webm" to "seller", "video-dashboard.webm" to "dashboard"),
      metadataEntries(id).associate { it.getValue("filename") to it["deviceName"] },
      "the report finds the browser's recording by the name the configuration gave it",
    )
  }

  /** A browser's screencast as the Playwright manager publishes it, counting who is attached. */
  private class FakeScreencastFeed : WebScreencastFeedRegistry.Feed {
    val subscribers = AtomicInteger(0)
    override fun subscribe(onFrame: (jpeg: ByteArray, hostTimestampMs: Long) -> Unit): AutoCloseable {
      subscribers.incrementAndGet()
      return AutoCloseable { subscribers.decrementAndGet() }
    }
  }

  @Test
  fun `a web companion records its browser's screencast and never asks Playwright to record`() {
    // The shipped companion recorder, not a fake. Playwright's own recorder is switched on by
    // publishing a record dir, which makes the browser manager rebuild the context the trail is
    // driving — the wedge a web session's own capture is skipped to avoid. A companion whose
    // browser is not up yet at bind is exactly when a web recorder would reach for it.
    val browser = dashboard.deviceId.instanceId
    val coord = SessionCaptureCoordinator(
      logsRepo = logsRepo,
      captureSessionFactory = { options, _ -> CaptureSession(listOf(RecordingStream("video")), options) },
    )
    val id = sessionId("web-companion-default")
    val feed = FakeScreencastFeed()
    try {
      coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
      assertEquals(1, coord.bindDevices(id, listOf(seller, dashboard)))
      assertNull(PlaywrightVideoRecordDir.getRecordDir(browser), "binding must not turn on Playwright's recorder")

      WebScreencastFeedRegistry.register(browser, feed)
      assertEquals(1, feed.subscribers.get(), "the browser is recorded from its screencast once it comes up")

      assertTrue(coord.stopForSession(id))
      assertEquals(0, feed.subscribers.get(), "ending the session detaches from the screencast")
      assertNull(PlaywrightVideoRecordDir.getRecordDir(browser))
    } finally {
      // A failed assertion above must not leave the companion watching this id for later tests.
      runCatching { coord.stopForSession(id) }
      WebScreencastFeedRegistry.unregister(browser, feed)
      PlaywrightVideoRecordDir.clearRecordDir(browser)
    }
  }

  @Test
  fun `a roster sent to a session with no capture records nothing`() {
    val coord = multiDeviceCoordinator()
    assertEquals(0, coord.bindDevices(sessionId("never-started"), listOf(seller, buyer)))
  }

  @Test
  fun `unbinding a companion stops its recording and keeps what it captured`() {
    val companions = linkedMapOf<String, RecordingStream>()
    val coord = multiDeviceCoordinator(companions = companions)
    val id = sessionId("unbind")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
    coord.bindDevices(id, listOf(seller, buyer))

    assertTrue(coord.unbindDevice(id, "buyer"))
    assertEquals(1, companions.getValue("video-buyer").stopCalls.get(), "unbinding stops the recorder then")
    assertFalse(coord.unbindDevice(id, "buyer"), "and a second unbind finds nothing recording")
    assertFalse(coord.unbindDevice(id, "seller"), "the start device is not a companion")

    coord.stopForSession(id)
    assertEquals(1, companions.getValue("video-buyer").stopCalls.get(), "session end does not stop it again")
    assertEquals(
      setOf("video.webm", "video-buyer.webm"),
      metadataEntries(id).map { it.getValue("filename") }.toSet(),
      "the footage a companion captured before it was unbound is still on record",
    )
  }

  @Test
  fun `two device names that reduce to one filename each keep their own recording`() {
    val companions = linkedMapOf<String, RecordingStream>()
    val coord = multiDeviceCoordinator(companions = companions)
    val id = sessionId("colliding-names")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
    val front = SessionCaptureCoordinator.BoundDevice("kiosk/front", TrailblazeDeviceId("emulator-5564", TrailblazeDevicePlatform.ANDROID))
    val front2 = SessionCaptureCoordinator.BoundDevice("kiosk:front", TrailblazeDeviceId("emulator-5566", TrailblazeDevicePlatform.ANDROID))

    assertEquals(2, coord.bindDevices(id, listOf(seller, front, front2)))
    assertEquals(setOf("video-kiosk_front", "video-kiosk_front-2"), companions.keys, "the second device must not write the first one's file")
    coord.stopForSession(id)

    assertEquals<Map<String?, String?>>(
      mapOf("video.webm" to "seller", "video-kiosk_front.webm" to "kiosk/front", "video-kiosk_front-2.webm" to "kiosk:front"),
      metadataEntries(id).associate { it.getValue("filename") to it["deviceName"] },
    )
  }

  @Test
  fun `two device names that differ only in case each keep their own recording`() {
    // The default macOS filesystem is case-insensitive: `video-Buyer.webm` would overwrite `video-buyer.webm`.
    val companions = linkedMapOf<String, RecordingStream>()
    val coord = multiDeviceCoordinator(companions = companions)
    val id = sessionId("case-only-names")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
    val upper = SessionCaptureCoordinator.BoundDevice("Buyer", TrailblazeDeviceId("emulator-5564", TrailblazeDevicePlatform.ANDROID))

    assertEquals(2, coord.bindDevices(id, listOf(seller, buyer, upper)))
    assertEquals(setOf("video-buyer", "video-Buyer-2"), companions.keys)
    coord.stopForSession(id)
  }

  @Test
  fun `a companion unbound while its recorder is starting is not left recording`() {
    val startEntered = CountDownLatch(1)
    val releaseStart = CountDownLatch(1)
    val companions = linkedMapOf<String, RecordingStream>()
    val coord = multiDeviceCoordinator(companionFactory = { options, _, basename ->
      val recording = RecordingStream(basename).also { companions[basename] = it }
      val slowStart = object : CaptureStream by recording {
        override fun start(sessionDir: File, deviceId: String, appId: String?) {
          startEntered.countDown()
          check(releaseStart.await(10, TimeUnit.SECONDS)) { "the test never released the companion's start" }
          recording.start(sessionDir, deviceId, appId)
        }
      }
      CaptureSession(listOf(slowStart), options)
    })
    val id = sessionId("unbind-mid-start")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)

    val binding = Executors.newSingleThreadExecutor()
    try {
      val bound = binding.submit<Int> { coord.bindDevices(id, listOf(seller, buyer)) }
      assertTrue(startEntered.await(10, TimeUnit.SECONDS), "the bind is inside the companion's start")
      coord.unbindDevice(id, "buyer")
      releaseStart.countDown()
      assertEquals(0, bound.get(10, TimeUnit.SECONDS), "the start finds the buyer unbound")
    } finally {
      releaseStart.countDown()
      binding.shutdownNow()
    }
    assertEquals(1, companions.getValue("video-buyer").stopCalls.get(), "its recorder was stopped as soon as it started")
    coord.stopForSession(id)
    assertEquals(1, companions.getValue("video-buyer").stopCalls.get(), "and is not stopped a second time at session end")
  }

  @Test
  fun `a companion bound again after an unbind records into a new file`() {
    val companions = linkedMapOf<String, RecordingStream>()
    val coord = multiDeviceCoordinator(companions = companions)
    val id = sessionId("rebind-after-unbind")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
    coord.bindDevices(id, listOf(seller, buyer))
    coord.unbindDevice(id, "buyer")

    assertEquals(1, coord.bindDevices(id, listOf(seller, buyer)))
    assertEquals(listOf("video-buyer", "video-buyer-2"), companions.keys.toList(), "the first stretch of footage is not truncated by the second")
    coord.stopForSession(id)

    assertEquals(
      setOf("video-buyer.webm", "video-buyer-2.webm"),
      metadataEntries(id).filter { it["deviceName"] == "buyer" }.mapNotNull { it["filename"] }.toSet(),
      "both stretches of the buyer's footage are on record",
    )
  }

  @Test
  fun `a companion whose unbind finishes after the session ended is still on record`() {
    val stopEntered = CountDownLatch(1)
    val releaseStop = CountDownLatch(1)
    val coord = multiDeviceCoordinator(companionFactory = { options, _, basename ->
      val recording = RecordingStream(basename)
      val slowStop = object : CaptureStream by recording {
        override fun stop(options: CaptureOptions): CaptureArtifact? {
          stopEntered.countDown()
          check(releaseStop.await(10, TimeUnit.SECONDS)) { "the test never released the companion's stop" }
          return recording.stop(options)
        }
      }
      CaptureSession(listOf(slowStop), options)
    })
    val id = sessionId("late-unbind")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
    coord.bindDevices(id, listOf(seller, buyer))

    val unbinding = Executors.newSingleThreadExecutor()
    try {
      val unbound = unbinding.submit<Boolean> { coord.unbindDevice(id, "buyer") }
      assertTrue(stopEntered.await(10, TimeUnit.SECONDS), "the unbind is inside the companion's stop")
      // The session ends while the buyer is still stopping: the metadata goes out without it.
      assertTrue(coord.stopForSession(id))
      assertEquals(listOf("video.webm"), metadataEntries(id).map { it.getValue("filename") })

      releaseStop.countDown()
      assertTrue(unbound.get(10, TimeUnit.SECONDS))
    } finally {
      releaseStop.countDown()
      unbinding.shutdownNow()
    }
    assertEquals(
      setOf("video.webm", "video-buyer.webm"),
      metadataEntries(id).map { it.getValue("filename") }.toSet(),
      "the late recording is added once its stop finishes",
    )
  }

  @Test
  fun `a companion whose recorder fails to start is dropped without taking the session down`() {
    val coord = multiDeviceCoordinator(companionFactory = { options, _, _ ->
      CaptureSession(listOf(FakeStream(throwOnStart = true)), options)
    })
    val id = sessionId("companion-start-fails")
    coord.startForSession(id, seller.deviceId.instanceId, TrailblazeDevicePlatform.ANDROID, videoOn)
    // CaptureSession.startAll swallows per-stream failures, so this exercises the path where the
    // companion starts "successfully" but records nothing; the session still ends cleanly with
    // only the start device on record.
    coord.bindDevices(id, listOf(seller, buyer))
    assertTrue(coord.stopForSession(id))
    assertEquals(listOf("video.webm"), metadataEntries(id).map { it.getValue("filename") })
  }
}
