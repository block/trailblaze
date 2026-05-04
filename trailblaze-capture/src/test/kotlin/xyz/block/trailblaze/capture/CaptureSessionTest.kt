package xyz.block.trailblaze.capture

import xyz.block.trailblaze.capture.logcat.AndroidLogcatCapture
import xyz.block.trailblaze.capture.logcat.IosLogCapture
import xyz.block.trailblaze.capture.video.AndroidVideoCapture
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the cross-product of capture flags × platforms in [CaptureSession.fromOptions].
 *
 * Uses reflection to peek at the streams list since it's a private val — happy to switch to
 * an exposed test seam if this becomes a maintenance problem.
 */
class CaptureSessionTest {

  private fun streamsOf(session: CaptureSession): List<CaptureStream> {
    val field = CaptureSession::class.java.getDeclaredField("streams").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return field.get(session) as List<CaptureStream>
  }

  // ──────────────────────────────────────────────────────────────────────────
  // captureLogcat × platform
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `captureLogcat true on ANDROID adds AndroidLogcatCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNotNull(session)
    val streams = streamsOf(session)
    assertTrue(streams.any { it is AndroidLogcatCapture })
    assertTrue(streams.none { it is IosLogCapture })
  }

  @Test
  fun `captureLogcat true on IOS does NOT add AndroidLogcatCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = true),
      TrailblazeDevicePlatform.IOS,
    )
    // captureLogcat is Android-only — on iOS with no other streams enabled, fromOptions
    // should refuse to build a session at all.
    assertNull(session)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // captureIosLogs × platform
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `captureIosLogs true on IOS adds IosLogCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureIosLogs = true),
      TrailblazeDevicePlatform.IOS,
    )
    assertNotNull(session)
    val streams = streamsOf(session)
    assertTrue(streams.any { it is IosLogCapture })
    assertTrue(streams.none { it is AndroidLogcatCapture })
  }

  @Test
  fun `captureIosLogs true on ANDROID does NOT add IosLogCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureIosLogs = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNull(session)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // captureVideo × platform — Android wires up; iOS is intentionally disabled
  // until the WebP migration noted in CaptureSession's TODO comment lands.
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `captureVideo true on ANDROID adds AndroidVideoCapture`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNotNull(session)
    val streams = streamsOf(session)
    assertTrue(streams.any { it is AndroidVideoCapture })
  }

  @Test
  fun `captureVideo true on IOS produces no video stream (intentionally disabled)`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true),
      TrailblazeDevicePlatform.IOS,
    )
    // iOS video capture is gated by the TODO in CaptureSession — no stream selected.
    assertNull(session)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Multiple flags + WEB / null platform
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `Android with video plus logcat enabled produces both streams`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true, captureLogcat = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNotNull(session)
    val streams = streamsOf(session).map { it::class.simpleName!! }
    assertContains(streams, "AndroidVideoCapture")
    assertContains(streams, "AndroidLogcatCapture")
  }

  @Test
  fun `WEB platform skips all platform-gated streams`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true, captureLogcat = true, captureIosLogs = true),
      TrailblazeDevicePlatform.WEB,
    )
    // Nothing wires up for WEB today; fromOptions returns null.
    assertNull(session)
  }

  @Test
  fun `null platform skips all platform-gated streams`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true, captureLogcat = true),
      platform = null,
    )
    assertNull(session)
  }

  @Test
  fun `all flags off returns null regardless of platform`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = false, captureIosLogs = false),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNull(session)
  }
}
