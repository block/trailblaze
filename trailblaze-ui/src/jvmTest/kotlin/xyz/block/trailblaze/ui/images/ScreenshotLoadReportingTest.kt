package xyz.block.trailblaze.ui.images

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how often a screenshot failure is said, which no single pane can pin for itself.
 */
class ScreenshotLoadReportingTest {

  private val captured = ByteArrayOutputStream()
  private lateinit var realErr: PrintStream

  private val lines: List<String>
    get() = captured.toString().lines().filter { it.isNotBlank() }

  @BeforeTest
  fun captureStdErr() {
    ScreenshotLoadLog.resetForTest()
    realErr = System.err
    System.setErr(PrintStream(captured, true))
  }

  @AfterTest
  fun releaseStdErr() {
    System.setErr(realErr)
    ScreenshotLoadLog.resetForTest()
  }

  @Test
  fun `the same failure is reported once`() {
    // Screenshot panes live in lazy lists and Coil does not cache an error result, so scrolling a
    // broken report re-fetches, re-fails, and would print a line per visible row per scroll pass.
    repeat(50) { ScreenshotLoadLog.reportOnce("Screenshot failed to load: shot.png: boom") }

    assertEquals(1, lines.size, "a repeated failure should be said once, but was: $lines")
  }

  @Test
  fun `a different cause for the same screenshot is still reported`() {
    ScreenshotLoadLog.reportOnce("Screenshot failed to load: shot.png: FileNotFoundException")
    ScreenshotLoadLog.reportOnce("Screenshot failed to load: shot.png: no decoder")

    // Deduplicating per screenshot rather than per line would swallow the second one, which is the
    // one saying the file arrived and the decoder is what broke.
    assertEquals(2, lines.size, "a new cause should still be said, but was: $lines")
  }

  @Test
  fun `the bound never costs the line that was just said`() {
    // The set is cleared to bound it, and the clear has to happen before the line goes in. Clearing
    // after the add throws away the line that triggered it, so whichever screenshot happens to be
    // the one that fills the set is reported twice in a row — right where a scrolling report
    // generates the most repeats.
    val saidTwiceInARow = (0 until 2_000).count { i ->
      val line = "Screenshot failed to load: shot-$i.png"
      ScreenshotLoadLog.reportOnce(line)
      val before = lines.size
      ScreenshotLoadLog.reportOnce(line)
      lines.size > before
    }

    assertEquals(0, saidTwiceInARow, "a line must never be repeated immediately after being said")
  }

  @Test
  fun `what has already been said is not remembered forever`() {
    val first = "Screenshot failed to load: shot-0.png"
    ScreenshotLoadLog.reportOnce(first)

    // A long session can fail on more screenshots than are worth remembering, and this set is only
    // there to keep the log readable — it must not become the thing that grows without bound. The
    // price of the bound is that a failure from far enough back can be said twice.
    repeat(5_000) { ScreenshotLoadLog.reportOnce("Screenshot failed to load: shot-${it + 1}.png") }
    ScreenshotLoadLog.reportOnce(first)

    assertTrue(
      lines.count { it == first } > 1,
      "the remembered set should be bounded, so an old line can be said again",
    )
  }

}
