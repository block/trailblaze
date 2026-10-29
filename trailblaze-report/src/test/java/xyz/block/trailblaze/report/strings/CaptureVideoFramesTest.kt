package xyz.block.trailblaze.report.strings

import kotlinx.datetime.Instant
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs a real ffmpeg over a recording built to make the wrong frame visible: it is sparse, the way
 * a recording of a UI is, with a red screen from 0s, green from 1s and blue from 3s. A capture at
 * 2.9s was taken while the screen was green; the NEXT frame — what a seek lands on — is blue.
 *
 * Skipped when `ffmpeg`/`ffprobe` are not on PATH.
 */
class CaptureVideoFramesTest {

  @get:Rule val tmp = TemporaryFolder()

  private val session = SessionId("2026_09_29_frames")
  private val t0 = Instant.parse("2026-09-29T12:00:00Z")

  private fun capture(id: String, atMs: Long, width: Int = 64, height: Int = 128, screenshot: String? = null) =
    TrailblazeLog.AgentDriverLog(
      viewHierarchy = null,
      trailblazeNodeTree = null,
      screenshotFile = screenshot,
      action = AgentDriverAction.BackPress,
      durationMs = 10,
      session = session,
      timestamp = Instant.fromEpochMilliseconds(t0.toEpochMilliseconds() + atMs),
      deviceHeight = height,
      deviceWidth = width,
      captureId = id,
    )

  /** A session dir holding the red/green/blue recording, stamped with the window [windowMs]. */
  private fun sessionWithRecording(windowMs: Long = 4_000, vararg more: Pair<String, Long>): File {
    val dir = tmp.newFolder()
    fun still(color: String) = File(dir, "$color.png").also {
      run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=$color:s=64x128", "-frames:v", "1", it.absolutePath)
    }
    val concat = File(dir, "stills.txt")
    concat.writeText(
      listOf("red" to 1, "green" to 2, "blue" to 1).joinToString("") { (color, seconds) ->
        "file '${still(color).absolutePath}'\nduration $seconds\n"
      } + "file '${File(dir, "blue.png").absolutePath}'\n",
    )
    run(
      "ffmpeg", "-v", "error", "-f", "concat", "-safe", "0", "-i", concat.absolutePath,
      "-fps_mode", "vfr", "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p",
      File(dir, "video.mp4").absolutePath,
    )
    listOf("red.png", "green.png", "blue.png", "stills.txt").forEach { File(dir, it).delete() }
    val start = t0.toEpochMilliseconds()
    val entries = listOf("video.mp4" to windowMs) + more
    File(dir, "capture_metadata.json").writeText(
      entries.joinToString(",", """{"artifacts":[""", "]}") { (name, window) ->
        """{"filename":"$name","type":"VIDEO","startTimestampMs":$start,"endTimestampMs":${start + window}}"""
      },
    )
    return dir
  }

  private fun decode(file: File): BufferedImage = ImageIO.read(file) ?: error("${file.name} is not a readable image")

  private fun colorOf(file: File): String {
    val image = decode(file)
    val rgb = image.getRGB(image.width / 2, image.height / 2)
    val channels = mapOf("red" to (rgb shr 16 and 0xFF), "green" to (rgb shr 8 and 0xFF), "blue" to (rgb and 0xFF))
    return channels.maxBy { it.value }.key
  }

  @Test
  fun `each capture gets the frame on screen at its instant, not the next one`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    val dir = sessionWithRecording()

    val result = CaptureVideoFrames.fill(
      dir,
      listOf(capture("capture-a", 500), capture("capture-b", 2_900), capture("capture-c", 3_500)),
    )

    assertEquals(3, result.written, "missed: ${result.missed}")
    assertEquals("red", colorOf(File(dir, "capture-a.webp")))
    assertEquals("green", colorOf(File(dir, "capture-b.webp")), "2.9s is before blue appears at 3s")
    assertEquals("blue", colorOf(File(dir, "capture-c.webp")))
    assertEquals(
      mapOf("capture-a" to "capture-a.webp", "capture-b" to "capture-b.webp", "capture-c" to "capture-c.webp"),
      result.frames,
    )
    assertFalse(dir.listFiles()!!.any { it.name.startsWith(".video-frames") }, "work dir is removed")
  }

  @Test
  fun `a frame is saved as a screenshot is, a WebP scaled to fit the screenshot box`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    // A phone-sized recording, the size of the farm's: 1080x2340 is past the 1536x768 box.
    val dir = tmp.newFolder()
    run(
      "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=green:s=1080x2340:d=2", "-c:v", "mpeg4",
      "-pix_fmt", "yuv420p", File(dir, "video.mp4").absolutePath,
    )
    val start = t0.toEpochMilliseconds()
    File(dir, "capture_metadata.json").writeText(
      """{"artifacts":[{"filename":"video.mp4","type":"VIDEO","startTimestampMs":$start,"endTimestampMs":${start + 2_000}}]}""",
    )

    val result = CaptureVideoFrames.fill(dir, listOf(capture("capture-phone", 500, width = 1080, height = 2340)))

    val frame = File(dir, "capture-phone.webp")
    assertEquals(1, result.written, "missed: ${result.missed}")
    val header = frame.readBytes()
    assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
    assertEquals("WEBP", String(header, 8, 4, Charsets.US_ASCII))
    val image = decode(frame)
    assertEquals(708 to 1536, image.width to image.height)
    assertEquals("green", colorOf(frame))
  }

  @Test
  fun `a run instant is scaled onto the recording, not offset into it`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    // The recorder's window is twice the file's length, so 5.8s into the window is 2.9s into the
    // file: green. An offset would ask for 5.8s, past the end.
    val dir = sessionWithRecording(windowMs = 8_000)

    val result = CaptureVideoFrames.fill(dir, listOf(capture("capture-b", 5_800)))

    assertEquals("green", colorOf(File(dir, "capture-b.webp")), "missed: ${result.missed}")
  }

  @Test
  fun `a recording with no end stamp covers as long as its file runs, in real time`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    val dir = sessionWithRecording()
    File(dir, "capture_metadata.json").writeText(
      """{"artifacts":[{"filename":"video.mp4","type":"VIDEO","startTimestampMs":${t0.toEpochMilliseconds()},"endTimestampMs":null}]}""",
    )

    val result = CaptureVideoFrames.fill(dir, listOf(capture("capture-b", 2_900), capture("capture-late", 4_500)))

    assertEquals("green", colorOf(File(dir, "capture-b.webp")), "missed: ${result.missed}")
    assertEquals(mapOf("capture-late" to CaptureVideoFrames.Miss.NOT_RECORDED), result.missed)
  }

  @Test
  fun `a webm that cannot be read falls back to the same recording's mp4`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    val dir = sessionWithRecording()
    File(dir, "video.webm").writeText("not a video")
    File(dir, "capture_metadata.json").writeText(
      File(dir, "capture_metadata.json").readText().replace(
        "]}",
        """,{"filename":"video.webm","type":"VIDEO_WEBM","startTimestampMs":${t0.toEpochMilliseconds()},""" +
          """"endTimestampMs":${t0.toEpochMilliseconds() + 4_000}}]}""",
      ),
    )

    val result = CaptureVideoFrames.fill(dir, listOf(capture("capture-b", 2_900)))

    assertEquals("green", colorOf(File(dir, "capture-b.webp")), "missed: ${result.missed}")
  }

  @Test
  fun `a capture two recordings cover is left alone, since nothing says which screen it was`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    val dir = sessionWithRecording()
    File(dir, "video.mp4").copyTo(File(dir, "video-buyer.mp4"))
    File(dir, "capture_metadata.json").writeText(
      File(dir, "capture_metadata.json").readText().replace(
        "]}",
        """,{"filename":"video-buyer.mp4","type":"VIDEO","startTimestampMs":${t0.toEpochMilliseconds()},""" +
          """"endTimestampMs":${t0.toEpochMilliseconds() + 4_000},"deviceName":"buyer"}]}""",
      ),
    )

    val result = CaptureVideoFrames.fill(dir, listOf(capture("capture-b", 2_900)))

    assertEquals(mapOf("capture-b" to CaptureVideoFrames.Miss.AMBIGUOUS_RECORDING), result.missed)
    assertFalse(File(dir, "capture-b.webp").exists())
  }

  @Test
  fun `a capture outside the recording, or turned from it, gets no frame`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    val dir = sessionWithRecording()

    val result = CaptureVideoFrames.fill(
      dir,
      listOf(
        capture("capture-late", 4_500),
        capture("capture-early", -100),
        capture("capture-landscape", 500, width = 128, height = 64),
      ),
    )

    assertEquals(
      mapOf(
        "capture-late" to CaptureVideoFrames.Miss.NOT_RECORDED,
        "capture-early" to CaptureVideoFrames.Miss.NOT_RECORDED,
        "capture-landscape" to CaptureVideoFrames.Miss.ROTATED,
      ),
      result.missed,
    )
    assertEquals(emptyList(), dir.listFiles()!!.filter { it.extension == "webp" })
  }

  @Test
  fun `a frame already saved is kept and named, and a capture with a screenshot needs none`() {
    val dir = tmp.newFolder()
    File(dir, "capture-a.webp").writeText("earlier")

    val result = CaptureVideoFrames.fill(
      dir,
      listOf(capture("capture-a", 500), capture("capture-s", 600, screenshot = "shot.webp")),
      // Nothing may be probed or decoded: the one frame needed is already there.
      ffprobeBinary = File(dir, "no-such-ffprobe").absolutePath,
      ffmpegBinary = File(dir, "no-such-ffmpeg").absolutePath,
    )

    assertEquals(mapOf("capture-a" to "capture-a.webp"), result.frames)
    assertEquals(0, result.written)
    assertEquals("earlier", File(dir, "capture-a.webp").readText())
  }

  @Test
  fun `a second pass over the same session opens no recording and rewrites no frame`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    val dir = sessionWithRecording()
    val logs = listOf(capture("capture-a", 500), capture("capture-b", 2_900))
    assertEquals(2, CaptureVideoFrames.fill(dir, logs).written)
    val before = listOf("capture-a.webp", "capture-b.webp").associateWith { File(dir, it).readBytes().toList() }

    val calls = tmp.newFile("second-pass-calls.log")
    val again = CaptureVideoFrames.fill(dir, logs, ffprobeBinary = spy("ffprobe", calls), ffmpegBinary = spy("ffmpeg", calls))

    assertEquals("", calls.readText(), "nothing is probed or decoded when every frame is already saved")
    assertEquals(0, again.written)
    assertEquals(mapOf("capture-a" to "capture-a.webp", "capture-b" to "capture-b.webp"), again.frames)
    assertEquals(before, before.keys.associateWith { File(dir, it).readBytes().toList() })
  }

  @Test
  fun `only the capture still missing a frame is decoded, and a saved one is left as it was`() {
    assumeTrue("ffmpeg and ffprobe on PATH", onPath("ffmpeg") && onPath("ffprobe"))
    val dir = sessionWithRecording()
    File(dir, "capture-a.webp").writeText("saved by an earlier report")

    val result = CaptureVideoFrames.fill(dir, listOf(capture("capture-a", 500), capture("capture-b", 2_900)))

    assertEquals(1, result.written)
    assertEquals("saved by an earlier report", File(dir, "capture-a.webp").readText())
    assertEquals("green", colorOf(File(dir, "capture-b.webp")))
  }

  @Test
  fun `a session still recording has no capture metadata yet, so its video is never opened`() {
    // The recorder publishes capture_metadata.json only once it stops, so a report built while
    // the session runs sees a video file with no window, and leaves it alone.
    val dir = tmp.newFolder()
    File(dir, "video.webm").writeText("still being written")
    val calls = tmp.newFile("in-progress-calls.log")

    val result = CaptureVideoFrames.fill(
      dir,
      listOf(capture("capture-a", 500)),
      ffprobeBinary = spy("ffprobe", calls),
      ffmpegBinary = spy("ffmpeg", calls),
    )

    assertEquals(mapOf("capture-a" to CaptureVideoFrames.Miss.NOT_RECORDED), result.missed)
    assertEquals("", calls.readText())
  }

  @Test
  fun `a session whose captures all have screenshots never reads its recording`() {
    val dir = tmp.newFolder()
    File(dir, "capture_metadata.json").writeText("not even valid json")
    val calls = tmp.newFile("all-shots-calls.log")

    val result = CaptureVideoFrames.fill(
      dir,
      listOf(capture("capture-s", 600, screenshot = "shot.webp")),
      ffprobeBinary = spy("ffprobe", calls),
      ffmpegBinary = spy("ffmpeg", calls),
    )

    assertEquals(CaptureVideoFrames.Result(emptyMap(), 0, emptyMap()), result)
    assertEquals("", calls.readText())
  }

  @Test
  fun `a capture id that is not a plain file name never names a file`() {
    val dir = tmp.newFolder()

    val result = CaptureVideoFrames.fill(dir, listOf(capture("../escape", 500)))

    assertEquals(mapOf("../escape" to CaptureVideoFrames.Miss.UNSAFE_ID), result.missed)
  }

  @Test
  fun `a recording named outside the session dir is not read`() {
    val dir = tmp.newFolder()
    File(dir.parentFile, "outside.mp4").writeText("not a video")
    File(dir, "capture_metadata.json").writeText(
      """{"artifacts":[{"filename":"../outside.mp4","type":"VIDEO","startTimestampMs":0,"endTimestampMs":10}]}""",
    )

    assertEquals(emptyList(), CaptureVideoFrames.recordingsIn(dir))
  }

  @Test
  fun `the webm of a recording is tried before its mp4`() {
    val dir = tmp.newFolder()
    File(dir, "video.mp4").writeText("mp4")
    File(dir, "video.webm").writeText("webm")
    File(dir, "capture_metadata.json").writeText(
      """{"artifacts":[""" +
        """{"filename":"video.mp4","type":"VIDEO","startTimestampMs":0,"endTimestampMs":10},""" +
        """{"filename":"video.webm","type":"VIDEO_WEBM","startTimestampMs":0,"endTimestampMs":10}]}""",
    )

    assertEquals(listOf(listOf("video.webm", "video.mp4")), CaptureVideoFrames.recordingsIn(dir).map { it.files.map(File::getName) })
  }

  /** A stand-in for ffprobe/ffmpeg that records each call in [calls] and fails, to prove whether it ran. */
  private fun spy(name: String, calls: File): String = File(tmp.newFolder(), name).apply {
    writeText("#!/bin/sh\necho \"$name \$*\" >> '${calls.absolutePath}'\nexit 1\n")
    setExecutable(true)
  }.absolutePath

  // Output goes to a file rather than a pipe, so the timeout holds even for a process that never
  // closes its stdout.
  private fun run(vararg command: String) {
    val log = tmp.newFile()
    val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(log).start()
    val finished = process.waitFor(60, TimeUnit.SECONDS)
    if (!finished) process.destroyForcibly()
    assertTrue(finished && process.exitValue() == 0, "${command.toList()}: ${log.readText()}")
  }

  private fun onPath(name: String): Boolean = try {
    val process = ProcessBuilder(name, "-version").redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    // A tool that is installed but slow to answer on a loaded agent must fail the test, not skip it:
    // only a tool that isn't there skips. AssertionError is not an Exception, so it gets out.
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw AssertionError("$name -version did not answer within 60s")
    }
    process.exitValue() == 0
  } catch (_: Exception) {
    false
  }
}
