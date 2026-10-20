package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the stale-recording discrimination — the load-bearing rule that a fresh recorder
 * cleans up a crashed Trailblaze recording of *either* iOS video name (the whole-session `video.mp4`
 * and the mid-session `video.simctl.mp4` remainder), while leaving a non-Trailblaze recording alone.
 * Pure regex logic, no pgrep / simulator.
 */
class IosVideoCaptureTest {

  private val deviceId = "ABCD-1234"

  private fun cmd(path: String) = "xcrun simctl io $deviceId recordVideo --codec=h264 --force $path"

  @Test
  fun `matches both Trailblaze video names for the device`() {
    assertTrue(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/s1/video.mp4")),
      "whole-session video.mp4 must match",
    )
    assertTrue(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/s1/video.simctl.mp4")),
      "the mid-session remainder video.simctl.mp4 must match",
    )
    assertTrue(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/s1/video.baguette.mp4")),
      "any video<*>.mp4 basename must match",
    )
  }

  @Test
  fun `does not match a non-Trailblaze recording or a different device`() {
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/ci/logs/simulator_recording.mp4")),
      "a deliberate non-Trailblaze recording (basename not video*.mp4) must be left alone",
    )
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/s1/clip.mp4")),
      "an unrelated basename must not match",
    )
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(
        deviceId,
        "xcrun simctl io OTHER-DEVICE recordVideo --force /sessions/s1/video.mp4",
      ),
      "a recording for a different device id must not match",
    )
  }

  @Test
  fun `a stop inside the recorder's startup never yields a window that runs backwards`() {
    // Clip-time zero is written by the drain thread when simctl announces its first frame, so it
    // can land after the stop path stamped its floor. The normal path re-stamps and overtakes it;
    // the path where the kill throws leaves the floor standing.
    val start = 1_700_000_000_500L
    assertEquals(
      start,
      IosVideoCapture.recordingWindowEndMs(stampedEndMs = start - 400, startMs = start),
      "an end before the start must collapse to the start, not stay negative",
    )
    // The ordinary case is untouched — this must not widen a window that was already right.
    assertEquals(
      start + 10_000,
      IosVideoCapture.recordingWindowEndMs(stampedEndMs = start + 10_000, startMs = start),
      "a window that already runs forwards is left exactly as stamped",
    )
  }

  @Test
  fun `recording temp target still matches the stale-recording pattern`() {
    // The temp target moved off java.io.tmpdir (CoreSimulatorTempFiles, boot-volume) — the
    // stale-recording cleanup must keep matching a crashed recorder at the new location.
    val target = IosVideoCapture.createRecordingTempFile()
    try {
      assertTrue(
        IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd(target.absolutePath)),
        "a recorder writing to ${target.absolutePath} must be cleaned up as stale",
      )
    } finally {
      target.delete()
    }
  }

  @Test
  fun `wildcard stays within a single path component`() {
    assertFalse(
      IosVideoCapture.matchesStaleTrailblazeRecording(deviceId, cmd("/sessions/video-run/other.mp4")),
      "a 'video' directory with a non-video basename must not match",
    )
  }

  @Test
  fun `the stop-time transcode is bounded by the recording it has to encode, not a flat ceiling`() {
    // The transcode runs inside session teardown, so its cap is what a wedged ffmpeg costs the
    // step. A short session must not be able to stall teardown for the length of a long one.
    val short = IosVideoCapture.transcodeTimeoutSeconds(30_000)
    val long = IosVideoCapture.transcodeTimeoutSeconds(20 * 60 * 1000L)
    assertTrue(short < long, "the bound must follow the recording's length, not sit at one value")

    // A floor, because a one-second recording still needs ffmpeg to start.
    assertTrue(IosVideoCapture.transcodeTimeoutSeconds(0) >= 60, "even an empty recording gets ffmpeg's startup")
    // The encode beats realtime comfortably, so the bound must leave room above the recording.
    assertTrue(long >= 20 * 60, "a 20-minute recording must be allowed at least its own length")
    // And a ceiling, so a mis-stamped window can't hand ffmpeg an unbounded wait.
    assertEquals(1_800L, IosVideoCapture.transcodeTimeoutSeconds(Long.MAX_VALUE / 4), "the cap still applies")
  }

  /** A `video.webm` holding [bytes] bytes, as a killed ffmpeg would leave it. */
  private fun partialWebm(bytes: Int): File =
    File.createTempFile("tb-transcode-", ".webm").apply { deleteOnExit(); writeBytes(ByteArray(bytes)) }

  @Test
  fun `a transcode that did not finish leaves no half-written recording behind`() {
    // ffmpeg destroyed on its timeout has been writing right up to the moment it was killed, and
    // the session then ships simctl's mp4 instead. CI uploads recordings by extension, so a webm
    // left beside that mp4 is published as a recording that no player can open.
    val timedOut = partialWebm(4_096)
    assertFalse(IosVideoCapture.transcodeDelivered(timedOut, exitCode = null), "a killed ffmpeg delivered nothing")
    assertFalse(timedOut.exists(), "the partial webm a killed ffmpeg left must not survive into the session dir")

    val failed = partialWebm(4_096)
    assertFalse(IosVideoCapture.transcodeDelivered(failed, exitCode = 1), "a non-zero exit delivered nothing")
    assertFalse(failed.exists(), "the same holds for the encode that ran to completion and failed")

    val empty = partialWebm(0)
    assertFalse(IosVideoCapture.transcodeDelivered(empty, exitCode = 0), "an empty file is not a recording")
    assertFalse(empty.exists(), "a zero-length target is cleaned up like any other non-delivery")
  }

  @Test
  fun `a transcode that produced a recording keeps it`() {
    // The other half of the contract: cleaning up must not reach the file the report is about to
    // play, which would cost every iOS session its recording.
    val encoded = partialWebm(4_096)
    assertTrue(IosVideoCapture.transcodeDelivered(encoded, exitCode = 0), "a clean exit with bytes is a delivery")
    assertTrue(encoded.exists(), "the delivered recording stays on disk")
  }

  private fun ffmpeg(vararg args: String) {
    val result = runSubprocessWithTimeout(listOf("ffmpeg", "-y", "-hide_banner", "-loglevel", "error") + args, timeoutSeconds = 60)
    assertEquals(0, result?.exitCode, "ffmpeg ${args.toList()} failed: ${result?.output}")
  }

  /** [file]'s video packets as (pts, dts) in decode order, in its stream time base. */
  private fun packets(file: File): List<Pair<Long, Long>> =
    runSubprocessWithTimeout(
      listOf("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts,dts", "-of", "csv=p=0", file.absolutePath),
      timeoutSeconds = 30,
    )!!.output.lines().mapNotNull { line -> line.trim().split(",").takeIf { it.size == 2 }?.let { it[0].toLong() to it[1].toLong() } }

  /** [file]'s frame times in ms, in the order a player shows them. */
  private fun frameTimesMs(file: File): List<Long> =
    runSubprocessWithTimeout(
      listOf("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0", file.absolutePath),
      timeoutSeconds = 30,
    )!!.output.lines().mapNotNull { it.trim().toDoubleOrNull() }.map { Math.round(it * 1000) }.sorted()

  /**
   * An mp4 with simctl's quirks in [dir], and its frame times rebased to zero. H.264 with B-frames
   * over bursts of change between still stretches, like simctl's stream; decode times seconds
   * behind presentation; and a frame stamped with its predecessor's presentation time, here the
   * eighth frame shown. The B-frame reorder also leaves one frame with a 2.88 s duration.
   */
  private fun simctlLikeRecording(dir: File): Pair<File, List<Long>> {
    val frameMs = listOf(0, 40, 80, 120, 1500, 1540, 1580, 1620, 4000, 4040, 4080, 4120, 7000, 7040, 7080, 7120)
    val setpts = frameMs.withIndex().joinToString("") { (i, ms) -> "if(eq(N\\,$i)\\,$ms\\," } + "PTS" + ")".repeat(frameMs.size)
    val encoded = File(dir, "encoded.mp4")
    ffmpeg(
      "-f", "lavfi", "-i", "testsrc=size=64x48:rate=25", "-vf", "settb=1/1000,setpts='$setpts'",
      "-frames:v", frameMs.size.toString(), "-c:v", "libx264", "-bf", "2", "-pix_fmt", "yuv420p",
      "-fps_mode", "passthrough", "-video_track_timescale", "1000", encoded.absolutePath,
    )
    val written = packets(encoded)
    val shown = written.map { it.first }.sorted()
    val repeated = written.indexOfFirst { it.first == shown[7] }
    val recording = File(dir, "recording.mp4")
    ffmpeg(
      "-i", encoded.absolutePath, "-c", "copy",
      "-bsf:v", "setts=pts=if(eq(N\\,$repeated)\\,${shown[6] + 1_000}\\,PTS+1000)",
      "-video_track_timescale", "1000", recording.absolutePath,
    )
    val recorded = frameTimesMs(recording)
    assertEquals(recorded[6], recorded[7], "the fixture repeats a timestamp")
    return recording to recorded.map { it - recorded.first() }
  }

  /** [command] without the frame-rate cap, so a frame's own timestamp is observable. */
  private fun uncapped(command: List<String>): List<String> =
    command.map { it.replace(",${IosVideoCapture.FRAME_RATE_CAP_FILTER}", "") }

  @Test
  fun `a repeated timestamp in simctl's mp4 moves no later frame`() {
    val dir = Files.createTempDirectory("ios-transcode-").toFile()
    try {
      val (recording, expected) = simctlLikeRecording(dir)

      // Measured before the cap resamples, which would put every frame on its grid either way.
      val webm = File(dir, "video.webm")
      ffmpeg(*uncapped(IosVideoCapture.transcodeCommand("ffmpeg", recording, webm)).drop(1).toTypedArray())
      assertEquals(expected, frameTimesMs(webm), "every frame keeps the time simctl gave it")

      // The same encode without the fix, to show the fixture trips ffmpeg's timestamp guess.
      val unfixed = File(dir, "unfixed.webm")
      ffmpeg(*uncapped(IosVideoCapture.transcodeCommand("ffmpeg", recording, unfixed)).drop(1).filter { it != "-fflags" && it != "+igndts" }.toTypedArray())
      assertNotEquals(expected, frameTimesMs(unfixed), "without -fflags +igndts later frames move onto decode times")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `both transcodes cap the frame rate and never run past the recording window`() {
    val dir = Files.createTempDirectory("ios-transcode-").toFile()
    try {
      val (recording, expected) = simctlLikeRecording(dir)
      val windowMs = expected.last() + 100
      val minGapMs = 1000 / IosVideoCapture.MAX_FRAME_RATE - 1 // a millisecond for rounding

      for (format in RecordingFormat.entries) {
        val out = File(dir, "video.${format.fileExtension}")
        ffmpeg(*IosVideoCapture.transcodeCommand("ffmpeg", recording, out, format, windowMs).drop(1).toTypedArray())
        val times = frameTimesMs(out)
        val capped = times.zipWithNext().all { (a, b) -> b - a >= minGapMs }
        assertTrue(capped, "$format: at most ${IosVideoCapture.MAX_FRAME_RATE} fps, got $times")
        // The fixture's 2.88 s last frame is what the window cap is for: some ffmpeg versions'
        // resampler hold that frame for its full duration, well past the window.
        assertTrue(times.last() < windowMs, "$format: ends inside the ${windowMs}ms window, got ${times.last()}")
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `one transcode runs the recording to its window, so the tail hold re-encodes nothing`() {
    val dir = Files.createTempDirectory("ios-transcode-").toFile()
    try {
      val (recording, expected) = simctlLikeRecording(dir)
      // Past even the fixture's 2.88 s last-frame duration, so only the held tail can reach it.
      val windowMs = expected.last() + 5_000

      for (format in RecordingFormat.entries) {
        val held = File(dir, "held.${format.fileExtension}")
        ffmpeg(*IosVideoCapture.transcodeCommand("ffmpeg", recording, held, format, windowMs, holdTail = true).drop(1).toTypedArray())
        val reached = RecordingTailHold.holdToWindow(held, windowMs, hold = { _, _ -> error("$format: re-encoded a second time") })
        assertTrue(reached, "$format: reaches the ${windowMs}ms window in one pass")

        // Without the hold the same transcode stops short, and the tail hold would encode it again.
        val short = File(dir, "short.${format.fileExtension}")
        ffmpeg(*IosVideoCapture.transcodeCommand("ffmpeg", recording, short, format, windowMs).drop(1).toTypedArray())
        var holds = 0
        RecordingTailHold.holdToWindow(short, windowMs, hold = { _, _ -> holds++; true })
        assertEquals(1, holds, "$format: unheld, the tail needs a second encode")
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `the held tail shows the last screen even when it was on screen for one short frame`() {
    val dir = Files.createTempDirectory("ios-transcode-").toFile()
    try {
      // Red, then blue for 20 ms just before stop: too short for the 10 fps grid to keep on its own.
      val recording = File(dir, "recording.mp4")
      ffmpeg(
        "-f", "lavfi", "-i", "color=red:s=64x64:r=50:d=1.06[a];color=blue:s=64x64:r=50:d=0.02[b];[a][b]concat=n=2",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", recording.absolutePath,
      )
      for (format in RecordingFormat.entries) {
        val held = File(dir, "held.${format.fileExtension}")
        ffmpeg(*IosVideoCapture.transcodeCommand("ffmpeg", recording, held, format, windowMs = 2_000, holdTail = true).drop(1).toTypedArray())
        val shown = frameColors(held)
        assertEquals('B', shown.last(), "$format: the tail holds the final screen, got $shown")
        assertTrue(shown.startsWith("R"), "$format: and the screen before it still plays first, got $shown")
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  /** Each frame of [file] as `R` or `B`, whichever of red and blue dominates it. */
  private fun frameColors(file: File): String {
    val raw = File(file.parentFile, "${file.name}.rgb")
    ffmpeg("-i", file.absolutePath, "-vf", "scale=1:1,format=rgb24", "-fps_mode", "passthrough", "-f", "rawvideo", raw.absolutePath)
    return raw.readBytes().toList().chunked(3).joinToString("") { (r, _, b) -> if ((r.toInt() and 0xff) > (b.toInt() and 0xff)) "R" else "B" }
  }

  @Test
  fun `the tail is never held without a window to stop it`() {
    // The hold repeats the last frame without end; only the window cap stops it.
    for (windowMs in listOf(null, 0L)) {
      val command = IosVideoCapture.transcodeCommand("ffmpeg", File("in.mp4"), File("video.webm"), windowMs = windowMs, holdTail = true)
      assertFalse(command.any { IosVideoCapture.HOLD_LAST_FRAME_FILTER in it }, "window $windowMs: $command")
    }
  }

  @Test
  fun `the mp4 fallback is downscaled H264, not simctl's full-resolution file`() {
    val command = IosVideoCapture.transcodeCommand("ffmpeg", File("in.mp4"), File("video.mp4"), RecordingFormat.MP4)
    assertTrue(command.windowed(2).contains(listOf("-c:v", "libx264")), "re-encoded with x264: $command")
    val filter = command[command.indexOf("-vf") + 1]
    assertTrue(IosVideoCapture.DOWNSCALE_FILTER in filter, "downscaled like the webm: $filter")
    assertTrue(IosVideoCapture.FRAME_RATE_CAP_FILTER in filter, "frame-rate capped like the webm: $filter")
  }

  @Test
  fun `a failed transcode logs ffmpeg's error, not its version banner`() {
    // A failure log keeps the first 400 characters of the output, and the banner alone is ~3,000.
    val dir = Files.createTempDirectory("ios-transcode-").toFile()
    try {
      val notAVideo = File(dir, "recording.mp4").apply { writeText("not a video") }
      val command = IosVideoCapture.transcodeCommand("ffmpeg", notAVideo, File(dir, "video.webm"))
      val result = runSubprocessWithTimeout(command, timeoutSeconds = 60)
      assertNotEquals(0, result?.exitCode, "the fixture must fail")
      val logged = sanitizeSubprocessOutputForLog(result!!.output)
      assertFalse("ffmpeg version" in logged, "no banner: $logged")
      assertTrue("recording.mp4" in logged, "names the failure: $logged")
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `a window too short to encode leaves the output uncapped`() {
    // A stop inside simctl's startup yields an empty window; `-t 0` would encode nothing.
    for (windowMs in listOf(null, 0L, -5L)) {
      val command = IosVideoCapture.transcodeCommand("ffmpeg", File("in.mp4"), File("video.webm"), windowMs = windowMs)
      assertFalse("-t" in command, "window $windowMs: $command")
    }
    val capped = IosVideoCapture.transcodeCommand("ffmpeg", File("in.mp4"), File("video.webm"), windowMs = 132_143)
    assertTrue(capped.windowed(2).contains(listOf("-t", "132.143")), "capped at the window: $capped")
  }
}
