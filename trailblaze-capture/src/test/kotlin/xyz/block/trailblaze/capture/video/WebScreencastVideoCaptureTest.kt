package xyz.block.trailblaze.capture.video

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType

/**
 * Behavioral tests for [WebScreencastVideoCapture]'s observable contract: whether it records from a
 * registered screencast feed or delegates to the Playwright-recorder fallback, and — with no
 * fallback — that it follows the device's feed however late or often the browser comes up. The
 * wall-clock → ffconcat timing math is covered separately in [ScreencastTimelineTest]. The routing
 * tests don't invoke ffmpeg; the follow test encodes for real, because the companion's own file
 * coming out of the mux is the claim.
 */
class WebScreencastVideoCaptureTest {

  private val deviceId = "web-screencast-test"
  private lateinit var sessionDir: File

  @BeforeTest
  fun setUp() {
    sessionDir = Files.createTempDirectory("webscreencast-").toFile()
  }

  /** Captures a test started, stopped at teardown so a failed assertion can't leave a watch behind. */
  private val captures = mutableListOf<WebScreencastVideoCapture>()

  private fun following(basename: String) =
    WebScreencastVideoCapture(fallback = null, basename = basename).also { captures += it }

  @AfterTest
  fun tearDown() {
    captures.forEach { runCatching { it.stop(CaptureOptions(captureVideo = true)) } }
    WebScreencastFeedRegistry.get(deviceId)?.let { WebScreencastFeedRegistry.unregister(deviceId, it) }
    sessionDir.deleteRecursively()
  }

  /** A fallback that records whether it was started/stopped, standing in for [PlaywrightVideoCapture]. */
  private class RecordingFallback : CaptureStream {
    override val type = CaptureType.VIDEO
    var started = false
    var stopped = false

    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      started = true
    }

    override fun stop(options: CaptureOptions): CaptureArtifact? {
      stopped = true
      return null
    }
  }

  /** A feed whose frames the test can push synchronously. */
  private class FakeFeed : WebScreencastFeedRegistry.Feed {
    var subscriberCount = 0
    private var onFrame: ((ByteArray, Long) -> Unit)? = null

    override fun subscribe(onFrame: (jpeg: ByteArray, hostTimestampMs: Long) -> Unit): AutoCloseable {
      subscriberCount++
      this.onFrame = onFrame
      return AutoCloseable {
        subscriberCount--
        this.onFrame = null
      }
    }

    fun emit(jpeg: ByteArray, tsMs: Long) = onFrame?.invoke(jpeg, tsMs)
  }

  @Test
  fun `no feed registered delegates start and stop to the fallback`() {
    val fallback = RecordingFallback()
    val capture = WebScreencastVideoCapture(fallback = fallback)

    capture.start(sessionDir, deviceId, appId = null)
    assertTrue(fallback.started, "start must delegate to the fallback when no screencast feed exists")

    // No frames dir on the delegated path — the fallback owns the recording.
    assertNull(
      sessionDir.listFiles { f -> f.name == ".trailblaze-screencast-frames" }?.firstOrNull(),
      "delegated path must not create the screencast frames dir",
    )

    capture.stop(CaptureOptions(captureVideo = true))
    assertTrue(fallback.stopped, "stop must delegate to the fallback on the delegated path")
  }

  @Test
  fun `feed registered subscribes and does not start the fallback`() {
    val fallback = RecordingFallback()
    val feed = FakeFeed()
    WebScreencastFeedRegistry.register(deviceId, feed)
    val capture = WebScreencastVideoCapture(fallback = fallback)

    capture.start(sessionDir, deviceId, appId = null)

    assertTrue(!fallback.started, "the fallback must NOT be started when a screencast feed is present")
    assertEquals(1, feed.subscriberCount, "start must subscribe exactly once to the feed")

    // A delivered frame is persisted to the frames dir (observable side effect of the screencast path).
    feed.emit(byteArrayOf(1, 2, 3), tsMs = 1000)
    val framesDir = File(sessionDir, ".trailblaze-screencast-frames")
    assertTrue(framesDir.isDirectory, "the screencast path must create a frames dir")
    assertEquals(1, framesDir.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0)
  }

  @Test
  fun `frames past the scratch-disk budget are not stored`() {
    val feed = FakeFeed()
    WebScreencastFeedRegistry.register(deviceId, feed)
    val capture = WebScreencastVideoCapture(fallback = RecordingFallback(), maxFrameBytes = 250)
      .also { captures += it }
    capture.start(sessionDir, deviceId, appId = null)

    // 100-byte frames, spaced past the throttle: two fit a 250-byte budget, the rest are dropped.
    repeat(4) { feed.emit(ByteArray(100), tsMs = 1000L + it * 100) }

    val framesDir = File(sessionDir, ".trailblaze-screencast-frames")
    assertEquals(2, framesDir.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0)
  }

  @Test
  fun `stop detaches the feed subscription`() {
    val feed = FakeFeed()
    WebScreencastFeedRegistry.register(deviceId, feed)
    val capture = WebScreencastVideoCapture(fallback = RecordingFallback())

    capture.start(sessionDir, deviceId, appId = null)
    assertEquals(1, feed.subscriberCount)

    capture.stop(CaptureOptions(captureVideo = true))
    assertEquals(0, feed.subscriberCount, "stop must detach the screencast subscription")
  }

  /** A distinct, genuinely decodable JPEG — the mux has to be able to read these. */
  private fun jpeg(shade: Int): ByteArray {
    val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color(shade, shade, shade)
    g.fillRect(0, 0, 64, 48)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
  }

  @Test
  fun `with no fallback it follows the browser's feed, however late or often it comes up`() {
    // A web companion of a multi-device session: its browser may launch after the recording starts,
    // and may be relaunched mid-session. Playwright's own recorder is off the table (it would rebuild
    // a context the trail is driving), so the recording has to follow the feed instead.
    val capture = following("video-dashboard")
    capture.start(sessionDir, deviceId, appId = null)

    val first = FakeFeed()
    WebScreencastFeedRegistry.register(deviceId, first)
    assertEquals(1, first.subscriberCount, "a browser that comes up after start is recorded from then on")
    val t0 = System.currentTimeMillis()
    first.emit(jpeg(40), tsMs = t0)
    first.emit(jpeg(80), tsMs = t0 + 100)

    WebScreencastFeedRegistry.unregister(deviceId, first)
    assertEquals(0, first.subscriberCount, "a closed browser's feed is let go")
    val relaunched = FakeFeed()
    WebScreencastFeedRegistry.register(deviceId, relaunched)
    assertEquals(1, relaunched.subscriberCount, "and the relaunched browser is followed onto its new feed")
    relaunched.emit(jpeg(120), tsMs = t0 + 200)

    val ownScratch = File(sessionDir, ".trailblaze-screencast-frames-video-dashboard")
    assertEquals(3, ownScratch.listFiles()?.count { it.name.endsWith(".jpg") } ?: 0, "frames from both feeds land in its own scratch")
    assertTrue(
      !File(sessionDir, ".trailblaze-screencast-frames").exists(),
      "a companion never touches the scratch a web session's own recorder uses",
    )

    // The start device's recorder may be muxing into the same directory at the same moment.
    val primaryScript = File(sessionDir, "video.screencast.concat.txt").apply { writeText("primary's") }

    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))
    assertEquals("primary's", primaryScript.readText(), "the companion's mux must not touch the primary's concat script")
    assertEquals(RecordingFormat.preferred.filename("video-dashboard"), artifact.file.name)
    assertTrue(artifact.file.length() > 0)
    assertTrue(
      !File(sessionDir, RecordingFormat.preferred.canonicalFilename).exists(),
      "the start device's recording is not the companion's to write",
    )
    assertEquals(0, relaunched.subscriberCount, "stop detaches from the feed")
    assertNull(capture.stop(CaptureOptions(captureVideo = true)), "a second stop records nothing more")
  }

  @Test
  fun `with no fallback a browser that comes up after stop is not recorded`() {
    val capture = following("video-dashboard")
    capture.start(sessionDir, deviceId, appId = null)
    assertNull(capture.stop(CaptureOptions(captureVideo = true)), "a browser that never came up recorded nothing")

    val late = FakeFeed()
    WebScreencastFeedRegistry.register(deviceId, late)
    assertEquals(0, late.subscriberCount, "the stopped recording no longer watches the device")
  }
}
