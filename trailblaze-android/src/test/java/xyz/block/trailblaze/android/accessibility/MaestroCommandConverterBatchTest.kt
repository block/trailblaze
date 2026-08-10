package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import maestro.orchestra.InputTextCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import xyz.block.trailblaze.exception.TrailblazeException

/**
 * Tests [MaestroCommandConverter.convertAll] batch semantics: an unsupported command fails the
 * whole batch loudly (a partial drop would report Success for a partially-run recording), while
 * an intentional no-op (e.g. [TakeScreenshotCommand]) is skipped without failing. Twin of the
 * iOS converter's `convertAll` tests (`MaestroCommandToIosDriverActionConverterTest`).
 */
class MaestroCommandConverterBatchTest {

  @Test
  fun `convertAll fails loudly on a mixed batch containing an unsupported command`() {
    val e = assertFailsWith<TrailblazeException> {
      MaestroCommandConverter.convertAll(
        listOf(
          InputTextCommand(text = "abc"),
          // No points and no direction — unsupported by the accessibility driver.
          SwipeCommand(duration = 100L),
          StopAppCommand(appId = "com.example"),
        ),
      )
    }
    assertTrue(e.message!!.contains("SwipeCommand"))
  }

  @Test
  fun `convertAll skips an intentional no-op without failing the batch`() {
    // TakeScreenshot converts to an empty (non-null) list — a deliberate skip (screenshots are
    // captured by the logging pipeline), not an unsupported command — so the batch still converts.
    val out = MaestroCommandConverter.convertAll(
      listOf(
        InputTextCommand(text = "abc"),
        TakeScreenshotCommand(path = "/tmp/x.png"),
        StopAppCommand(appId = "com.example"),
      ),
    )
    assertEquals(2, out.size)
    assertIs<AccessibilityAction.InputText>(out[0])
    assertIs<AccessibilityAction.StopApp>(out[1])
  }
}
