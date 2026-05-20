package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for [VideoSpriteExtractor] focused on the duration-mismatch path
 * introduced for the Android `screenrecord` raw-H.264 wrap bug.
 *
 * **Bug reproduction strategy.** We can't easily ship the actual broken Android mp4 as a
 * test fixture (binary, internal-app frames), so we synthesize the same shape of failure
 * deterministically: generate a 10-second testsrc mp4 with real wall-clock timing, then use
 * ffmpeg's `setpts=PTS*0.1` to compress all 150 frames into a 1-second container. The
 * resulting `broken.mp4` mirrors the production failure mode — `duration` reports ~1s while
 * the stream actually represents 10s of recording — and is the exact case the
 * `expectedDurationMs` parameter is designed to repair.
 *
 * Skipped when `ffmpeg` or `ffprobe` is not on PATH so this doesn't fail on hermetic CI
 * agents without the binaries.
 */
class VideoSpriteExtractorTest {

  private lateinit var tempDir: File

  @BeforeTest
  fun setUp() {
    tempDir = Files.createTempDirectory("sprite-extractor-").toFile()
  }

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  @Test
  fun `broken mp4 + expectedDurationMs produces wall-clock-indexed sprite sheet`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val broken = synthesizeBrokenTimingMp4(
      target = File(tempDir, "broken.mp4"),
      realDurationSeconds = 10,
      compressedToSeconds = 1,
      fps = 15,
    )

    // Sanity-check the fixture actually reproduces the bug. Without this, a future ffmpeg
    // behavior change could silently turn the test into a no-op.
    val reportedSeconds = readDurationSeconds(broken) ?: error("ffprobe couldn't read duration")
    assertTrue(
      reportedSeconds < 2.0,
      "fixture should report ~1s duration to mirror the production bug; got ${"%.3f".format(reportedSeconds)}s",
    )

    val expectedDurationMs = 10_000L
    val spriteFps = 2

    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = broken,
      fps = spriteFps,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      expectedDurationMs = expectedDurationMs,
    )

    assertNotNull(spriteFile, "expected a sprite sheet to be produced")
    val meta = File(broken.parentFile, "video_sprites.txt")
    assertTrue(meta.exists(), "expected sprite metadata file")
    val props = meta.readLines().associate {
      val parts = it.split("=", limit = 2)
      parts[0].trim() to parts.getOrElse(1) { "" }.trim()
    }

    // The re-stamp should yield (expectedDurationS × spriteFps) ± 1 frames. Anything close
    // to the broken `duration × spriteFps = 2` would mean the fix didn't take. We allow ±1
    // because ffmpeg's `fps` filter rounding can produce one extra/missing frame at the edges.
    val expectedFrames = (expectedDurationMs / 1000.0 * spriteFps).toInt()
    val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing from metadata: $props")
    assertTrue(
      actualFrames in (expectedFrames - 1)..(expectedFrames + 1),
      "expected ~$expectedFrames sprite frames after re-stamp, got $actualFrames (metadata=$props)",
    )
  }

  @Test
  fun `broken mp4 without expectedDurationMs reproduces the under-sampling bug`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val broken = synthesizeBrokenTimingMp4(
      target = File(tempDir, "broken.mp4"),
      realDurationSeconds = 10,
      compressedToSeconds = 1,
      fps = 15,
    )

    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = broken,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      // No expectedDurationMs — extractor honors the broken container duration.
    )

    assertNotNull(spriteFile)
    val meta = File(broken.parentFile, "video_sprites.txt")
    val props = meta.readLines().associate {
      val parts = it.split("=", limit = 2)
      parts[0].trim() to parts.getOrElse(1) { "" }.trim()
    }
    val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing")
    // With the broken 1-second container and fps=2 sampling, ffmpeg emits ~2 frames. This
    // assertion locks in the bug as a known-broken baseline so a future regression that
    // re-introduces it doesn't silently pass both tests.
    assertTrue(
      actualFrames <= 4,
      "expected the un-corrected path to under-sample (≤4 frames); got $actualFrames",
    )
  }

  @Test
  fun `correctly-timestamped mp4 sprite count is dominated by the spriteFps × duration window`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // 5-second properly-timestamped mp4 (no setpts mangling). Mirrors what iOS/Web produce.
    val healthy = generateHealthyMp4(
      target = File(tempDir, "healthy.mp4"),
      durationSeconds = 5,
      fps = 15,
    )

    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = healthy,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      // Pass the (matching) wall-clock — extractor should not re-stamp since reported ≈ expected.
      expectedDurationMs = 5_000L,
    )

    assertNotNull(spriteFile)
    val meta = File(healthy.parentFile, "video_sprites.txt")
    val props = meta.readLines().associate {
      val parts = it.split("=", limit = 2)
      parts[0].trim() to parts.getOrElse(1) { "" }.trim()
    }
    val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing")
    // 5s × 2fps = 10 sprite frames, ±1 because ffmpeg's `fps` filter rounds frame boundaries
    // differently across versions (and `testsrc` doesn't always emit a clean integer frame at
    // t=durationS). Keep the assertion as a window so this passes uniformly across ffmpeg 4.x
    // and 6.x rather than locking us to whichever happens to land on the current CI agent.
    assertTrue(
      actualFrames in 9..11,
      "expected 9–11 sprite frames from a 5-second healthy mp4 at 2 fps; got $actualFrames",
    )
  }

  @Test
  fun `expectedDurationMs of zero or negative is a no-op`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // Use the broken-timing mp4 so we can tell the difference: WITHOUT a valid
    // expectedDurationMs the extractor honors the container's bogus duration and produces ≤4
    // frames; WITH a valid one it re-stamps to ~20. Passing 0 or a negative value must take
    // the "no-op" branch (returns baseVf without probing), so the result has to match the
    // un-re-stamped behavior — not silently treat the bad value as a legitimate window.
    val broken =
      synthesizeBrokenTimingMp4(
        target = File(tempDir, "broken.mp4"),
        realDurationSeconds = 10,
        compressedToSeconds = 1,
        fps = 15,
      )

    for (badValue in listOf(0L, -1_000L)) {
      // Each iteration writes the same sprite file; clean it so the second iteration starts fresh.
      File(broken.parentFile, "video_sprites.webp").delete()
      File(broken.parentFile, "video_sprites.txt").delete()

      val spriteFile =
        VideoSpriteExtractor.generateSpriteSheet(
          videoFile = broken,
          fps = 2,
          frameHeight = 360,
          webpQuality = 80,
          isLandscape = false,
          expectedDurationMs = badValue,
        )
      assertNotNull(spriteFile, "expected a sprite sheet even with bad expectedDurationMs=$badValue")
      val meta = File(broken.parentFile, "video_sprites.txt")
      val props =
        meta.readLines().associate {
          val parts = it.split("=", limit = 2)
          parts[0].trim() to parts.getOrElse(1) { "" }.trim()
        }
      val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing")
      assertTrue(
        actualFrames <= 4,
        "expectedDurationMs=$badValue should take the no-op branch (≤4 frames, matching the un-corrected baseline); got $actualFrames",
      )
    }
  }

  @Test
  fun `synthetic rate outside sane bounds skips re-stamp`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // Force the clamp guard in `maybeRestamp` to fire: a broken 1-second container with ~17
    // frames against a 1-millisecond `expectedDurationMs` yields a synthetic rate of ~17_000
    // fps — well above the SYNTHETIC_RATE_MAX of 120. The extractor should log the clamp and
    // fall back to the un-re-stamped pipeline rather than emit a degenerate filter chain.
    val broken =
      synthesizeBrokenTimingMp4(
        target = File(tempDir, "broken.mp4"),
        realDurationSeconds = 10,
        compressedToSeconds = 1,
        fps = 15,
      )

    val spriteFile =
      VideoSpriteExtractor.generateSpriteSheet(
        videoFile = broken,
        fps = 2,
        frameHeight = 360,
        webpQuality = 80,
        isLandscape = false,
        // 1ms wall-clock against ~17 frames → ~17000 fps synthetic rate → out of range.
        expectedDurationMs = 1L,
      )

    assertNotNull(spriteFile, "extractor should fall back to un-re-stamped sprite extraction, not crash")
    val meta = File(broken.parentFile, "video_sprites.txt")
    val props =
      meta.readLines().associate {
        val parts = it.split("=", limit = 2)
        parts[0].trim() to parts.getOrElse(1) { "" }.trim()
      }
    val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing")
    // Same baseline as the "no expectedDurationMs" test: the clamp guard short-circuits the
    // re-stamp, so the extractor honors the broken container's 1s duration and produces ≤4
    // sprite frames. If the clamp ever silently passes an extreme rate through, this number
    // would jump dramatically.
    assertTrue(
      actualFrames <= 4,
      "out-of-range synthetic rate should fall back to un-corrected pipeline (≤4 frames); got $actualFrames",
    )
  }

  @Test
  fun `extraction failure writes video_sprites_failed marker`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // A plain-text file named video.mp4: ffmpeg's frame-extraction pass will exit non-zero
    // ("Invalid data found when processing input" or similar), which routes through the
    // helper-migrated failure path and should also call `writeFailureMarker(dir, reason)` so
    // the CI smoke test (`cli_smoke_tests_common.sh`) can surface the reason. The marker is
    // the load-bearing contract between this code and the CI smoke step — verify both that
    // it's written AND that its content includes the per-stage diagnostic.
    val notReallyAVideo = File(tempDir, "video.mp4").apply { writeText("not an mp4") }

    val spriteFile =
      VideoSpriteExtractor.generateSpriteSheet(
        videoFile = notReallyAVideo,
        fps = 2,
        frameHeight = 360,
        webpQuality = 80,
        isLandscape = false,
        expectedDurationMs = 5_000L,
      )
    assertNull(spriteFile, "expected null when the input isn't a valid mp4")

    val markerFile = File(tempDir, VideoSpriteExtractor.FAILURE_MARKER_FILENAME)
    assertTrue(
      markerFile.exists(),
      "expected ${VideoSpriteExtractor.FAILURE_MARKER_FILENAME} to be written on the failure path",
    )
    val markerText = markerFile.readText()
    // The marker should identify the failing stage (extraction or sprite assembly) so an
    // operator triaging CI can tell which step bailed out without re-running locally.
    assertTrue(
      markerText.contains("ffmpeg frame extraction failed") ||
        markerText.contains("ffmpeg ran (exit=0) but produced 0 frames"),
      "marker should contain a recognizable failure stage, got:\n$markerText",
    )
  }

  // ────────────────────────────────────────────────────────────────────────────

  /**
   * Generates a real `mp4` with proper timing — uses `testsrc` so every frame is unique
   * (varying colors and a moving counter). Dedup-friendly fixture: each frame differs from
   * its neighbors, so the sprite extractor's dedup pass doesn't collapse the output and the
   * `frames=N` metadata is the unfiltered ffmpeg-extracted count we want to assert on.
   */
  private fun generateHealthyMp4(target: File, durationSeconds: Int, fps: Int): File {
    val pb = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-f", "lavfi",
      "-i", "testsrc=duration=$durationSeconds:size=320x240:rate=$fps",
      "-c:v", "libx264",
      "-preset", "ultrafast",
      "-pix_fmt", "yuv420p",
      target.absolutePath,
    ).redirectErrorStream(true)
    val process = pb.start()
    process.inputStream.bufferedReader().readText()
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw IllegalStateException("failed to generate healthy mp4 at ${target.absolutePath}")
    }
    return target
  }

  /**
   * Generates a broken-timing mp4: `realDurationSeconds × fps` frames jammed into a
   * `compressedToSeconds`-second container. Mirrors the Android raw-H.264-wrap production
   * bug where 80 seconds of frames end up in a 2-second mp4 because the wrap step had no
   * timing information to work with.
   */
  private fun synthesizeBrokenTimingMp4(
    target: File,
    realDurationSeconds: Int,
    compressedToSeconds: Int,
    fps: Int,
  ): File {
    // Generate a healthy mp4 first, then mangle its PTS so the container duration shrinks.
    val healthy = generateHealthyMp4(File(tempDir, "intermediate.mp4"), realDurationSeconds, fps)
    val ptsScale = compressedToSeconds.toFloat() / realDurationSeconds.toFloat()
    val pb = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-i", healthy.absolutePath,
      "-filter:v", "setpts=PTS*$ptsScale",
      "-c:v", "libx264",
      "-preset", "ultrafast",
      "-pix_fmt", "yuv420p",
      target.absolutePath,
    ).redirectErrorStream(true)
    val process = pb.start()
    process.inputStream.bufferedReader().readText()
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw IllegalStateException("failed to synthesize broken-timing mp4 at ${target.absolutePath}")
    }
    healthy.delete()
    return target
  }

  private fun readDurationSeconds(file: File): Double? {
    val pb = ProcessBuilder(
      "ffprobe",
      "-v", "error",
      "-select_streams", "v:0",
      "-show_entries", "stream=duration",
      "-of", "default=nw=1:nk=1",
      file.absolutePath,
    ).redirectErrorStream(true)
    return try {
      val process = pb.start()
      val output = process.inputStream.bufferedReader().readText().trim()
      if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null
      try {
        java.lang.Double.parseDouble(output)
      } catch (_: NumberFormatException) {
        null
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun ffmpegOnPath(): Boolean = binaryOnPath("ffmpeg")

  private fun ffprobeOnPath(): Boolean = binaryOnPath("ffprobe")

  private fun binaryOnPath(name: String): Boolean = try {
    ProcessBuilder(name, "-version")
      .redirectErrorStream(true)
      .start()
      .let {
        val finished = it.waitFor(5, TimeUnit.SECONDS)
        if (!finished) it.destroyForcibly()
        finished && it.exitValue() == 0
      }
  } catch (_: Exception) {
    false
  }
}
