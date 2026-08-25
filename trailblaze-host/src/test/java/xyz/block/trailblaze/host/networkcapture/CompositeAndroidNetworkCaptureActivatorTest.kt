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

    override fun start(
      sessionId: String,
      sessionDir: File,
      deviceId: TrailblazeDeviceId,
      targetAppIds: List<String>,
      deviceLabel: String?,
    ) {
      started += sessionId
      startedDevices += deviceId.instanceId to deviceLabel
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
  fun `routes to proxy when the opt-in is on`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { true }

    composite.start("s1", dir, deviceId, emptyList())

    assertEquals(listOf("s1"), proxy.started)
    assertTrue(fallback.started.isEmpty())
  }

  @Test
  fun `routes to fallback when the opt-in is off`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { false }

    composite.start("s1", dir, deviceId, emptyList())

    assertEquals(listOf("s1"), fallback.started)
    assertTrue(proxy.started.isEmpty())
  }

  @Test
  fun `no-op when the opt-in is off and there is no fallback (OSS layout)`() {
    val proxy = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback = null) { false }

    composite.start("s1", dir, deviceId, emptyList())
    composite.stop("s1")

    assertTrue(proxy.started.isEmpty())
    assertTrue(proxy.stopped.isEmpty())
  }

  @Test
  fun `stop tears down the delegate that started the session`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { true }

    composite.start("s1", dir, deviceId, emptyList())
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

    composite.start("s1", dir, deviceId, emptyList(), deviceLabel = "seller")
    composite.start("s1", dir, buyer, emptyList(), deviceLabel = "buyer")
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

    composite.start("s1", dir, deviceId, emptyList()) // routes to fallback and is recorded
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

    composite.start("off", dir, deviceId, emptyList()) // routes to fallback
    optIn = true
    composite.start("on", dir, deviceId, emptyList()) // routes to proxy

    composite.stop("off")
    composite.stop("on")

    assertEquals(listOf("off"), fallback.started)
    assertEquals(listOf("on"), proxy.started)
    assertEquals(listOf("off"), fallback.stopped)
    assertEquals(listOf("on"), proxy.stopped)
  }
}
