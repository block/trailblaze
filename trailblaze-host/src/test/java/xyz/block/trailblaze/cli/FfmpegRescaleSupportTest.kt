package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-level coverage for [FfmpegRescaleSupport.scaleFilter] — the ffmpeg scale-filter
 * expression the GIF and video exporters compose into their filter graphs on the
 * `--max-size` rescale path.
 *
 * It's a pure string builder, so it's pinned directly here: the even-vs-auto height rule
 * and the positive-width precondition are exactly the kind of detail a refactor could
 * silently flip. The subprocess-running half of the helper ([runFfmpegToTemp]) is covered
 * by the exporters' own integration/smoke tests, not here.
 */
class FfmpegRescaleSupportTest {

  @Test fun `scaleFilter returns null when no target width is given`() {
    assertNull(FfmpegRescaleSupport.scaleFilter(null, FfmpegRescaleSupport.EvenHeight.LANCZOS_AUTO))
    assertNull(FfmpegRescaleSupport.scaleFilter(null, FfmpegRescaleSupport.EvenHeight.LANCZOS_EVEN))
  }

  @Test fun `scaleFilter uses auto height (-1) for formats that accept odd dimensions`() {
    assertEquals(
      "scale=720:-1:flags=lanczos",
      FfmpegRescaleSupport.scaleFilter(720, FfmpegRescaleSupport.EvenHeight.LANCZOS_AUTO),
    )
  }

  @Test fun `scaleFilter rounds height to even (-2) for codecs that require it`() {
    assertEquals(
      "scale=720:-2:flags=lanczos",
      FfmpegRescaleSupport.scaleFilter(720, FfmpegRescaleSupport.EvenHeight.LANCZOS_EVEN),
    )
  }

  @Test fun `scaleFilter rejects a non-positive width`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      FfmpegRescaleSupport.scaleFilter(0, FfmpegRescaleSupport.EvenHeight.LANCZOS_AUTO)
    }
    assertTrue(
      ex.message!!.contains("positive"),
      "Error must call out the positive-width precondition: '${ex.message}'",
    )
  }
}
