package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure wall-clock → ffconcat timing logic in [ScreencastTimeline]. No browser,
 * ffmpeg, or filesystem — the arithmetic that keeps the muxed video aligned to the report timeline
 * is exercised directly.
 */
class ScreencastTimelineTest {

  private fun frame(path: String, tsMs: Long) = ScreencastTimeline.Frame(path, tsMs)

  /** Parses the `duration N.NNN` directives from a script into milliseconds, in order. */
  private fun durationsMs(script: String): List<Long> =
    script.lineSequence()
      .filter { it.startsWith("duration ") }
      .map { (it.removePrefix("duration ").trim().toDouble() * 1000).toLong() }
      .toList()

  private fun fileLines(script: String): List<String> =
    script.lineSequence().filter { it.startsWith("file ") }.toList()

  @Test
  fun `no frames yields null`() {
    assertNull(ScreencastTimeline.buildConcatScript(emptyList(), sessionStartMs = 0, sessionEndMs = 1000))
  }

  @Test
  fun `first frame is anchored at video-time zero even if it arrived late`() {
    // First frame arrives 1.2s after session start (screencast warmup); it should still cover
    // from t=0 so there's no black gap and video-time 0 == session start.
    val script = ScreencastTimeline.buildConcatScript(
      frames = listOf(frame("/a.jpg", 1200), frame("/b.jpg", 3200)),
      sessionStartMs = 0,
      sessionEndMs = 5000,
    )!!
    val durations = durationsMs(script)
    // frame a: 0 -> 3200 (b's rel time, anchored) ; frame b: 3200 -> 5000 ; + trailing tail
    assertEquals(3200L, durations[0])
    assertEquals(1800L, durations[1])
    // Total of the two real-frame durations spans the full session window.
    assertEquals(5000L, durations[0] + durations[1])
  }

  @Test
  fun `final frame duration extends to session end for a long static tail`() {
    val script = ScreencastTimeline.buildConcatScript(
      frames = listOf(frame("/a.jpg", 0), frame("/b.jpg", 500)),
      sessionStartMs = 0,
      sessionEndMs = 30_000,
    )!!
    val durations = durationsMs(script)
    assertEquals(500L, durations[0])
    // The static tail after the last frame (500ms .. 30s) is represented at true length.
    assertEquals(29_500L, durations[1])
  }

  @Test
  fun `out-of-order timestamps collapse to a minimum step instead of a negative duration`() {
    // Second frame's timestamp is before the first (clock jitter) — must not produce a negative
    // duration that the concat demuxer would reject.
    val script = ScreencastTimeline.buildConcatScript(
      frames = listOf(frame("/a.jpg", 1000), frame("/b.jpg", 800), frame("/c.jpg", 2000)),
      sessionStartMs = 0,
      sessionEndMs = 3000,
    )!!
    assertTrue(durationsMs(script).all { it >= 1L }, "every duration must be >= 1ms")
  }

  @Test
  fun `script has a trailing repeat of the last frame so its duration is honored`() {
    val script = ScreencastTimeline.buildConcatScript(
      frames = listOf(frame("/a.jpg", 0), frame("/b.jpg", 1000)),
      sessionStartMs = 0,
      sessionEndMs = 2000,
    )!!
    val files = fileLines(script)
    // Two real frames + one trailing repeat of the last file.
    assertEquals(3, files.size)
    assertEquals(files[1], files[2], "trailing entry repeats the last frame")
    assertTrue(script.startsWith("ffconcat version 1.0\n"))
  }

  @Test
  fun `single frame spans the whole session`() {
    val script = ScreencastTimeline.buildConcatScript(
      frames = listOf(frame("/only.jpg", 0)),
      sessionStartMs = 0,
      sessionEndMs = 4000,
    )!!
    // The one real frame covers the full window; then the trailing tail repeat.
    assertEquals(4000L, durationsMs(script).first())
  }

  @Test
  fun `paths with single quotes are escaped for the concat demuxer`() {
    val script = ScreencastTimeline.buildConcatScript(
      frames = listOf(frame("/tmp/it's here/frame.jpg", 0)),
      sessionStartMs = 0,
      sessionEndMs = 1000,
    )!!
    assertTrue(script.contains("""file '/tmp/it'\''s here/frame.jpg'"""))
  }
}
