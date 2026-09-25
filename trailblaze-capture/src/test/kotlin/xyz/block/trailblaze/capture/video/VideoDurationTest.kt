package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parsing ffprobe's duration answer. Every string below is something ffprobe really emits, and the
 * ones that mean "I don't know" have to stay distinguishable from a real zero — a caller that took
 * a zero for an answer would publish a recording whose window is an instant, and the report would
 * scale the whole clip onto it.
 */
class VideoDurationTest {

  @Test
  fun `a duration in seconds becomes whole milliseconds`() {
    assertEquals(10_960L, VideoDuration.parseDurationMs("10.960000\n"), "the measured Playwright recording")
    assertEquals(8_920L, VideoDuration.parseDurationMs("8.920000"), "trailing newline is optional")
    assertEquals(1L, VideoDuration.parseDurationMs("0.000667\n"), "sub-millisecond durations round, not truncate to zero")
  }

  @Test
  fun `an answerless probe is null rather than zero`() {
    assertNull(VideoDuration.parseDurationMs("N/A\n"), "a container with no duration in its header")
    assertNull(VideoDuration.parseDurationMs(""), "a file ffprobe could not open prints nothing")
    assertNull(VideoDuration.parseDurationMs("   \n\n"), "whitespace is not an answer")
    assertNull(VideoDuration.parseDurationMs("0.000000\n"), "a still-empty recording is no answer either")
    assertNull(VideoDuration.parseDurationMs("-1.5\n"), "a negative duration is not a window")
  }

  @Test
  fun `the duration is read from the first line, not the last`() {
    assertEquals(
      8_960L,
      VideoDuration.parseDurationMs("8.960000\nN/A\n"),
      "ffprobe prints one value per requested entry; the duration we asked for is the first",
    )
  }
}
