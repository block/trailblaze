package xyz.block.trailblaze.capture

import xyz.block.trailblaze.capture.logcat.AndroidLogcatCapture
import xyz.block.trailblaze.capture.logcat.IosLogCapture
import xyz.block.trailblaze.capture.video.AndroidVideoCapture
import xyz.block.trailblaze.capture.video.IosVideoCapture
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
      // Explicitly disable iOS logs (now on by default) to isolate the logcat-on-iOS case.
      CaptureOptions(captureVideo = false, captureLogcat = true, captureIosLogs = false),
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
      // Explicitly disable logcat (now on by default) to isolate the iosLogs-on-Android case.
      CaptureOptions(captureVideo = false, captureLogcat = false, captureIosLogs = true),
      TrailblazeDevicePlatform.ANDROID,
    )
    assertNull(session)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // captureVideo × platform
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
  fun `captureVideo true on IOS adds IosVideoCapture`() {
    val session = CaptureSession.fromOptions(
      // Disable the log streams (now on by default) so this isolates the video-on-iOS case.
      CaptureOptions(captureVideo = true, captureLogcat = false, captureIosLogs = false),
      TrailblazeDevicePlatform.IOS,
    )
    assertNotNull(session)
    assertTrue(streamsOf(session).any { it is IosVideoCapture })
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
  fun `WEB platform with captureVideo on adds PlaywrightVideoCapture only`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = true, captureLogcat = true, captureIosLogs = true),
      TrailblazeDevicePlatform.WEB,
    )
    // logcat / iOS logs don't apply to web; only the Playwright video stream is wired.
    assertNotNull(session)
    val streams = streamsOf(session).map { it::class.simpleName!! }
    assertContains(streams, "PlaywrightVideoCapture")
    kotlin.test.assertEquals(1, streams.size, "WEB only registers the Playwright video stream")
  }

  @Test
  fun `WEB platform with captureVideo off skips all streams`() {
    val session = CaptureSession.fromOptions(
      CaptureOptions(captureVideo = false, captureLogcat = true, captureIosLogs = true),
      TrailblazeDevicePlatform.WEB,
    )
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
