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
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.FakeHostAppTarget
import xyz.block.trailblaze.host.recording.DeviceConnectionService.Companion.connectionBinding
import xyz.block.trailblaze.recording.DeviceScreenStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private typealias Binding = HostDeviceSessionManager.Binding

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

  /** Just the binding a holder is refusing on behalf of, for the cases that don't care who holds it. */
  private fun HostDeviceSessionManager.refusedBinding(
    deviceId: TrailblazeDeviceId,
    binding: Binding,
  ): Binding? = refusalFor(deviceId, binding)?.heldBy?.single()?.boundTo

  /** A refusal by a single holder, which is every case but the one that asserts two of them. */
  private fun heldBy(
    boundTo: Binding,
    holder: HostDeviceSessionManager.ConnectResult.Holder,
  ) = HostDeviceSessionManager.ConnectResult.BoundToOtherTarget(
    listOf(HostDeviceSessionManager.ConnectResult.BoundToOtherTarget.Held(boundTo, holder)),
  )

  /** The stream a [HostDeviceSessionManager.ConnectResult.Ready] handed back. */
  private fun HostDeviceSessionManager.ConnectResult.stream(): DeviceScreenStream =
    (this as HostDeviceSessionManager.ConnectResult.Ready).stream

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

    assertSame(attached, result.await().stream(), "the published recorder stream must remain registered")
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

    assertSame(shared, result.await().stream())
    assertSame(shared, manager.get(deviceId))
    assertFalse(shared.closed, "the winning stream must stay open")
  }

  // ─── Target binding ───
  //
  // A connect installs and launches the app it is bound to, so reusing one connection for another
  // target would drive one app while the caller reports the other.

  @Test
  fun `a session is reused for the target it was connected for`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val stream = ClosableStubStream()
    manager.connectIfAbsent(deviceId, Binding("app-a")) { stream }

    var reconnected = false
    val again = manager.connectIfAbsent(deviceId, Binding("app-a")) { reconnected = true; ClosableStubStream() }

    assertSame(stream, again.stream())
    assertFalse(reconnected, "the same target must not re-run the connect")
  }

  @Test
  fun `a session bound to another target is refused rather than reused`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val stream = ClosableStubStream()
    manager.connectIfAbsent(deviceId, Binding("app-a")) { stream }

    var reconnected = false
    val other = manager.connectIfAbsent(deviceId, Binding("app-b")) { reconnected = true; ClosableStubStream() }

    assertEquals(
      heldBy(Binding("app-a"), HostDeviceSessionManager.ConnectResult.Holder.VIEWER),
      other,
      "the refusal has to name the target the device IS bound to, so the caller can say what to release",
    )
    assertFalse(reconnected, "a refused connect must not touch the device")
    assertSame(stream, manager.get(deviceId), "the live session must survive the refusal")
    assertFalse(stream.closed)
  }

  @Test
  fun `a connect that names no target reuses whatever is live`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val stream = ClosableStubStream()
    manager.connectIfAbsent(deviceId, Binding("app-a")) { stream }

    // Naming nothing asks for nothing in particular, so there is nothing to conflict with.
    assertSame(stream, manager.connectIfAbsent(deviceId, Binding()) { ClosableStubStream() }.stream())
  }

  @Test
  fun `an attached session carries the target its owner opened it for`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val attached = ClosableStubStream()
    manager.attach(deviceId, attached, Binding("app-a"))

    // The recorder's own connection stays reachable for the target it opened...
    assertSame(attached, manager.connectIfAbsent(deviceId, Binding("app-a")) { ClosableStubStream() }.stream())
    // ...and is refused for any other, as the RECORDER: a disconnect only drops it from this
    // registry, so telling the caller to release it would have them open a second connection to a
    // device the recorder is still driving.
    assertEquals(
      heldBy(Binding("app-a"), HostDeviceSessionManager.ConnectResult.Holder.RECORDER),
      manager.connectIfAbsent(deviceId, Binding("app-b")) { ClosableStubStream() },
    )
  }

  @Test
  fun `a caller that opens the device itself can ask what the binding would refuse`() = runBlocking {
    // Trail Runner's recorder connects the device directly instead of through connectIfAbsent, so
    // the refusal above never runs for it. It has to ask first: its own attach is a no-op while
    // another session holds the device, so connecting anyway leaves the registry serving the other
    // stream - over a driver this connect may have just rebuilt underneath it.
    val manager = HostDeviceSessionManager()
    manager.connectIfAbsent(deviceId, Binding("app-a")) { ClosableStubStream() }

    assertEquals(Binding("app-a"), manager.refusedBinding(deviceId, Binding("app-b")))
    assertNull(manager.refusedBinding(deviceId, Binding("app-a")), "its own target is not a conflict")
    assertNull(manager.refusedBinding(deviceId, Binding()), "naming nothing contradicts nothing")
  }

  @Test
  fun `nothing conflicts on a device with no session, or one bound to no target`() = runBlocking {
    val manager = HostDeviceSessionManager()
    assertNull(manager.refusedBinding(deviceId, Binding("app-a")), "a free device refuses nobody")

    manager.attach(deviceId, ClosableStubStream())
    assertNull(
      manager.refusedBinding(deviceId, Binding("app-a")),
      "a session opened with no target drives no particular app, so it can't contradict one",
    )
  }

  @Test
  fun `an attach that names no target is never refused`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val attached = ClosableStubStream()
    manager.attach(deviceId, attached)

    // A connection opened with no target selected drives no particular app, so there is nothing
    // for a named connect to contradict - and refusing would make that stream unreachable.
    assertSame(attached, manager.connectIfAbsent(deviceId, Binding("app-a")) { ClosableStubStream() }.stream())
  }

  @Test
  fun `a connect that produces no stream is unavailable`() = runBlocking {
    val manager = HostDeviceSessionManager()

    assertEquals(
      HostDeviceSessionManager.ConnectResult.Unavailable,
      manager.connectIfAbsent(deviceId, Binding("app-a")) { null },
    )
    assertFalse(manager.isConnected(deviceId))
  }

  @Test
  fun `a released device can be bound to a different target`() = runBlocking {
    val manager = HostDeviceSessionManager()
    val first = ClosableStubStream()
    manager.connectIfAbsent(deviceId, Binding("app-a")) { first }
    manager.remove(deviceId)

    val second = ClosableStubStream()
    assertSame(second, manager.connectIfAbsent(deviceId, Binding("app-b")) { second }.stream())
    assertTrue(first.closed, "remove owns the earlier stream and must close it")
  }

  @Test
  fun `a custom-wrapper connect is refused by a session that named no target`() = runBlocking {
    // The case the target names alone can't see: neither side contradicts the other on WHICH app,
    // but the live session is running the plain base driver and this connect needs a wrapped one.
    // Sharing would drive the app unwrapped; connecting anyway would rebuild the driver the live
    // stream is running on.
    val manager = HostDeviceSessionManager()
    manager.connectIfAbsent(deviceId, Binding()) { ClosableStubStream() }

    assertEquals(
      heldBy(Binding(), HostDeviceSessionManager.ConnectResult.Holder.VIEWER),
      manager.connectIfAbsent(deviceId, Binding("square", "square")) { ClosableStubStream() },
    )
    assertEquals(
      Binding(),
      manager.refusedBinding(deviceId, Binding("square", "square")),
      "the recorder has to see this one too - it closes the driver itself",
    )
  }

  @Test
  fun `a targetless connect is refused by a session on a custom wrapper`() = runBlocking {
    // The same mismatch from the other side. A null target is a wildcard on the target axis, so
    // only the driver it would build stops this reuse.
    val manager = HostDeviceSessionManager()
    manager.connectIfAbsent(deviceId, Binding("square", "square")) { ClosableStubStream() }

    assertEquals(
      heldBy(Binding("square", "square"), HostDeviceSessionManager.ConnectResult.Holder.VIEWER),
      manager.connectIfAbsent(deviceId, Binding()) { ClosableStubStream() },
    )
  }

  @Test
  fun `a plain target still shares with a targetless connect`() = runBlocking {
    // The driver axis must not refuse a pair the driver cache would have shared: neither of these
    // has a custom wrapper, so both are running the identical base driver and a refusal would ask
    // for a reconnect that rebuilds exactly what is already there.
    //
    // Note this is the ONLY pair the driver axis lets through on a null. Two DIFFERENT plain
    // targets are still refused - by the target axis, which this does not change - even though they too
    // share a driver; that refusal is cheap precisely because the driver is reused, which
    // `HostIosDriverWrapperKeyTest.two plain targets share a driver` is what pins.
    val manager = HostDeviceSessionManager()
    val live = ClosableStubStream()
    manager.connectIfAbsent(deviceId, Binding("alpha")) { live }

    assertNull(manager.refusedBinding(deviceId, Binding()), "a targetless connect names nothing to contradict")
    assertSame(live, manager.connectIfAbsent(deviceId, Binding()) { ClosableStubStream() }.stream())

    // Guard the boundary the name above could be misread as covering.
    assertEquals(
      Binding("alpha"),
      manager.refusedBinding(deviceId, Binding("beta")),
      "two different plain targets are still refused, on the target axis",
    )
  }

  @Test
  fun `a claimed device refuses a connect on another driver, as the agent that holds it`() = runBlocking {
    // The MCP bridge holds the driver in its own map. A disconnect through this registry would not
    // take it away, so telling the caller to disconnect would send them round a loop that changes
    // nothing - so the refusal names the agent session as the holder, not a disconnect as the fix.
    val manager = HostDeviceSessionManager()

    manager.claim(deviceId, Binding("square", "square"))

    assertEquals(
      heldBy(Binding("square", "square"), HostDeviceSessionManager.ConnectResult.Holder.AGENT),
      manager.connectIfAbsent(deviceId, Binding()) { ClosableStubStream() },
    )
  }

  @Test
  fun `a claim is refused against without ever being connected`() {
    // A claim is not a session: it has no stream to serve, so the screen and interaction handlers
    // must still read this device as not connected. Anything else would hand them a null stream to
    // dereference, or make `/devices` advertise pixels nobody can produce.
    val manager = HostDeviceSessionManager()

    manager.claim(deviceId, Binding("square", "square"))

    assertNull(manager.get(deviceId), "a claim has no stream to serve")
    assertFalse(manager.isConnected(deviceId), "a claimed device is not a connected one")
    assertEquals(Binding("square", "square"), manager.refusedBinding(deviceId, Binding()))
  }

  @Test
  fun `a claim does not refuse a connect it agrees with, and a released one refuses nothing`() = runBlocking {
    // Two guards in one: the claim must not block the agent's own device from being viewed for the
    // same target, and releasing it on driver close must not leave the device permanently refused.
    val manager = HostDeviceSessionManager()
    manager.claim(deviceId, Binding("square", "square"))

    val agreeing = ClosableStubStream()
    assertNull(manager.refusedBinding(deviceId, Binding("square", "square")))
    assertSame(agreeing, manager.connectIfAbsent(deviceId, Binding("square", "square")) { agreeing }.stream())

    manager.remove(deviceId)
    manager.releaseClaim(deviceId)

    val fresh = ClosableStubStream()
    assertNull(manager.refusedBinding(deviceId, Binding()), "the released claim refuses nothing")
    assertSame(fresh, manager.connectIfAbsent(deviceId, Binding()) { fresh }.stream())
  }

  @Test
  fun `a session and a claim can hold one device, and either can refuse`() = runBlocking {
    // The reason a claim is its own map rather than an entry in `sessions`: the bridge and a viewer
    // genuinely both hold a device, so one slot could only ever record one of them. Whichever
    // holder conflicts is the one that refuses.
    val manager = HostDeviceSessionManager()
    manager.connectIfAbsent(deviceId, Binding("alpha")) { ClosableStubStream() }
    manager.claim(deviceId, Binding("square", "square"))

    assertEquals(
      Binding("square", "square"),
      manager.refusedBinding(deviceId, Binding("alpha")),
      "the claim refuses a connect the session alone would have shared with",
    )

    // A connect that contradicts BOTH has to hear about both, or the remedy it is handed isn't
    // enough: it disconnects the session, retries, and is refused again by a holder nobody named.
    val both = manager.refusalFor(deviceId, Binding("beta"))!!
    assertEquals(
      listOf(Binding("alpha"), Binding("square", "square")),
      both.heldBy.map { it.boundTo },
    )
    assertEquals(
      "${deviceId.toFullyQualifiedDeviceId()} is already connected for target 'alpha' and is being driven by an " +
        "agent session for target 'square'. Disconnect it and stop that session before connecting " +
        "it for target 'beta'.",
      both.explain(deviceId, Binding("beta"), action = "connecting"),
    )
  }

  @Test
  fun `a claim refuses on its driver alone, so it cannot go stale on the target`() = runBlocking {
    // The one axis a claim can trust. `selectAppTarget` keeps the MCP bridge's persistent driver
    // when the wrapper doesn't change, so after switching between two plain targets the claim's
    // TARGET names an app the agent stopped driving - refusing the target it moved to and waving
    // through the one it left. Its DRIVER key can't drift that way: it is null exactly when the
    // agent holds the plain driver, and a switch that would change it is a switch that closes the
    // driver and releases the claim.
    //
    // Refusing on the driver alone is also all a claim is for. Nothing is torn down when two plain
    // targets meet on one device - they share the driver - so a refusal there would charge a
    // reconnect to protect nothing.
    val manager = HostDeviceSessionManager()
    manager.claim(deviceId, Binding("plain-a"))

    assertNull(
      manager.refusalFor(deviceId, Binding("plain-b")),
      "two plain targets share the driver a claim exists to protect",
    )

    // The mismatch that does close a driver is still refused, in both directions.
    assertEquals(
      Binding("plain-a"),
      manager.refusedBinding(deviceId, Binding("wrapped", "wrapped")),
      "a custom-wrapper connect would rebuild the plain driver the agent holds",
    )
    manager.releaseClaim(deviceId)
    manager.claim(deviceId, Binding("wrapped", "wrapped"))
    assertEquals(
      Binding("wrapped", "wrapped"),
      manager.refusedBinding(deviceId, Binding("plain-a")),
      "and a plain connect would rebuild the wrapped driver",
    )
  }

  @Test
  fun `a claim is inert off the ios Maestro path, which is the limit and not an accident`() = runBlocking {
    // Driven through the real `connectionBinding` rather than a hand-shaped Binding, because the
    // limit IS that producer: it fills the driver key only for iOS, so on Android and web both sides
    // of the comparison are null and no claim can ever refuse anything. Pinned so the boundary is a
    // decision someone has to change a test to move, not a null that looks like an oversight - and
    // so that widening it (giving Android a claimable axis) can't happen silently.
    val manager = HostDeviceSessionManager()
    val android = { id: String ->
      connectionBinding(
        platform = TrailblazeDevicePlatform.ANDROID,
        driverType = TrailblazeDriverType.DEFAULT_ANDROID,
        target = FakeHostAppTarget(id),
      )
    }
    manager.claim(deviceId, android("alpha"))

    assertNull(
      manager.refusalFor(deviceId, android("beta")),
      "an Android claim cannot refuse, even for a target that installs a different runner",
    )
    // A session made from the same binding does refuse it, so this is the claim rule's narrowness
    // and not a binding that has nothing to compare.
    manager.attach(deviceId, ClosableStubStream(), android("alpha"))
    assertEquals(android("alpha"), manager.refusedBinding(deviceId, android("beta")))
  }

  @Test
  fun `a host-native ios connect is not refused by a wrapper claim it cannot disturb`() = runBlocking {
    // The driver axis only applies between connects that build a Maestro driver. AXe talks to the
    // simulator directly and never enters `HostIosDriverFactory`, so it cannot rebuild the agent's
    // wrapped driver - but it has no driver key either, and comparing that absence against a real
    // key refused it and told the user to stop an agent session that was never in the way.
    val manager = HostDeviceSessionManager()
    val axe = connectionBinding(
      platform = TrailblazeDevicePlatform.IOS,
      driverType = TrailblazeDriverType.IOS_AXE,
      target = FakeHostAppTarget("square", hasCustomIosDriver = true),
    )
    manager.claim(deviceId, Binding("square", "square"))

    assertNull(manager.refusalFor(deviceId, axe), "AXe builds no driver the claim protects")
    assertNull(manager.refusalForClaim(deviceId, axe), "and the same holds when AXe is the claimer")

    // The connect that really is holding the base driver is still refused, which is the pair the
    // driver axis exists for - both have no driver key, only one of them conflicts.
    assertEquals(Binding("square", "square"), manager.refusedBinding(deviceId, Binding()))
  }

  @Test
  fun `a claimer is refused on the driver axis by a session too, so connect order stops mattering`() = runBlocking {
    // The same narrowness from the other side. A holder and an asker are only in each other's way on
    // the axes they both occupy, and a claimer occupies the driver alone - so a session for plain A
    // must not refuse a claimer for plain B, exactly as a claim for plain A doesn't refuse a viewer
    // for plain B. Asking through `refusalFor` here made the outcome depend on who connected first.
    val manager = HostDeviceSessionManager()
    manager.attach(deviceId, ClosableStubStream(), Binding("plain-a"))

    assertNull(
      manager.refusalForClaim(deviceId, Binding("plain-b")),
      "the session and the claimer would run the identical base driver",
    )
    // A viewer asking the same question still is refused: it wants the device's target, which the
    // session owns, and it can act on the answer by disconnecting.
    assertEquals(Binding("plain-a"), manager.refusedBinding(deviceId, Binding("plain-b")))

    // And the driver axis, which a claimer does occupy, still refuses it.
    assertEquals(
      Binding("plain-a"),
      manager.refusalForClaim(deviceId, Binding("wrapped", "wrapped"))?.heldBy?.single()?.boundTo,
      "driving it for a custom wrapper would rebuild the driver the session is streaming on",
    )
  }
}
