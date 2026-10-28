package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cuts real recordings with real ffmpeg, the way a loop's failed pass is clipped. */
class VideoSliceTest {

  private lateinit var dir: File

  @BeforeTest
  fun setUp() {
    dir = Files.createTempDirectory("video-slice-").toFile()
  }

  @AfterTest
  fun tearDown() {
    dir.deleteRecursively()
  }

  @Test
  fun `a sparse mp4 keeps its own frames rather than being padded out to a constant rate`() {
    val source = recording("video.mp4", 0, 400, 880, 4_880)
    val output = File(dir, "out/video.mp4").apply { parentFile.mkdirs() }

    assertTrue(VideoSlice.slice(source, offsetMs = 0, durationMs = 3_000, output))

    assertTrue(packetCount(output) <= 4, "got ${packetCount(output)} packets")
  }

  @Test
  fun `a cut whose screen stopped changing early still runs the whole window`() {
    val source = recording("video.webm", 0, 200, 5_000)
    val output = File(dir, "out/video.webm").apply { parentFile.mkdirs() }

    assertTrue(VideoSlice.slice(source, offsetMs = 0, durationMs = 3_000, output))

    val durationMs = VideoDuration.probeMs(output)!!
    assertTrue(durationMs >= 2_900, "ran ${durationMs}ms")
  }

  @Test
  fun `the cut's times are written in ASCII digits whatever the locale`() {
    val default = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("ar-EG"))
      val command = VideoSlice.command(File("in.webm"), offsetMs = 1_500, durationMs = 2_005, File("out.webm"), "ffmpeg")
      assertEquals("1.500", command[command.indexOf("-ss") + 1])
      assertEquals("2.005", command[command.indexOf("-t") + 1])
    } finally {
      Locale.setDefault(default)
    }
  }

  /** A recording in [name]'s container with frames at the given millisecond timestamps, like the live mux writes. */
  private fun recording(name: String, vararg frameMs: Int): File {
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

  private fun packetCount(file: File): Int =
    runSubprocessWithTimeout(
      listOf("ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", file.absolutePath),
      timeoutSeconds = 30,
    )!!.output.trim().toInt()
}
