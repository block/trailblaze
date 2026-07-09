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

  private class RecordingActivator : AndroidNetworkCaptureActivator {
    val started = mutableListOf<String>()
    val stopped = mutableListOf<String>()

    override fun start(
      sessionId: String,
      sessionDir: File,
      deviceId: TrailblazeDeviceId,
      targetAppId: String?,
    ) {
      started += sessionId
    }

    override fun stop(sessionId: String) {
      stopped += sessionId
    }
  }

  private val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
  private val dir = File(System.getProperty("java.io.tmpdir"))

  @Test
  fun `routes to proxy when the opt-in is on`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { true }

    composite.start("s1", dir, deviceId, null)

    assertEquals(listOf("s1"), proxy.started)
    assertTrue(fallback.started.isEmpty())
  }

  @Test
  fun `routes to fallback when the opt-in is off`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { false }

    composite.start("s1", dir, deviceId, null)

    assertEquals(listOf("s1"), fallback.started)
    assertTrue(proxy.started.isEmpty())
  }

  @Test
  fun `no-op when the opt-in is off and there is no fallback (OSS layout)`() {
    val proxy = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback = null) { false }

    composite.start("s1", dir, deviceId, null)
    composite.stop("s1")

    assertTrue(proxy.started.isEmpty())
    assertTrue(proxy.stopped.isEmpty())
  }

  @Test
  fun `stop tears down the delegate that started the session`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { true }

    composite.start("s1", dir, deviceId, null)
    composite.stop("s1")

    assertEquals(listOf("s1"), proxy.stopped)
    assertTrue(fallback.stopped.isEmpty())
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
  fun `the gate is evaluated per-session at start time`() {
    val proxy = RecordingActivator()
    val fallback = RecordingActivator()
    var optIn = false
    val composite = CompositeAndroidNetworkCaptureActivator(proxy, fallback) { optIn }

    composite.start("off", dir, deviceId, null) // routes to fallback
    optIn = true
    composite.start("on", dir, deviceId, null) // routes to proxy

    composite.stop("off")
    composite.stop("on")

    assertEquals(listOf("off"), fallback.started)
    assertEquals(listOf("on"), proxy.started)
    assertEquals(listOf("off"), fallback.stopped)
    assertEquals(listOf("on"), proxy.stopped)
  }
}
