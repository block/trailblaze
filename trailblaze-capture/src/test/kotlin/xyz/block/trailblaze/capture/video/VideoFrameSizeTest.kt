package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parsing tests for [VideoFrameSize]. The stitch scales every segment onto whatever this reports,
 * so a half-answer read as a real size would build a filter graph ffmpeg rejects — each failure
 * mode has to come back as "no answer" instead.
 */
class VideoFrameSizeTest {

  private lateinit var dir: File

  @BeforeTest
  fun setUp() {
    dir = Files.createTempDirectory("frame-size-").toFile()
  }

  @AfterTest
  fun tearDown() {
    dir.deleteRecursively()
  }

  @Test
  fun `reads width then height from ffprobe's bare output`() {
    assertEquals(VideoFrameSize.Size(1206, 2622), VideoFrameSize.parse("1206\n2622\n"))
  }

  @Test
  fun `ignores blank lines`() {
    assertEquals(VideoFrameSize.Size(720, 1566), VideoFrameSize.parse("\n720\n1566\n"))
  }

  @Test
  fun `a quarter-turn display matrix swaps the reported axes`() {
    // What `simctl io recordVideo` writes for a landscape session: portrait pixels plus a matrix.
    // ffmpeg applies that matrix on decode, so the frames the concat filter actually receives are
    // landscape. Reporting the stored shape would fit every other segment to a canvas that never
    // appears and pillarbox the session.
    assertEquals(VideoFrameSize.Size(2622, 1206), VideoFrameSize.parse("1206\n2622\n90\n"))
    assertEquals(VideoFrameSize.Size(2622, 1206), VideoFrameSize.parse("1206\n2622\n-90\n"))
    assertEquals(VideoFrameSize.Size(2622, 1206), VideoFrameSize.parse("1206\n2622\n270\n"))
  }

  @Test
  fun `a half turn or no rotation leaves the axes alone`() {
    assertEquals(VideoFrameSize.Size(1206, 2622), VideoFrameSize.parse("1206\n2622\n"))
    assertEquals(VideoFrameSize.Size(1206, 2622), VideoFrameSize.parse("1206\n2622\n180\n"))
    assertEquals(VideoFrameSize.Size(1206, 2622), VideoFrameSize.parse("1206\n2622\n0\n"))
  }

  @Test
  fun `probing a rotated recording reports what it decodes to`() {
    if (!ffprobeOnPath() || !ffmpegOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // End to end against a real tagged file, so this survives ffprobe changing how it prints side
    // data — the parse tests above are pinned to today's format and would not notice.
    val rotated = File(dir, "rotated.mp4")
    runCommand(
      listOf(
        "ffmpeg", "-y", "-v", "error",
        "-f", "lavfi", "-i", "testsrc=duration=1:size=240x320:rate=5",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        rotated.absolutePath,
      ),
    )
    val tagged = File(dir, "tagged.mp4")
    runCommand(
      listOf(
        "ffmpeg", "-y", "-v", "error",
        "-display_rotation:v:0", "90",
        "-i", rotated.absolutePath,
        "-c", "copy", tagged.absolutePath,
      ),
    )

    assertEquals(VideoFrameSize.Size(240, 320), VideoFrameSize.probe(rotated))
    assertEquals(
      VideoFrameSize.Size(320, 240),
      VideoFrameSize.probe(tagged),
      "the same pixels tagged for a quarter turn decode landscape",
    )
  }

  private fun runCommand(command: List<String>) {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
      "command failed: ${command.joinToString(" ")}\n$output"
    }
  }

  private fun ffmpegOnPath(): Boolean = binaryOnPath("ffmpeg")

  private fun ffprobeOnPath(): Boolean = binaryOnPath("ffprobe")

  private fun binaryOnPath(name: String): Boolean = try {
    ProcessBuilder(name, "-version").redirectErrorStream(true).start().let {
      val finished = it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
      if (!finished) it.destroyForcibly()
      finished && it.exitValue() == 0
    }
  } catch (_: Exception) {
    false
  }

  @Test
  fun `a file ffprobe could not open has no size`() {
    assertNull(VideoFrameSize.parse(""))
  }

  @Test
  fun `a stream missing a dimension has no size`() {
    assertNull(VideoFrameSize.parse("1206\n"), "width alone is not a size")
    assertNull(VideoFrameSize.parse("1206\nN/A\n"), "an unknown height is not a size")
  }

  @Test
  fun `a zero or negative dimension has no size`() {
    assertNull(VideoFrameSize.parse("0\n2622\n"))
    assertNull(VideoFrameSize.parse("1206\n-2\n"))
  }

  @Test
  fun `an empty or missing file is not probed`() {
    assertNull(VideoFrameSize.probe(File(dir, "absent.webm")))
    assertNull(VideoFrameSize.probe(File(dir, "empty.webm").apply { createNewFile() }))
  }
}
