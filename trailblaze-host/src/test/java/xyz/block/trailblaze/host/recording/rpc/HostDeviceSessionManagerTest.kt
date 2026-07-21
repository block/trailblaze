package xyz.block.trailblaze.host.recording.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.recording.DeviceScreenStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [HostDeviceSessionManager.attach] / [detach] — the bridge Trail Runner's recorder uses to
 * publish its already-open connection into the shared registry so `/devices/api/stream`, the
 * `/rpc-ws` frame subscription, and `GetHostDeviceScreenRequest` can all reach it.
 *
 * The contract that matters: `attach` makes the stream visible (`get` non-null), and `detach` removes
 * it **without closing it** — the recorder owns the lifecycle and closes it itself. `remove` (the
 * viewer-owned path) is the one that closes. A regression that closed on detach would tear the
 * recorder's own stream out from under it (double-close / dead mirror mid-recording).
 */
class HostDeviceSessionManagerTest {

  private val deviceId = TrailblazeDeviceId("emulator-test", TrailblazeDevicePlatform.ANDROID)

  /** Minimal stream that records whether it was closed, to prove detach vs. remove behavior. */
  private class ClosableStubStream : DeviceScreenStream, AutoCloseable {
    var closed = false
      private set

    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override fun frames(): Flow<ByteArray> = emptyFlow()
    override suspend fun tap(x: Int, y: Int) {}
    override suspend fun longPress(x: Int, y: Int) {}
    override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long?) {}
    override suspend fun inputText(text: String) {}
    override suspend fun pressKey(key: String) {}
    override suspend fun getViewHierarchy(): ViewHierarchyTreeNode = error("not used")
    override suspend fun getTrailblazeNodeTree(): TrailblazeNode? = null
    override suspend fun getScreenshot(): ByteArray = ByteArray(0)
    override suspend fun getMirrorScreenshot(): ByteArray = ByteArray(0)
    override fun close() { closed = true }
  }

  @Test
  fun `attach makes the externally-owned stream visible`() {
    val manager = HostDeviceSessionManager()
    val stream = ClosableStubStream()
    assertFalse(manager.isConnected(deviceId))

    manager.attach(deviceId, stream)

    assertTrue(manager.isConnected(deviceId))
    assertSame(stream, manager.get(deviceId), "get must return the attached stream instance")
  }

  @Test
  fun `detach removes the stream without closing it`() {
    val manager = HostDeviceSessionManager()
    val stream = ClosableStubStream()
    manager.attach(deviceId, stream)

    manager.detach(deviceId)

    assertNull(manager.get(deviceId), "detach must remove the entry")
    assertFalse(stream.closed, "detach must NOT close the caller-owned stream")
  }

  @Test
  fun `remove closes a manager-owned stream (connectIfAbsent path)`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val stream = ClosableStubStream()
    manager.connectIfAbsent(deviceId) { stream }

    manager.remove(deviceId)

    assertNull(manager.get(deviceId))
    assertTrue(stream.closed, "remove owns connectIfAbsent-created streams and must close them")
  }

  @Test
  fun `remove drops an externally-owned entry without closing it`() {
    val manager = HostDeviceSessionManager()
    val stream = ClosableStubStream()
    manager.attach(deviceId, stream)

    manager.remove(deviceId)

    assertNull(manager.get(deviceId), "remove must drop the entry")
    assertFalse(stream.closed, "remove must NOT close a stream the recorder owns — it re-attaches on its next connect")
  }

  @Test
  fun `detach on an unknown device is a no-op`() {
    val manager = HostDeviceSessionManager()
    manager.detach(deviceId) // must not throw
    assertFalse(manager.isConnected(deviceId))
  }

  @Test
  fun `attach does not clobber an existing session`() {
    val manager = HostDeviceSessionManager()
    val first = ClosableStubStream()
    val second = ClosableStubStream()
    manager.attach(deviceId, first)

    manager.attach(deviceId, second)

    assertSame(first, manager.get(deviceId), "the existing session wins — clobbering it would leak the displaced stream")
    assertFalse(first.closed)
    assertFalse(second.closed)
  }

  @Test
  fun `attach and detach leave a viewer-owned session untouched`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val viewerOwned = ClosableStubStream()
    manager.connectIfAbsent(deviceId) { viewerOwned }
    val recorderOwned = ClosableStubStream()

    manager.attach(deviceId, recorderOwned) // no-op: viewer session already registered
    manager.detach(deviceId) // no-op: the registered session isn't the recorder's

    assertSame(viewerOwned, manager.get(deviceId), "the viewer-owned session must survive a recorder attach/detach cycle")
    assertFalse(viewerOwned.closed)
  }

  @Test
  fun `attach wins when it publishes while connect is suspended`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val candidate = ClosableStubStream()
    val attached = ClosableStubStream()
    val connectStarted = CompletableDeferred<Unit>()
    val releaseConnect = CompletableDeferred<Unit>()
    val result = async {
      manager.connectIfAbsent(deviceId) {
        connectStarted.complete(Unit)
        releaseConnect.await()
        candidate
      }
    }

    connectStarted.await()
    manager.attach(deviceId, attached)
    releaseConnect.complete(Unit)

    assertSame(attached, result.await(), "the published recorder stream must remain registered")
    assertSame(attached, manager.get(deviceId))
    assertTrue(candidate.closed, "the losing viewer stream must not leak")
    assertFalse(attached.closed)
  }

  @Test
  fun `concurrent publication of the same stream does not close it`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val shared = ClosableStubStream()
    val connectStarted = CompletableDeferred<Unit>()
    val releaseConnect = CompletableDeferred<Unit>()
    val result = async {
      manager.connectIfAbsent(deviceId) {
        connectStarted.complete(Unit)
        releaseConnect.await()
        shared
      }
    }

    connectStarted.await()
    manager.attach(deviceId, shared)
    releaseConnect.complete(Unit)

    assertSame(shared, result.await())
    assertSame(shared, manager.get(deviceId))
    assertFalse(shared.closed, "the winning stream must stay open")
  }
}
