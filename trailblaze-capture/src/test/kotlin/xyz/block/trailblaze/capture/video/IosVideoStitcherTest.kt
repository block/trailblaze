package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [IosVideoStitcher].
 *
 * The load-bearing one runs a **real** ffmpeg over two segments that differ in codec *and* frame
 * size — the exact shape the mid-session handover produces (a VP9/H.264 baguette segment at the
 * simulator's native size, then a scaled-down H.264 `simctl` remainder). That combination is what
 * the concat demuxer silently mis-handles: it decodes every file as the first one's codec, drops
 * the frames it can't decode, and exits 0, so the session ships as its first segment alone. Only an
 * end-to-end join over genuinely mismatched inputs can catch that; a graph-shape assertion can't.
 *
 * Skipped when `ffmpeg`/`ffprobe` are not on PATH so this doesn't fail on a hermetic CI agent
 * without the binaries. The main build's tests all run on agents that have ffmpeg. The
 * gap-preserving arithmetic itself is covered by `IosVideoStitchPlanTest`.
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

  private fun twoSegmentPlan(
    first: File = File(sessionDir, "baguette.mp4"),
    second: File = File(sessionDir, "simctl.mp4"),
    firstStart: Long = 1_000,
    firstEnd: Long = 3_000,
    secondStart: Long = 5_000,
    secondEnd: Long = 8_000,
  ): IosVideoStitchPlan.StitchPlan =
    IosVideoStitchPlan.plan(
      listOf(
        IosVideoStitchPlan.VideoSegment(first, firstStart, firstEnd),
        IosVideoStitchPlan.VideoSegment(second, secondStart, secondEnd),
      ),
    )!!

  @Test
  fun `a handover's mismatched segments are both present in the joined recording`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    if (!WallClockMuxConsumer.Output.vp9EncoderAvailable()) {
      // Without VP9 there is no second codec to mismatch against, and no WebM to deliver.
      println("skipping: this host's ffmpeg has no VP9 encoder")
      return
    }
    // Deliberately mismatched, like the real handover: different codec, different frame size.
    val baguette = generateVideo(File(sessionDir, "baguette.webm"), seconds = 2, size = "320x240", codec = "libvpx-vp9")
    val simctl = generateVideo(File(sessionDir, "simctl.mp4"), seconds = 3, size = "240x180", codec = "libx264")
    val finalFile = File(sessionDir, "video.webm")

    // baguette covered [1000,3000] then died; simctl restarted at 5000 — a 2s gap. The first entry
    // is therefore presented for 4000ms, so the simctl footage must begin at offset 4s and the
    // whole recording must run 4s + its own 3s.
    val ok = IosVideoStitcher.stitch(
      plan = twoSegmentPlan(first = baguette, second = simctl),
      finalFile = finalFile,
      format = RecordingFormat.WEBM,
    )

    assertTrue(ok, "a two-segment stitch over decodable inputs must succeed")
    val durationMs = assertNotNull(VideoDuration.probeMs(finalFile), "joined recording should have a duration")
    assertTrue(
      durationMs > 4_000,
      "the simctl segment starts at 4000ms, so a recording of ${durationMs}ms means it was dropped " +
        "— exactly the silent failure the concat demuxer produced",
    )
    // 4s of first-entry-plus-held-gap, then the remainder's own 3s. Allow a frame or two of slack.
    assertTrue(
      durationMs in 6_800..7_200,
      "expected ~7000ms (4000ms presented + 3000ms remainder) but got ${durationMs}ms",
    )
  }

  @Test
  fun `the joined recording is rejected when it is too short to hold every segment`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // A recording of 2s under a plan whose final segment doesn't start until 4s: something before
    // it was dropped. ffmpeg would have exited 0 either way, so this is the only signal.
    val short = generateVideo(File(sessionDir, "short.mp4"), seconds = 2, size = "320x240", codec = "libx264")

    assertFalse(
      IosVideoStitcher.verifySpansThePlan(twoSegmentPlan(), short, "ffprobe"),
      "a recording shorter than its last segment's start proves a segment was dropped",
    )
  }

  @Test
  fun `a recording that reaches past its last segment's start is accepted`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val long = generateVideo(File(sessionDir, "long.mp4"), seconds = 6, size = "320x240", codec = "libx264")
    assertTrue(
      IosVideoStitcher.verifySpansThePlan(twoSegmentPlan(), long, "ffprobe"),
      "6s covers the 4s at which the last segment starts, plus footage of its own",
    )
  }

  @Test
  fun `an unreadable duration does not discard a recording ffmpeg just wrote`() {
    // ffprobe can be missing on a host whose ffmpeg works. Losing the session's video over a probe
    // we only use as a cross-check would be a worse trade than shipping it unverified.
    val written = File(sessionDir, "video.webm").apply { writeBytes(ByteArray(64)) }
    assertTrue(
      IosVideoStitcher.verifySpansThePlan(
        twoSegmentPlan(),
        written,
        File(sessionDir, "no-such-ffprobe").absolutePath,
      ),
    )
  }

  @Test
  fun `every segment is decoded on its own and scaled onto one frame size before joining`() {
    val graph = IosVideoStitcher.filterComplex(twoSegmentPlan(), VideoFrameSize.Size(1206, 2622))

    // Each input gets its own chain — that separate decode is what lets segments of different
    // codecs join at all.
    assertContains(graph, "[0:v]")
    assertContains(graph, "[1:v]")
    // The concat filter rejects inputs whose size or aspect differ, so both are fitted to the target.
    assertEquals(2, graph.split("scale=1206:2622").size - 1, "both segments must be scaled: $graph")
    assertEquals(2, graph.split("setsar=1").size - 1, "both segments need a square pixel aspect: $graph")
    assertContains(graph, "concat=n=2:v=1:a=0[stitched]")
  }

  @Test
  fun `a non-final segment holds its last frame out to its presented duration`() {
    val graph = IosVideoStitcher.filterComplex(twoSegmentPlan(), VideoFrameSize.Size(320, 240))

    // The 2s gap is filled with clones of the dying screen rather than black, and the trim pins the
    // next segment to offset 4s whether this one's footage ran long or short.
    assertContains(graph, "tpad=stop_mode=clone:stop_duration=4.000")
    assertContains(graph, "trim=duration=4.000")
    // The final segment plays its natural length — one hold in the whole graph, not two.
    assertEquals(1, graph.split("tpad=").size - 1, "only the non-final segment holds: $graph")
  }

  @Test
  fun `the canvas comes from the shape the session spends the most time in`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // A rotation mid-session splits the recording by orientation. Here the session opens portrait
    // for a moment — a home screen, an app launching — and then runs landscape for the rest of it.
    val portraitOpening = generateVideo(File(sessionDir, "portrait.mp4"), seconds = 1, size = "240x320", codec = "libx264")
    val landscapeBody = generateVideo(File(sessionDir, "landscape.mp4"), seconds = 3, size = "320x240", codec = "libx264")

    val target = IosVideoStitcher.targetFrameSize(
      plan = twoSegmentPlan(
        first = portraitOpening,
        second = landscapeBody,
        firstStart = 0,
        firstEnd = 500,
        secondStart = 500,
        secondEnd = 10_000,
      ),
      ffprobeBinary = "ffprobe",
    )

    assertEquals(
      VideoFrameSize.Size(320, 240),
      target,
      "the session is landscape for 9.5s of its 10s; sizing it to the half-second of portrait it " +
        "opened on would letterbox the whole thing",
    )
  }

  @Test
  fun `the last segment's time on screen counts toward the canvas`() {
    // The final entry carries no presented duration — it just plays out — so its span has to come
    // from the plan's overall window. Treating it as zero lets a brief opening segment win by
    // default, which is precisely the case above.
    val plan = twoSegmentPlan(firstStart = 0, firstEnd = 500, secondStart = 500, secondEnd = 10_000)
    val presentedByAllButLast = plan.entries.sumOf { it.presentedDurationMs ?: 0L }

    assertTrue(
      plan.overallEndEpochMs - plan.overallStartEpochMs - presentedByAllButLast > presentedByAllButLast,
      "the fixture must actually have a longer tail than head, or this proves nothing",
    )
  }

  @Test
  fun `reports failure without leaving a partial artifact when ffmpeg cannot run`() {
    if (!ffprobeOnPath()) {
      println("skipping: ffprobe not on PATH")
      return
    }
    val segment = generateVideo(File(sessionDir, "baguette.mp4"), seconds = 1, size = "320x240", codec = "libx264")
    val finalFile = File(sessionDir, "video.webm")

    val ok =
      IosVideoStitcher.stitch(
        plan = twoSegmentPlan(first = segment, second = segment),
        finalFile = finalFile,
        format = RecordingFormat.WEBM,
        // A binary that can't start: the shared subprocess runner returns null → stitch is false.
        ffmpegBinary = File(sessionDir, "no-such-ffmpeg").absolutePath,
      )

    assertFalse(ok, "stitch must report failure when ffmpeg can't run")
    assertFalse(finalFile.exists(), "no partial output should be left behind on a failed stitch")
  }

  @Test
  fun `without a frame size the segments are joined as they are rather than not at all`() {
    // ffprobe can be missing on a host whose ffmpeg is fine. Every recording — even a single
    // baguette segment — comes through this join, so refusing without a size would lose the whole
    // session's video on such a host. Same-sized segments still join; mismatched ones are left to
    // fail in ffmpeg's own exit code.
    val graph = IosVideoStitcher.filterComplex(twoSegmentPlan(), target = null)

    assertFalse(graph.contains("scale="), "with no size to fit onto, no scaling may be attempted: $graph")
    assertFalse(graph.contains(",pad="), "with no size to fit onto, no letterboxing may be attempted: $graph")
    assertContains(graph, "tpad=stop_mode=clone:stop_duration=4.000", message = "the gap must still be held")
    assertContains(graph, "concat=n=2:v=1:a=0[stitched]")
  }

  @Test
  fun `same-sized segments are stitched on a host with ffmpeg but no ffprobe`() {
    if (!ffmpegOnPath()) {
      println("skipping: ffmpeg not on PATH")
      return
    }
    val first = generateVideo(File(sessionDir, "baguette.mp4"), seconds = 1, size = "320x240", codec = "libx264")
    val second = generateVideo(File(sessionDir, "baguette.1.mp4"), seconds = 1, size = "320x240", codec = "libx264")
    val finalFile = File(sessionDir, "video.webm")

    val ok =
      IosVideoStitcher.stitch(
        plan = twoSegmentPlan(first = first, second = second),
        finalFile = finalFile,
        format = RecordingFormat.WEBM,
        ffprobeBinary = File(sessionDir, "no-such-ffprobe").absolutePath,
      )

    assertTrue(ok, "a host without ffprobe must still get its recording")
    assertTrue(finalFile.length() > 0, "the joined recording must have been written")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Generate a short test-pattern video via `ffmpeg lavfi testsrc` in the requested codec/size. */
  private fun generateVideo(target: File, seconds: Int, size: String, codec: String): File {
    val speed =
      if (codec == "libx264") listOf("-preset", "ultrafast")
      else listOf("-b:v", "0", "-crf", "40", "-deadline", "realtime", "-cpu-used", "8")
    val process = ProcessBuilder(
      listOf(
        "ffmpeg",
        "-y",
        "-f", "lavfi",
        "-i", "testsrc=duration=$seconds:size=$size:rate=15",
        "-c:v", codec,
        "-pix_fmt", "yuv420p",
      ) + speed + target.absolutePath,
    ).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText()
    if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw IOException("failed to generate $codec fixture at ${target.absolutePath}")
    }
    return target
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
