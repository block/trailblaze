package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [IosScreenRotation].
 *
 * The load-bearing one runs **real** ffmpeg and checks the enum's central claim: that its two ways
 * of expressing a turn — baking it into the pixels with `transpose` for WebM, and declaring it in
 * an mp4 display matrix for the zero-re-encode path — produce the *same displayed frame*.
 *
 * That claim is worth a subprocess because getting it wrong is silent and asymmetric. ffmpeg's
 * `transpose=1`/`transpose=2` and its `-display_rotation` angles run in opposite senses, so a sign
 * error in either direction still yields a plausible landscape recording — just mirrored end for
 * end from the screenshots taken beside it, and nothing downstream compares the two. Comparing
 * decoded pixels is the only check that catches it.
 *
 * Skipped when ffmpeg isn't on PATH so this doesn't fail on a hermetic agent without the binary.
 */
class IosScreenRotationTest {

  private lateinit var workDir: File

  @BeforeTest
  fun setUp() {
    workDir = Files.createTempDirectory("ios-rotation-").toFile()
  }

  @AfterTest
  fun tearDown() {
    workDir.deleteRecursively()
  }

  @Test
  fun `baking a turn into the pixels shows the same frame as declaring it in the container`() {
    if (!ffmpegOnPath()) {
      println("skipping: ffmpeg not on PATH")
      return
    }
    // Portrait, like every iOS framebuffer. `testsrc` is asymmetric in both axes, so a frame turned
    // the wrong way round does not hash equal to one turned the right way.
    val portrait = generatePortraitVideo(File(workDir, "portrait.mp4"))

    IosScreenRotation.entries.filter { it != IosScreenRotation.NONE }.forEach { rotation ->
      val baked = decodeFrame(
        File(workDir, "baked-$rotation.png"),
        inputArgs = listOf("-i", portrait.absolutePath),
        outputArgs = listOf("-vf", rotation.transposeFilter!!),
      )
      val declared = decodeFrame(
        File(workDir, "declared-$rotation.png"),
        inputArgs = listOf(
          "-display_rotation:v:0",
          rotation.displayRotationDegrees.toString(),
          "-i",
          portrait.absolutePath,
        ),
        outputArgs = emptyList(),
      )
      assertEquals(
        baked,
        declared,
        "$rotation: the transpose filter and the ${rotation.displayRotationDegrees}° display " +
          "matrix must show the same frame, or a WebM recording and an mp4 one of the same " +
          "session disagree about which way up it was",
      )
    }
  }

  @Test
  fun `the two landscape turns are not interchangeable`() {
    if (!ffmpegOnPath()) {
      println("skipping: ffmpeg not on PATH")
      return
    }
    // Guards the test above from passing on a degenerate fixture: if the source were symmetric,
    // every turn would hash equal and the comparison would prove nothing.
    val portrait = generatePortraitVideo(File(workDir, "portrait.mp4"))
    val clockwise = decodeFrame(
      File(workDir, "cw.png"),
      inputArgs = listOf("-i", portrait.absolutePath),
      outputArgs = listOf("-vf", IosScreenRotation.CLOCKWISE_90.transposeFilter!!),
    )
    val counterClockwise = decodeFrame(
      File(workDir, "ccw.png"),
      inputArgs = listOf("-i", portrait.absolutePath),
      outputArgs = listOf("-vf", IosScreenRotation.COUNTER_CLOCKWISE_90.transposeFilter!!),
    )
    assertNotEquals(clockwise, counterClockwise, "the fixture must be able to tell the turns apart")
  }

  @Test
  fun `an unrotated screen asks for no filter and no tag`() {
    assertNull(IosScreenRotation.NONE.transposeFilter, "NONE must add no filter — it costs an encode")
    assertEquals(0, IosScreenRotation.NONE.displayRotationDegrees)
  }

  @Test
  fun `only the quarter turns swap the frame's axes`() {
    // Consumers size the output canvas off this, so a half turn claiming to swap would transpose
    // the whole session's dimensions.
    assertTrue(IosScreenRotation.CLOCKWISE_90.swapsAxes)
    assertTrue(IosScreenRotation.COUNTER_CLOCKWISE_90.swapsAxes)
    assertTrue(!IosScreenRotation.NONE.swapsAxes)
    assertTrue(!IosScreenRotation.HALF_TURN.swapsAxes)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  private fun generatePortraitVideo(target: File): File {
    run(
      listOf(
        "ffmpeg", "-y",
        "-f", "lavfi",
        "-i", "testsrc=duration=1:size=240x320:rate=5",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        target.absolutePath,
      ),
    )
    return target
  }

  /** Decodes one frame and returns a digest of its raw pixels, so two frames compare by content. */
  private fun decodeFrame(target: File, inputArgs: List<String>, outputArgs: List<String>): String {
    run(listOf("ffmpeg", "-y", "-v", "error") + inputArgs + outputArgs + listOf("-frames:v", "1", target.absolutePath))
    val process = ProcessBuilder(
      listOf("ffmpeg", "-v", "error", "-i", target.absolutePath, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"),
    ).start()
    val pixels = process.inputStream.readBytes()
    if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw IOException("failed to read pixels from ${target.name}")
    }
    check(pixels.isNotEmpty()) { "decoded no pixels from ${target.name}" }
    return MessageDigest.getInstance("SHA-256").digest(pixels).joinToString("") { "%02x".format(it) }
  }

  private fun run(command: List<String>) {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw IOException("command failed: ${command.joinToString(" ")}\n$output")
    }
  }

  private fun ffmpegOnPath(): Boolean = try {
    ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().let {
      val finished = it.waitFor(5, TimeUnit.SECONDS)
      if (!finished) it.destroyForcibly()
      finished && it.exitValue() == 0
    }
  } catch (_: Exception) {
    false
  }
}
