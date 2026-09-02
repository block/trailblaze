package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * An `androidView` selector must never reach Maestro Orchestra.
 *
 * It is authored against a live `android.view.View` tree with strict, case-sensitive matching;
 * Maestro resolves against UiAutomator's accessibility projection with lenient matching. Lowering
 * one and running it there is not a degraded match, it is a match against a different tree.
 *
 * The guard lives in [lowerToMaestroSelector] rather than in one tool because every selector-based
 * tool lowers through it — tap, assertVisible, assertNotVisible, waitUntilNotVisible. On an
 * assertion the failure mode is the dangerous one: a lenient re-match on the wrong tree reads as a
 * pass, so the trail goes green having verified nothing.
 */
class MaestroSelectorLoweringAndroidViewTest {

  @Test
  fun `an androidView selector refuses to lower`() {
    val error = assertFailsWith<IllegalStateException> {
      lowerToMaestroSelector(
        TrailblazeNodeSelector(
          androidView = DriverNodeMatch.AndroidView(textRegex = "^Pick Me$"),
        ),
      )
    }
    assertTrue(
      error.message.orEmpty().contains("androidView recording cannot replay"),
      "the refusal should say why, not just fail: ${error.message}",
    )
  }

  /**
   * The blank-lowering guard cannot stand in for this one. `textRegex` and `resourceIdRegex` DO map
   * to the Maestro shape, so an androidView selector carrying either lowers to something non-blank
   * and would sail past `isBlank()` straight into a wrong-tree match.
   */
  @Test
  fun `the refused selector would otherwise have lowered to a non-blank selector`() {
    val selector = TrailblazeNodeSelector(
      androidView = DriverNodeMatch.AndroidView(textRegex = "^Pick Me$", resourceIdRegex = "^row$"),
    )
    val lowered = selector.toTrailblazeElementSelector()
    assertEquals("^Pick Me$", lowered.textRegex)
    assertEquals("^row$", lowered.idRegex)
  }

  @Test
  fun `other dialects still lower`() {
    val lowered = lowerToMaestroSelector(
      TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^Pick Me$"),
      ),
    )
    assertEquals("^Pick Me$", assertNotNull(lowered).textRegex)
  }

  @Test
  fun `a null selector is still the callers problem, not an error`() {
    assertEquals(null, lowerToMaestroSelector(null))
  }
}
