package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The held tail is a real encode, so it is tested against real ffmpeg: what matters is that the
 * container comes out as long as the session and that no earlier frame moved.
 */
class RecordingTailHoldTest {

  private lateinit var dir: File

  @BeforeTest
  fun setUp() {
    dir = Files.createTempDirectory("tail-hold-").toFile()
  }

  @AfterTest
  fun tearDown() {
    dir.deleteRecursively()
  }

  /** A short VP9 WebM with frames at the given millisecond timestamps, like the live mux writes. */
  private fun webm(vararg frameMs: Int): File = recording("video.webm", frameMs)

  /** The same, as the H.264 mp4 a host without libvpx records. */
  private fun mp4(vararg frameMs: Int): File = recording("video.mp4", frameMs)

  private fun recording(name: String, frameMs: IntArray): File {
    val out = File(dir, name)
    val select = frameMs.indices.joinToString("+") { "eq(n\\,$it)" }
    val pts = frameMs.withIndex().joinToString("") { (i, ms) -> "if(eq(N\\,$i)\\,${ms / 1000.0}/TB\\," } +
      "PTS" + ")".repeat(frameMs.size)
    val result = runSubprocessWithTimeout(
      listOf(
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", "testsrc=size=64x48:rate=25",
        "-vf", "select='$select',setpts='$pts'",
        "-frames:v", frameMs.size.toString(),
      ) + RecordingTailHold.encodeArgsFor(out) + listOf("-enc_time_base", "1:1000", out.absolutePath),
      timeoutSeconds = 60,
    )
    assertEquals(0, result?.exitCode, "fixture encode failed: ${result?.output}")
    return out
  }

  private fun packetTimesMs(file: File): List<Long> =
    runSubprocessWithTimeout(
      listOf("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0", file.absolutePath),
      timeoutSeconds = 30,
    )!!.output.lines().mapNotNull { it.trim().toDoubleOrNull() }.map { Math.round(it * 1000) }

  @Test
  fun `a still tail is held to the stop, and no earlier frame moves`() {
    val recording = webm(0, 120, 350)
    val before = packetTimesMs(recording)

    assertTrue(RecordingTailHold.holdLastFrame(recording, untilMs = 4_000L), "the hold should succeed")

    val durationMs = assertNotNull(VideoDuration.probeMs(recording))
    assertTrue(durationMs >= 3_900L, "the recording must run to the stop, ran ${durationMs}ms")
    val after = packetTimesMs(recording)
    assertContentEquals(before, after.dropLast(1), "the footage's own frames keep their timestamps")
    assertEquals(4_000L, after.last(), "one held frame, stamped at the stop")
  }

  @Test
  fun `a single-frame recording becomes one held for the session`() {
    // The CLI's "capture screen state" session: nothing moved, so the feed sent one frame.
    val recording = webm(0)

    assertTrue(RecordingTailHold.holdLastFrame(recording, untilMs = 16_000L))

    assertTrue(assertNotNull(VideoDuration.probeMs(recording)) >= 15_900L)
    assertEquals(listOf(0L, 16_000L), packetTimesMs(recording))
  }

  @Test
  fun `a file that is not a recording is left exactly as it was`() {
    val notVideo = File(dir, "video.webm").apply { writeText("not a webm") }

    assertFalse(RecordingTailHold.holdLastFrame(notVideo, untilMs = 5_000L))

    assertEquals("not a webm", notVideo.readText(), "a failed hold must never cost the original")
    assertFalse(File(dir, "video.held.webm").exists(), "and must leave no half-written file behind")
  }

  @Test
  fun `a recording that already runs its window is not re-encoded`() {
    val held = mutableListOf<Long>()
    val ok = RecordingTailHold.holdToWindow(File("x.webm"), windowMs = 10_000L, probeMs = { 9_900L }) { _, until -> held += until; true }
    assertTrue(ok)
    assertEquals(emptyList(), held)
  }

  @Test
  fun `a recording short of its window is held to it`() {
    val held = mutableListOf<Long>()
    val ok = RecordingTailHold.holdToWindow(File("x.webm"), windowMs = 27_800L, probeMs = { 4_400L }) { _, until -> held += until; true }
    assertTrue(ok)
    assertEquals(listOf(27_800L), held)
  }

  @Test
  fun `a recording whose length cannot be read is left alone`() {
    val ok = RecordingTailHold.holdToWindow(File("x.webm"), windowMs = 27_800L, probeMs = { null }) { _, _ -> error("must not hold") }
    assertFalse(ok)
  }

  @Test
  fun `a short recording is held to its window end to end`() {
    val recording = webm(0, 200, 400)
    assertTrue(RecordingTailHold.holdToWindow(recording, windowMs = 6_000L))
    assertEquals(6_000L, packetTimesMs(recording).last())
  }

  @Test
  fun `an mp4 recording is held in its own container`() {
    // A host whose ffmpeg has no libvpx records mp4; its still tail is just as short.
    val recording = mp4(0, 200, 400)
    val before = packetTimesMs(recording).sorted()

    assertTrue(RecordingTailHold.holdToWindow(recording, windowMs = 6_000L))

    val after = packetTimesMs(recording).sorted()
    assertContentEquals(before, after.dropLast(1), "the footage's own frames keep their timestamps")
    assertEquals(6_000L, after.last(), "one held frame, stamped at the stop")
    assertTrue(assertNotNull(VideoDuration.probeMs(recording)) >= 5_900L)
  }

  @Test
  fun `the re-encode keeps the recording's container`() {
    assertEquals(RecordingFormat.WEBM.encodeArgs(), RecordingTailHold.encodeArgsFor(File("video.webm")))
    val mp4 = RecordingTailHold.encodeArgsFor(File("video.mp4"))
    assertTrue("libx264" in mp4, "an mp4 is re-encoded as H.264, not VP9 into an mp4 name")
    assertTrue("passthrough" in mp4, "and keeps the held clone's stamp instead of padding to a constant rate")
  }

  @Test
  fun `the filter moves only the clone`() {
    assertEquals(
      "tpad=stop_mode=clone:stop=1,setpts='if(gte(N,7),12.5/TB,PTS)'",
      RecordingTailHold.filter(frames = 7, untilMs = 12_500L),
    )
  }
}
