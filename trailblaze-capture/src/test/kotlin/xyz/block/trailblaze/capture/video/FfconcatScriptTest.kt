package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the shared ffconcat emission primitives used by both [ScreencastTimeline] and
 * [IosVideoStitchPlan]. These are the escape/format details both producers depend on, so pin them
 * once here rather than transitively through each producer.
 */
class FfconcatScriptTest {

  @Test
  fun `escapeConcatPath wraps each quote in the ffconcat convention`() {
    assertEquals("""a'\''b'\''c""", FfconcatScript.escapeConcatPath("a'b'c"))
    assertEquals("/plain/path.jpg", FfconcatScript.escapeConcatPath("/plain/path.jpg"))
  }

  @Test
  fun `formatSeconds renders fixed three-decimal seconds, locale-independent`() {
    assertEquals("2.000", FfconcatScript.formatSeconds(2_000))
    // Sub-second and short-fraction values must zero-pad to three decimals, not collapse.
    assertEquals("0.005", FfconcatScript.formatSeconds(5))
    assertEquals("0.050", FfconcatScript.formatSeconds(50))
    assertEquals("1.500", FfconcatScript.formatSeconds(1_500))
    assertEquals("0.000", FfconcatScript.formatSeconds(0))
    assertEquals("12.345", FfconcatScript.formatSeconds(12_345))
  }
}
