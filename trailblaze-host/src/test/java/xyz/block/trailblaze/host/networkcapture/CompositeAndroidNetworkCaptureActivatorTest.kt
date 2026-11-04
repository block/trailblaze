package xyz.block.trailblaze.host.networkcapture

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure routing logic of [CompositeAndroidNetworkCaptureActivator]: which delegate
 * a session is routed to, that the opt-in gate is evaluated per-session, the OSS null-fallback
 * no-op, and that [stop] tears down the delegate that actually started the session.
 *
 * Uses a tiny recording double for the 2-method [AndroidNetworkCaptureActivator] SPI — it *is* the
 * interface under test, recording only the observable contract (which delegate got start/stop). The
 * real delegates mutate a device, so they can't run here.
 */
class CompositeAndroidNetworkCaptureActivatorTest {

  private class RecordingActivator(
    private val optedInSessions: Set<String> = emptySet(),
  ) : AndroidNetworkCaptureActivator {
    val started = mutableListOf<String>()
    /** Every (device, label) pair this delegate was asked to capture, in call order. */
    val startedDevices = mutableListOf<Pair<String, String?>>()
    val stopped = mutableListOf<String>()
    /** The `targetAppIds` of every start, in call order. */
    val startedTargetAppIds = mutableListOf<List<String>?>()
    /** The `requireTraffic` of every start, in call order. */
    val startedRequireTraffic = mutableListOf<Boolean>()
    /** The `targetId` of every start, in call order. */
    val startedTargetIds = mutableListOf<String?>()

    override fun start(
      sessionId: String,
      sessionDir: File,
      deviceId: TrailblazeDeviceId,
      targetAppIds: List<String>?,
      deviceLabel: String?,
      requireTraffic: Boolean,
      targetId: String?,
    ) {
      started += sessionId
      startedDevices += deviceId.instanceId to deviceLabel
      startedTargetAppIds += targetAppIds
      startedRequireTraffic += requireTraffic
      startedTargetIds += targetId
    }

    override fun stop(sessionId: String) {
      stopped += sessionId
    }

    override fun isSessionCaptureOptedIn(sessionId: String): Boolean =
      sessionId in optedInSessions
  }

  private val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val dir = File(System.getProperty("java.io.tmpdir"))

  @Test
  fun `forwards no-target and unresolved-target starts to the delegate unchanged`() {
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(RecordingActivator(), fallback) { false }

    composite.start("no-target", dir, deviceId, null, targetId = null)
    composite.start("unresolved", dir, deviceId, emptyList(), targetId = null)

    // The delegate tells "skip" from "refuse" by null vs empty, so the composite must not
    // normalize one into the other.
    assertEquals(listOf(null, emptyList()), fallback.startedTargetAppIds)
  }

  @Test
  fun `forwards the target id to the delegate`() {
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(RecordingActivator(), fallback) { false }

    composite.start("s1", dir, deviceId, listOf("com.example.app"), targetId = "example")

    // A delegate that only serves some targets decides from this; dropping it would idle capture
    // for every target, including the ones it serves.
    assertEquals(listOf<String?>("example"), fallback.startedTargetIds)
  }

  @Test
  fun `routes to proxy when the opt-in is on`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { true }

    composite.start("s1", dir, deviceId, emptyList(), targetId = null)

    assertEquals(listOf("s1"), proxy.started)
    assertTrue(fallback.started.isEmpty())
  }

  @Test
  fun `routes to fallback when the opt-in is off`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { false }

    composite.start("s1", dir, deviceId, emptyList(), targetId = null)

    assertEquals(listOf("s1"), fallback.started)
    assertTrue(proxy.started.isEmpty())
  }

  @Test
  fun `no-op when the opt-in is off and there is no fallback (OSS layout)`() {
    val proxy = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback = null) { false }

    composite.start("s1", dir, deviceId, emptyList(), targetId = null)
    composite.stop("s1")

    assertTrue(proxy.started.isEmpty())
    assertTrue(proxy.stopped.isEmpty())
  }

  @Test
  fun `stop tears down the delegate that started the session`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { true }

    composite.start("s1", dir, deviceId, emptyList(), targetId = null)
    composite.stop("s1")

    assertEquals(listOf("s1"), proxy.stopped)
    assertTrue(fallback.stopped.isEmpty())
  }

  @Test
  fun `both devices of one session route to the same delegate and keep their labels`() {
    // Routing is keyed by session alone on purpose: a session's two displays must not end up on
    // different capture mechanisms, one of which would be recording nothing.
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { true }
    val buyer = TrailblazeDeviceId("emulator-5556", TrailblazeDevicePlatform.ANDROID)

    composite.start("s1", dir, deviceId, emptyList(), deviceLabel = "seller", targetId = null)
    composite.start("s1", dir, buyer, emptyList(), deviceLabel = "buyer", targetId = null)
    composite.stop("s1")

    assertEquals(
      listOf<Pair<String, String?>>("emulator-5554" to "seller", "emulator-5556" to "buyer"),
      proxy.startedDevices.toList(),
    )
    assertTrue(fallback.startedDevices.isEmpty())
    // One session-scoped stop — the delegate fans out over its own devices.
    assertEquals(listOf("s1"), proxy.stopped)
  }

  @Test
  fun `whether a session requires traffic reaches the delegate`() {
    // Dropping it would make an interactive session's empty capture fail its end again.
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(RecordingActivator(), fallback) { false }

    composite.start("interactive", dir, deviceId, listOf("com.example"), requireTraffic = false, targetId = null)
    composite.start("run", dir, deviceId, listOf("com.example"), targetId = null)

    assertEquals(listOf(false, true), fallback.startedRequireTraffic.toList())
  }

  @Test
  fun `stop is a no-op for a session that was never started`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { true }

    composite.stop("never-started")

    assertTrue(proxy.stopped.isEmpty())
    assertTrue(fallback.stopped.isEmpty())
  }

  @Test
  fun `isSessionCaptureOptedIn defers to the delegate the session would route to`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator(optedInSessions = setOf("s1"))
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { false }

    assertTrue(composite.isSessionCaptureOptedIn("s1"))
    assertTrue(!composite.isSessionCaptureOptedIn("s2"))
  }

  @Test
  fun `isSessionCaptureOptedIn asks the recorded delegate for an already-routed session`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator(optedInSessions = setOf("s1"))
    var optIn = false
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { optIn }

    composite.start("s1", dir, deviceId, emptyList(), targetId = null) // routes to fallback and is recorded
    optIn = true // a later flip must not re-route the opt-in question to proxy

    assertTrue(composite.isSessionCaptureOptedIn("s1"))
  }

  @Test
  fun `isSessionCaptureOptedIn is false when the opt-in is off and there is no fallback (OSS layout)`() {
    val proxy = RecordingActivator(optedInSessions = setOf("s1"))
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback = null) { false }

    assertTrue(!composite.isSessionCaptureOptedIn("s1"))
  }

  @Test
  fun `the gate is evaluated per-session at start time`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    var optIn = false
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { optIn }

    composite.start("off", dir, deviceId, emptyList(), targetId = null) // routes to fallback
    optIn = true
    composite.start("on", dir, deviceId, emptyList(), targetId = null) // routes to proxy

    composite.stop("off")
    composite.stop("on")

    assertEquals(listOf("off"), fallback.started)
    assertEquals(listOf("on"), proxy.started)
    assertEquals(listOf("off"), fallback.stopped)
    assertEquals(listOf("on"), proxy.stopped)
  }
}
