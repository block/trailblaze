package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
  fun `tiny single-frame mp4 still yields a sprite sheet via the low-rate re-stamp path`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // Reproduces the CI smoke failure mode: a fresh default-config Android
    // emulator (320x640, headless, no compositor activity) recorded for ~11 wall-clock seconds
    // produces an h264 stream with a single IDR frame and nothing else, which the `-c copy`
    // mp4 wrap stamps as a 0.04-second, 1-frame container. The previous synthetic-rate clamp
    // at 0.1 fps rejected the (1 frame / 11 s ≈ 0.09 fps) re-stamp; the un-re-stamped pipeline
    // then sampled at fps=2 from a 0.04-second timeline and produced zero PNG frames. Lowering
    // the clamp lets the re-stamp run, ffmpeg duplicates the single frame across the wall-clock
    // window, and dedup collapses the result back to one unique sprite frame.
    val crushed = synthesizeTinySingleFrameMp4(File(tempDir, "tiny_crushed.mp4"))

    // Sanity-check the fixture matches the real CI failure shape — without this guard a future
    // ffmpeg release that changes setpts rounding could let the test pass without exercising
    // the low-rate path we're actually testing. The CI signature is `nb_frames=1` *and*
    // `duration≈0.04s`; assert both so a drift in either dimension surfaces here rather than
    // silently weakening the test into "any short mp4 works."
    val reportedSeconds = readDurationSeconds(crushed) ?: error("ffprobe couldn't read duration")
    val reportedFrames = readNbFrames(crushed) ?: error("ffprobe couldn't read nb_frames")
    assertEquals(
      1L,
      reportedFrames,
      "fixture should report nb_frames=1 to mirror the CI single-IDR-frame failure mode",
    )
    assertTrue(
      reportedSeconds in 0.01..0.5,
      "fixture should report ~0.04s duration to mirror the CI bug; got ${"%.3f".format(reportedSeconds)}s",
    )

    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = crushed,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      // 11 seconds of wall-clock matches the smoke test's blaze run window.
      expectedDurationMs = 11_000L,
    )

    assertNotNull(
      spriteFile,
      "low-activity recording with a single frame should still yield a sprite sheet, not bail with " +
        "'ffmpeg ran (exit=0) but produced 0 frames'",
    )
    val meta = File(crushed.parentFile, "video_sprites.txt")
    val props =
      meta.readLines().associate {
        val parts = it.split("=", limit = 2)
        parts[0].trim() to parts.getOrElse(1) { "" }.trim()
      }
    val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing")
    // With the re-stamp applied, ffmpeg's fps=2 filter across the 11-second window emits ~22
    // duplicates of the single source frame. The exact count depends on ffmpeg's rounding at
    // edges; assert ≥ 5 so a regression that re-tightens the clamp drops back to ≤ 1 and
    // visibly fails this assertion.
    assertTrue(
      actualFrames >= 5,
      "expected ≥5 sprite frames after low-rate re-stamp; got $actualFrames (metadata=$props)",
    )
    val uniqueFrames = props["uniqueFrames"]?.toIntOrNull() ?: error("uniqueFrames missing")
    assertTrue(
      uniqueFrames in 1..2,
      "the single source frame should dedup to 1 (or rarely 2) unique sprite; got $uniqueFrames",
    )
  }

  @Test
  fun `short wall-clock window with broken-timing mp4 still yields a sprite sheet`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // Reproduces the CLI smoke regression on the Android shards (build 8290+): the daemon-
    // driven `trailblaze blaze "Describe what's on screen"` finishes in roughly one wall-clock
    // second on a static-screen AVD, so the recorder hands the extractor an mp4 with
    // `nb_frames=1, duration=0.04s` together with `expectedDurationMs=~1000`. The original
    // tolerance check (|0.04 - 1.0| = 0.96 ≤ 2.0 floor) classified that pair as "healthy
    // wobble" and skipped the re-stamp; the un-re-stamped `fps=2` pipeline then sampled a
    // 0.04s timeline and produced zero PNG frames — the `video_sprites.webp missing` failure.
    // The fix adds a ratio-based bound on top of the absolute floor so a 25× duration
    // disagreement still triggers the re-stamp.
    val crushed = synthesizeTinySingleFrameMp4(File(tempDir, "tiny_crushed.mp4"))

    // Sanity-check the fixture before it's load-bearing: without this guard a future ffmpeg
    // behavior change could quietly drift the synthesized mp4 away from the CI shape (1 frame,
    // ~0.04s) and the test would still pass for the wrong reason. Mirrors the assertion the
    // sibling `tiny single-frame mp4 …` test runs over the same fixture.
    val reportedSeconds = readDurationSeconds(crushed) ?: error("ffprobe couldn't read duration")
    val reportedFrames = readNbFrames(crushed) ?: error("ffprobe couldn't read nb_frames")
    assertEquals(
      1L,
      reportedFrames,
      "fixture should report nb_frames=1 to mirror the CI single-IDR-frame failure mode",
    )
    assertTrue(
      reportedSeconds in 0.01..0.5,
      "fixture should report ~0.04s duration to mirror the CI bug; got ${"%.3f".format(reportedSeconds)}s",
    )

    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = crushed,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      // 1 second wall-clock — small enough that the absolute tolerance floor (2s) used to
      // swallow the broken timing on its own.
      expectedDurationMs = 1_000L,
    )

    assertNotNull(
      spriteFile,
      "short-wall-clock recording with broken-timing mp4 should still yield a sprite sheet — " +
        "this is the CI smoke regression that the ratio-bound fix addresses",
    )
    val meta = File(crushed.parentFile, "video_sprites.txt")
    val props =
      meta.readLines().associate {
        val parts = it.split("=", limit = 2)
        parts[0].trim() to parts.getOrElse(1) { "" }.trim()
      }
    val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing")
    // With the re-stamp applied, ffmpeg's setpts+fps chain duplicates the single source frame
    // across the 1-second wall-clock window at 2fps → at least 2 frames. Assert ≥2 so a
    // regression that silently re-introduces the tolerance hole drops back to 0 or 1.
    assertTrue(
      actualFrames >= 2,
      "expected ≥2 sprite frames after restamp across 1s wall-clock window; got $actualFrames (metadata=$props)",
    )
  }

  @Test
  fun `healthy ratio just above the 0_5 floor takes the no-restamp branch`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // Pins the *healthy side* of the duration-mismatch ratio bound. A 2-second healthy mp4
    // against a 3-second wall-clock expectation has:
    //   absDiff = |2.0 - 3.0| = 1.0  ≤  tolerance (floor 2.0s) → absolute check passes
    //   ratio = 2 / 3 = 0.667        ≥  0.5 (DURATION_MISMATCH_RATIO_MIN) → ratio check passes
    // Both clear the threshold, so maybeRestamp returns baseVf and the sprite extraction
    // takes the un-re-stamped path: fps=2 over a 2-second timeline ≈ 4 frames. A re-stamp
    // would spread the source's 30 frames over the 3-second expected window and emit ≈ 6
    // frames at fps=2, so a regression that tightens `DURATION_MISMATCH_RATIO_MIN` to 0.7+
    // would push the frame count above this assertion.
    //
    // Without this test, the only ratio-bound coverage is the extreme case (ratio 0.04 in
    // `short wall-clock window …`), so a future change that flips the threshold to 0.7
    // would still be caught only on the broken side, leaving the healthy side
    // silently broken.
    val healthy = generateHealthyMp4(File(tempDir, "healthy_ratio.mp4"), durationSeconds = 2, fps = 15)

    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = healthy,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      expectedDurationMs = 3_000L,
    )

    assertNotNull(spriteFile)
    val meta = File(healthy.parentFile, "video_sprites.txt")
    val props = meta.readLines().associate {
      val parts = it.split("=", limit = 2)
      parts[0].trim() to parts.getOrElse(1) { "" }.trim()
    }
    val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing from metadata: $props")
    // 2s × 2fps = 4 frames, ±1 for ffmpeg edge rounding. The un-re-stamped baseline. If
    // restamp had fired we'd see ~6 frames; the upper bound at 5 catches that regression
    // without making the test brittle against legit ffmpeg rounding drift.
    assertTrue(
      actualFrames in 3..5,
      "expected ~4 un-re-stamped sprite frames (no restamp on ratio=0.667); got $actualFrames (metadata=$props)",
    )
  }

  @Test
  fun `single-frame fallback recovers when fps sampling and probe both bail`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // Reproduces the live Android CI smoke regression where the diagnostic block showed
    // `probe=unavailable` *and* `vf=fps=2,scale=-2:720` produced 0 frames: the
    // primary `fps=N` extraction can't find any samples on a sparse-timing mp4 even though
    // the stream is decodable. Without the fallback the run aborts with the marker; with the
    // fallback we recover a single static sprite frame, which is enough for the smoke step's
    // `assert video_sprites.webp exists` check to pass.
    //
    // The 0.04-second single-frame fixture combined with `expectedDurationMs = null` takes
    // the no-op branch in maybeRestamp, leaving `fps=2,scale=...` to extract from a 0.04s
    // timeline — ffmpeg exits 0 but emits no PNG frames in the primary pass, so the
    // single-frame fallback is the only thing that can succeed here.
    val crushed = synthesizeTinySingleFrameMp4(File(tempDir, "tiny_crushed.mp4"))

    // Sanity-check the fixture — same shape as the sibling tests so a future ffmpeg drift
    // surfaces here rather than weakening the assertion silently.
    val reportedFrames = readNbFrames(crushed) ?: error("ffprobe couldn't read nb_frames")
    assertEquals(1L, reportedFrames, "fixture should report nb_frames=1 to mirror the CI failure mode")

    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = crushed,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      // Intentionally null so the un-re-stamped pipeline runs and the primary fps=2 produces
      // 0 frames, forcing the fallback to take over.
      expectedDurationMs = null,
    )
    assertNotNull(
      spriteFile,
      "expected the single-frame fallback to recover a sprite sheet when fps sampling " +
        "produces 0 frames — this is the CI smoke regression behavior the fallback is meant to fix",
    )
    val meta = File(crushed.parentFile, "video_sprites.txt")
    val props = meta.readLines().associate {
      val parts = it.split("=", limit = 2)
      parts[0].trim() to parts.getOrElse(1) { "" }.trim()
    }
    val actualFrames = props["frames"]?.toIntOrNull() ?: error("frames missing from metadata: $props")
    assertEquals(
      1,
      actualFrames,
      "fallback path should yield exactly one sprite frame; got $actualFrames (metadata=$props)",
    )
    // No failure marker should be written on the recovered path — the fallback's job is to
    // turn what was a hard failure into a successful (if static) sprite.
    val markerFile = File(crushed.parentFile, VideoSpriteExtractor.FAILURE_MARKER_FILENAME)
    assertTrue(
      !markerFile.exists(),
      "no failure marker should be written when the fallback recovers; got:\n" +
        (if (markerFile.exists()) markerFile.readText() else ""),
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
    // And the diagnostic block — expectedDurationMs + the chosen vf chain — needs to be in
    // the marker too. CI silences `Console.log`, so this block is the only thing that
    // survives an artifact snapshot when an operator tries to debug a sprite-extraction
    // failure post-hoc.
    assertTrue(
      markerText.contains("expectedDurationMs=5000"),
      "marker should include expectedDurationMs for post-hoc diagnosis, got:\n$markerText",
    )
    assertTrue(
      markerText.contains("vf="),
      "marker should include the chosen ffmpeg -vf chain so it's clear whether restamp fired, got:\n$markerText",
    )
  }

  @Test
  fun `K1-style broken-timing mp4 over a long session trips the broken-mp4 gate`() {
    // Reproduces the K1 CI pathology (trailblaze-ios-pr build 11092). The CI mp4 there
    // claimed 2 seconds of duration for an 87-second test; ffmpeg sampled the truncated
    // timeline directly (re-stamp didn't fire) and emitted only 4 sprite frames vs the
    // ~174 the wall-clock window called for. We synthesize the same shape by feeding the
    // tiny single-frame fixture (0.04s container, 1 frame — the existing CI smoke fixture)
    // with a *much* longer expected window. With nbFrames=1 and expectedS=1000s,
    // syntheticRate = 1/1000 = 0.001, which sits below SYNTHETIC_RATE_MIN (0.005) — the
    // upstream clamp trips, re-stamp is skipped, and the un-re-stamped pipeline yields a
    // single sprite frame via the existing single-frame fallback. Against the 2000 expected
    // frames (1000s × 2fps), 1/2000 ≈ 0.05% coverage is far below the gate's 10% threshold
    // and well above the gate's 60-expected-frame floor — so the broken-mp4 marker fires.
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val tiny = synthesizeTinySingleFrameMp4(File(tempDir, "tiny_broken.mp4"))
    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = tiny,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      expectedDurationMs = 1_000_000L,
    )
    assertNull(
      spriteFile,
      "broken-mp4 gate should suppress sprite emission when extraction can't fill the wall-clock window",
    )

    val brokenMarker = File(tempDir, VideoSpriteExtractor.MP4_BROKEN_MARKER_FILENAME)
    assertTrue(
      brokenMarker.exists(),
      "expected ${VideoSpriteExtractor.MP4_BROKEN_MARKER_FILENAME} so platform callers can skip VIDEO fallback",
    )
    assertTrue(
      VideoSpriteExtractor.isMp4DetectedAsBroken(tempDir),
      "isMp4DetectedAsBroken() should report true when the marker is present",
    )
    val markerText = brokenMarker.readText()
    assertTrue(
      markerText.contains("expected ~"),
      "marker should record the expected-vs-actual coverage so triage doesn't need to re-derive it; got:\n$markerText",
    )
    // The diag file should *also* be present — the broken-mp4 gate is a refinement of the
    // probe + re-stamp decision tree, so the diagnostic block telling us what ffprobe saw
    // and which vf chain ffmpeg ran rides along in the same session dir.
    assertTrue(
      File(tempDir, VideoSpriteExtractor.DIAG_FILENAME).exists(),
      "expected ${VideoSpriteExtractor.DIAG_FILENAME} alongside the broken-mp4 marker",
    )
  }

  @Test
  fun `healthy mp4 with full coverage does not trip the broken-mp4 gate`() {
    // Counterpart to the gate-trips test: when the source mp4's actual frame count matches
    // (or exceeds) what the wall-clock window calls for, the gate must not fire. Otherwise
    // every healthy long session would lose its sprite sheet.
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val healthy = generateHealthyMp4(
      target = File(tempDir, "healthy.mp4"),
      durationSeconds = 30,
      fps = 30,
    )
    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = healthy,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      expectedDurationMs = 30_000L,
    )
    assertNotNull(spriteFile, "healthy 30s mp4 should produce a sprite sheet")
    assertTrue(
      !File(tempDir, VideoSpriteExtractor.MP4_BROKEN_MARKER_FILENAME).exists(),
      "broken-mp4 marker must not be present for healthy captures",
    )
  }

  @Test
  fun `isMp4DetectedAsBroken reflects marker file presence`() {
    // Directly exercise the predicate platform wrappers and report-generation depend on.
    // Without this, the contract is only covered transitively through generateSpriteSheet.
    assertTrue(
      !VideoSpriteExtractor.isMp4DetectedAsBroken(tempDir),
      "fresh tempDir has no marker — predicate must return false",
    )
    assertTrue(
      !VideoSpriteExtractor.isMp4DetectedAsBroken(null),
      "null dir (e.g. file.parentFile at filesystem root) must return false rather than NPE",
    )
    File(tempDir, VideoSpriteExtractor.MP4_BROKEN_MARKER_FILENAME).writeText("test marker")
    assertTrue(
      VideoSpriteExtractor.isMp4DetectedAsBroken(tempDir),
      "marker present — predicate must return true",
    )
  }

  @Test
  fun `shouldSkipVideoFallbackForBrokenMp4 short-circuits when marker is absent or dir is null`() {
    // The shared helper platform wrappers (Android / iOS / Playwright) call after a null
    // sprite return. It must return false on the healthy path (sprite gen failed for some
    // unrelated infra reason) so callers fall back to emitting VIDEO as before; only the
    // broken-mp4 case should return true.
    assertTrue(
      !VideoSpriteExtractor.shouldSkipVideoFallbackForBrokenMp4(tempDir, "TestPlatform"),
      "no marker → must not skip VIDEO fallback",
    )
    assertTrue(
      !VideoSpriteExtractor.shouldSkipVideoFallbackForBrokenMp4(null, "TestPlatform"),
      "null dir → must not skip VIDEO fallback (no NPE)",
    )
    File(tempDir, VideoSpriteExtractor.MP4_BROKEN_MARKER_FILENAME).writeText("test marker")
    assertTrue(
      VideoSpriteExtractor.shouldSkipVideoFallbackForBrokenMp4(tempDir, "TestPlatform"),
      "marker present → must signal skip",
    )
  }

  @Test
  fun `generateSpriteSheet clears stale broken-mp4 marker before deciding`() {
    // A leftover marker from a previous failed attempt in the same dir must not suppress
    // emission for a healthy current run. In practice session dirs are unique per recording,
    // but the cleanup is the durable contract.
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    File(tempDir, VideoSpriteExtractor.MP4_BROKEN_MARKER_FILENAME).writeText("stale marker")
    val healthy = generateHealthyMp4(
      target = File(tempDir, "healthy_after_stale_marker.mp4"),
      durationSeconds = 30,
      fps = 30,
    )
    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = healthy,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      expectedDurationMs = 30_000L,
    )
    assertNotNull(spriteFile, "healthy mp4 must produce a sprite sheet even if a stale marker existed")
    assertTrue(
      !File(tempDir, VideoSpriteExtractor.MP4_BROKEN_MARKER_FILENAME).exists(),
      "stale marker must be cleared at the start of generateSpriteSheet",
    )
  }

  @Test
  fun `short single-frame static-screen recording does not trip the broken-mp4 gate`() {
    // The CI smoke fixture: 0.04s mp4 with 1 frame, 11s expected wall-clock. After re-stamp
    // the single frame is replicated across the timeline; the gate's absolute floor
    // (BROKEN_MP4_MIN_EXPECTED_FRAMES) keeps this case out of the broken bucket because the
    // expected frame count for an 11-second session at 2fps is only 22 — below the floor.
    // Regression test: if a future contributor lowers the floor without thinking through
    // the static-screen path, this test fails first.
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val crushed = synthesizeTinySingleFrameMp4(File(tempDir, "tiny_static.mp4"))
    val spriteFile = VideoSpriteExtractor.generateSpriteSheet(
      videoFile = crushed,
      fps = 2,
      frameHeight = 360,
      webpQuality = 80,
      isLandscape = false,
      expectedDurationMs = 11_000L,
    )
    assertNotNull(
      spriteFile,
      "11s single-frame static-screen recording must still produce a sprite sheet — " +
        "the broken-mp4 gate's absolute floor is what keeps this case out of the broken bucket",
    )
    assertTrue(
      !File(tempDir, VideoSpriteExtractor.MP4_BROKEN_MARKER_FILENAME).exists(),
      "broken-mp4 marker must not be written for legitimate single-frame recordings",
    )
  }

  @Test
  fun `successful extraction writes video_sprites_diag with probe and restamp summary`() {
    // The diagnostic file is the only forensic trail when sprite extraction completes but the
    // result is suspect (e.g. tiny sprite from a long session because re-stamp silently didn't
    // fire). It must be written even on the success path; CI artifact uploads see the file but
    // not host-side Console.log, so this is the only signal that survives.
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
      expectedDurationMs = 10_000L,
    )
    assertNotNull(spriteFile, "sprite sheet should be produced for the broken-timing fixture")

    val diagFile = File(tempDir, VideoSpriteExtractor.DIAG_FILENAME)
    assertTrue(
      diagFile.exists(),
      "expected ${VideoSpriteExtractor.DIAG_FILENAME} on the success path",
    )
    val diagText = diagFile.readText()
    assertTrue(
      diagText.contains("expectedDurationMs=10000"),
      "diag should include expectedDurationMs for the K1-style post-hoc triage, got:\n$diagText",
    )
    assertTrue(
      diagText.contains("probe=reportedS="),
      "diag should include the ffprobe outcome, got:\n$diagText",
    )
    assertTrue(
      diagText.contains("setpts="),
      "broken-timing fixture should have triggered re-stamp; diag's vf= line should include setpts=, got:\n$diagText",
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

  /**
   * Synthesizes a 1-frame mp4 with a ~0.04-second container duration — the exact shape the
   * Android `screenrecord` path produces on a fresh default 320x640 AVD when the recorded
   * screen is static (one IDR frame, no further data). The `-c copy` mp4 wrap stamps the
   * container at 25 fps default, so 1 frame = 1/25s = 0.04s. The test that uses this
   * fixture relies on `ffprobe` reporting `nb_frames=1, duration=0.04`.
   */
  private fun synthesizeTinySingleFrameMp4(target: File): File {
    // Generate a 25-frame, 25 fps testsrc, then crush PTS to ~0.04s and keep just the first
    // frame. We need the multi-frame intermediate because `setpts=PTS*0.04` on a 1-frame stream
    // doesn't shrink the container (nothing to "compress" with a single PTS=0 frame).
    val intermediate = generateHealthyMp4(File(tempDir, "intermediate.mp4"), durationSeconds = 1, fps = 25)
    val pb = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-i", intermediate.absolutePath,
      "-filter:v", "setpts=PTS*0.04",
      "-frames:v", "1",
      "-c:v", "libx264",
      "-preset", "ultrafast",
      "-pix_fmt", "yuv420p",
      target.absolutePath,
    ).redirectErrorStream(true)
    val process = pb.start()
    process.inputStream.bufferedReader().readText()
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw IllegalStateException("failed to synthesize tiny single-frame mp4 at ${target.absolutePath}")
    }
    intermediate.delete()
    return target
  }

  /**
   * Probes [file] via ffprobe for `nb_frames` on the first video stream. Returns null when
   * ffprobe is missing, exits non-zero, or the container reports `N/A` (some containers don't
   * carry a header-side frame count). Mirrors [readDurationSeconds] — the two are paired so
   * test fixtures can assert both halves of the CI failure signature in one go.
   */
  private fun readNbFrames(file: File): Long? {
    val pb = ProcessBuilder(
      "ffprobe",
      "-v", "error",
      "-select_streams", "v:0",
      "-show_entries", "stream=nb_frames",
      "-of", "default=nw=1:nk=1",
      file.absolutePath,
    ).redirectErrorStream(true)
    return try {
      val process = pb.start()
      val output = process.inputStream.bufferedReader().readText().trim()
      if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null
      output.toLongOrNull()
    } catch (_: Exception) {
      null
    }
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
