package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType

/**
 * Behavioral tests for [WebScreencastVideoCapture]'s observable contract: whether it records from a
 * registered screencast feed or delegates to the Playwright-recorder fallback. The wall-clock →
 * ffconcat timing math is covered separately in [ScreencastTimelineTest]; here we don't invoke
 * ffmpeg — we assert the *routing* decision (feed present → subscribe; feed absent → delegate) and
 * that a delivered frame is persisted to disk.
 */
class WebScreencastVideoCaptureTest {

  private val deviceId = "web-screencast-test"
  private lateinit var sessionDir: File

  @BeforeTest
  fun setUp() {
    sessionDir = Files.createTempDirectory("webscreencast-").toFile()
  }

  @AfterTest
  fun tearDown() {
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
  fun `stop detaches the feed subscription`() {
    val feed = FakeFeed()
    WebScreencastFeedRegistry.register(deviceId, feed)
    val capture = WebScreencastVideoCapture(fallback = RecordingFallback())

    capture.start(sessionDir, deviceId, appId = null)
    assertEquals(1, feed.subscriberCount)

    capture.stop(CaptureOptions(captureVideo = true))
    assertEquals(0, feed.subscriberCount, "stop must detach the screencast subscription")
  }
}
