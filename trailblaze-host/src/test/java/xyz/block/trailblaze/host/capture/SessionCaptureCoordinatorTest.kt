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
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo

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
}
