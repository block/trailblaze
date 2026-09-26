package xyz.block.trailblaze.capture.video

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * What the screencap fallback owes the rest of the system: the same recording artifact the
 * streaming recorder produces, on a window the report can place events against, without paying
 * for frames of a screen that did not change.
 *
 * The recording tests encode for real. Whether a container comes out that states its own duration
 * is the whole claim, and a fake ffmpeg could not make it.
 */
class AndroidScreencapVideoCaptureTest {

  private lateinit var sessionDir: File

  @BeforeTest
  fun setUp() {
    sessionDir = Files.createTempDirectory("screencap-capture-").toFile()
  }

  @AfterTest
  fun tearDown() {
    sessionDir.deleteRecursively()
  }

  /** A distinct, genuinely decodable PNG — ffmpeg has to be able to read these. */
  private fun png(shade: Int): ByteArray {
    val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color(shade, shade, shade)
    g.fillRect(0, 0, 64, 48)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }

  private fun framesOnDisk(): List<File> =
    File(sessionDir, ".trailblaze-screencap-frames").listFiles()?.sorted().orEmpty()

  /**
   * Polls [count] until it reaches [n] or [timeoutMs] passes, returning the last value. A fixed
   * sleep is a ceiling on a loaded CI agent: 300ms once bought only two 20ms samples.
   */
  private fun awaitAtLeast(n: Int, timeoutMs: Long = 10_000L, count: () -> Int): Int {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (count() < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
    return count()
  }

  private fun capture(
    intervalMs: Long = 20L,
    grab: (TrailblazeDeviceId) -> ByteArray?,
  ) = AndroidScreencapVideoCapture(
    format = RecordingFormat.preferred,
    intervalMs = intervalMs,
    grabFrame = grab,
  )

  @Test
  fun `a sampled session is recorded as a container that states the session's own length`() {
    // The report places an event on a recording by scaling its offset into the artifact's window
    // onto the container's duration. If the container's duration and the window disagree, every
    // event lands at the wrong moment — so this is the property that makes the clip navigable.
    val shade = AtomicInteger(0)
    val capture = capture { png(shade.addAndGet(40) % 250) }

    capture.start(sessionDir, DEVICE_ID, appId = null)
    Thread.sleep(600)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertTrue(artifact.file.isFile, "the recording must exist on disk")
    assertTrue(artifact.file.length() > 0, "the recording must hold bytes")
    assertEquals(RecordingFormat.preferred.canonicalFilename, artifact.file.name)
    assertEquals(RecordingFormat.preferred.captureType, artifact.type)

    val endMs = assertNotNull(artifact.endTimestampMs, "a recording with no end cannot be scaled onto")
    val windowMs = endMs - artifact.startTimestampMs
    val durationMs = assertNotNull(
      VideoDuration.probeMs(artifact.file),
      "a recording whose length cannot be read cannot be scrubbed",
    )
    assertTrue(
      kotlin.math.abs(durationMs - windowMs) < windowMs / 4,
      "container is ${durationMs}ms but the window it covers is ${windowMs}ms",
    )
  }

  @Test
  fun `a session that ends on a still screen is no longer than the session was`() {
    // The case that used to come out nearly twice too long. A run that stops changing partway
    // through leaves one frame holding the rest of the session, and the resample has no way to
    // know how long a final frame lasts, so it writes the gap before it all over again. The
    // report scales session time onto container time, so an overlong container pushes every
    // event late by the same proportion.
    val ticks = AtomicInteger(0)
    val capture = capture { if (ticks.incrementAndGet() <= 3) png(40 * ticks.get()) else png(120) }

    capture.start(sessionDir, DEVICE_ID, appId = null)
    Thread.sleep(900)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    val endMs = assertNotNull(artifact.endTimestampMs)
    val windowMs = endMs - artifact.startTimestampMs
    val durationMs = assertNotNull(VideoDuration.probeMs(artifact.file))
    assertTrue(
      durationMs <= windowMs + GRID_TOLERANCE_MS,
      "container is ${durationMs}ms for a ${windowMs}ms session; it must not outrun the window",
    )
  }

  @Test
  fun `a screen that is not changing is stored once, not once per sample`() {
    // A still screen would otherwise cost a full-resolution image every interval, for a timeline
    // that already represents it correctly as one frame held for its true duration.
    val grabs = AtomicInteger(0)
    val still = png(120)
    val capture = capture { grabs.incrementAndGet(); still }

    capture.start(sessionDir, DEVICE_ID, appId = null)
    val sampled = awaitAtLeast(3) { grabs.get() }
    val stored = framesOnDisk().size
    capture.stop(CaptureOptions(captureVideo = true))

    assertTrue(sampled >= 3, "the sampler should have run several times, ran $sampled")
    assertEquals(1, stored, "an unchanging screen is one frame, not $stored")
  }

  @Test
  fun `each change to the screen is kept`() {
    val shade = AtomicInteger(0)
    val capture = capture { png(shade.addAndGet(50) % 250) }

    capture.start(sessionDir, DEVICE_ID, appId = null)
    val stored = awaitAtLeast(3) { framesOnDisk().size }
    capture.stop(CaptureOptions(captureVideo = true))

    assertTrue(stored >= 3, "a changing screen should keep its changes, kept only $stored")
  }

  @Test
  fun `bytes that are not an image are never mistaken for a frame`() {
    // The exact shape of the failure this whole path exists for: a device that rejects the
    // command answers on stdout, so the "frame" is an ASCII complaint.
    val capture = capture { "/system/bin/sh: screencap: inaccessible or not found".toByteArray() }

    capture.start(sessionDir, DEVICE_ID, appId = null)
    Thread.sleep(150)
    assertEquals(0, framesOnDisk().size, "shell output is not a frame")

    assertNull(
      capture.stop(CaptureOptions(captureVideo = true)),
      "a session with no real frames must not claim a recording",
    )
    assertTrue(
      !File(sessionDir, RecordingFormat.preferred.canonicalFilename).exists(),
      "no recording file may be left behind either",
    )
  }

  @Test
  fun `a device that answers nothing yields no recording`() {
    val capture = capture { null }

    capture.start(sessionDir, DEVICE_ID, appId = null)
    Thread.sleep(150)

    assertNull(capture.stop(CaptureOptions(captureVideo = true)))
  }

  @Test
  fun `sampling stops when the capture does`() {
    // The sampler holds an adb round trip every interval. One that outlived its session would go
    // on charging the next one for footage nobody will see.
    val grabs = AtomicInteger(0)
    val capture = capture { grabs.incrementAndGet(); png(90) }

    capture.start(sessionDir, DEVICE_ID, appId = null)
    Thread.sleep(200)
    capture.stop(CaptureOptions(captureVideo = true))
    val atStop = grabs.get()
    Thread.sleep(200)

    assertEquals(atStop, grabs.get(), "the sampler kept going after stop")
  }

  @Test
  fun `a png is recognised and shell output is not`() {
    assertTrue(AndroidScreencapVideoCapture.looksLikePng(png(10)))
    assertTrue(!AndroidScreencapVideoCapture.looksLikePng("not a png at all".toByteArray()))
    assertTrue(!AndroidScreencapVideoCapture.looksLikePng(ByteArray(0)))
  }

  private companion object {
    /** The resample writes on a fixed grid, so a container may round up by up to one step. */
    const val GRID_TOLERANCE_MS = 150L
    const val DEVICE_ID = "emulator-5560"
  }

  @Test
  fun `a companion device's recording lands under its own name with its own frame scratch`() {
    // Two screencap recorders share one session directory in a multi-device session. Each needs
    // its own frame scratch — or the second's frames would be encoded into the first's clip — and
    // its own output file, or the companion would overwrite the start device's recording.
    val shade = AtomicInteger(0)
    val capture = AndroidScreencapVideoCapture(
      format = RecordingFormat.preferred,
      intervalMs = 20L,
      grabFrame = { png(shade.addAndGet(40) % 250) },
      basename = "video-buyer",
    )

    capture.start(sessionDir, DEVICE_ID, appId = null)
    // Read the scratch while it records: the stop clears it, so an empty directory afterwards
    // proves nothing about where the frames went.
    val ownScratch = File(sessionDir, ".trailblaze-screencap-frames-video-buyer")
    assertTrue(awaitAtLeast(3) { ownScratch.listFiles()?.size ?: 0 } >= 3, "the companion's frames land in its own scratch")
    assertEquals(0, framesOnDisk().size, "the companion never touched the start device's frame scratch")
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertEquals(RecordingFormat.preferred.filename("video-buyer"), artifact.file.name)
    assertEquals(sessionDir, artifact.file.parentFile, "the companion's recording sits beside the start device's")
    assertTrue(artifact.file.length() > 0)
    assertTrue(
      !File(sessionDir, RecordingFormat.preferred.canonicalFilename).exists(),
      "the start device's recording is not the companion's to write",
    )
  }
}
