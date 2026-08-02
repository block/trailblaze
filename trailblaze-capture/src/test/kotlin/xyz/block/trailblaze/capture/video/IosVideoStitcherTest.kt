package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Observable-contract tests for [IosVideoStitcher] that don't need a working ffmpeg: it must write
 * the ffconcat script for the plan, and it must report failure (never throw, never leave a partial
 * artifact the caller would treat as success) when the ffmpeg binary can't run. The gap-preserving
 * arithmetic itself is covered by `IosVideoStitchPlanTest`.
 */
class IosVideoStitcherTest {

  private lateinit var sessionDir: File

  @BeforeTest
  fun setUp() {
    sessionDir = Files.createTempDirectory("ios-stitcher-").toFile()
  }

  @AfterTest
  fun tearDown() {
    sessionDir.deleteRecursively()
  }

  private fun twoSegmentPlan(): IosVideoStitchPlan.StitchPlan =
    IosVideoStitchPlan.plan(
      listOf(
        IosVideoStitchPlan.VideoSegment(File(sessionDir, "baguette.mp4"), 1_000, 3_000),
        IosVideoStitchPlan.VideoSegment(File(sessionDir, "simctl.mp4"), 5_000, 8_000),
      ),
    )!!

  @Test
  fun `writes the ffconcat script and reports failure when ffmpeg cannot run`() {
    val finalMp4 = File(sessionDir, "video.mp4")

    val ok =
      IosVideoStitcher.stitch(
        plan = twoSegmentPlan(),
        sessionDir = sessionDir,
        finalMp4 = finalMp4,
        // A binary that can't start: the shared subprocess runner returns null → stitch is false.
        ffmpegBinary = File(sessionDir, "no-such-ffmpeg").absolutePath,
      )

    assertFalse(ok, "stitch must report failure when ffmpeg can't run")
    assertFalse(finalMp4.exists(), "no partial output should be left behind on a failed stitch")
    val script = File(sessionDir, "video.concat.ffconcat")
    assertTrue(script.exists(), "the ffconcat script is written before the concat is attempted")
    assertTrue(script.readText().startsWith("ffconcat version 1.0"), "script must be a valid ffconcat header")
  }
}
