package xyz.block.trailblaze.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.runtime.Composable
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.ui.images.ImageLoader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A step that recorded no screenshot must not be reported as a screenshot that failed.
 *
 * Only null was treated as "no screenshot here". A blank reference reaches the loaders as a real
 * name, and they answer with a URL for the session directory — which fails in the decoder, one
 * layer past anything that knows the reference was empty. The inspector then said a screenshot
 * could not be loaded for a step that never had one, which is the same sentence a genuinely broken
 * report shows and sends the reader after a file that was never named.
 */
@OptIn(ExperimentalTestApi::class)
class InspectorBlankScreenshotTest {

  /**
   * Answers every reference the way the real loaders do — including a blank one, which is the
   * whole point. A fake that returned null for blank would move the fix into the test and pass
   * against the bug.
   */
  private class HonestLoader(private val basePath: String) : ImageLoader {
    override fun getImageModel(sessionId: String, screenshotFile: String?): Any? =
      screenshotFile?.let { "$basePath/$sessionId/$it" }
  }

  private val hierarchy = ViewHierarchyTreeNode(
    nodeId = 1,
    x1 = 0,
    y1 = 0,
    x2 = 10,
    y2 = 10,
    text = "Submit",
  )

  @Test
  fun `a blank reference is a step with no screenshot, not a screenshot that failed`() =
    runComposeUiTest {
      setContent { Screen(imageUrl = "   ") }

      assertNoFailureMessage()
    }

  @Test
  fun `a reference that is only whitespace inside is still blank`() = runComposeUiTest {
    setContent { Screen(imageUrl = "\n\t ") }

    assertNoFailureMessage()
  }

  /**
   * The control. Without it the assertions above would keep passing if the inspector stopped
   * reporting failures at all, or stopped rendering this pane, and would prove nothing.
   */
  @Test
  fun `a named screenshot that will not load still says so`() = runComposeUiTest {
    setContent { Screen(imageUrl = "shot.png") }

    waitUntil(timeoutMillis = RENDER_TIMEOUT_MS) {
      onAllNodesWithText("Failed to load screenshot", substring = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
  }

  @Composable
  private fun Screen(imageUrl: String?) {
    MaterialTheme {
      Surface {
        InspectViewHierarchyScreenComposable(
          sessionId = "session-1",
          viewHierarchy = hierarchy,
          imageUrl = imageUrl,
          deviceWidth = 100,
          deviceHeight = 200,
          imageLoader = HonestLoader(basePath = "trailblaze-test://logs"),
        )
      }
    }
  }

  private fun ComposeUiTest.assertNoFailureMessage() {
    // Given time to get it wrong: the message does not appear in the first composition either way.
    // It arrives a frame later, when the load the blank reference caused comes back failed.
    mainClock.advanceTimeBy(FAILURE_SETTLE_MS)
    waitForIdle()

    assertTrue(
      onAllNodesWithText("Failed to load screenshot", substring = true)
        .fetchSemanticsNodes()
        .isEmpty(),
      "a step with no screenshot must not be reported as a screenshot that failed",
    )
  }

  companion object {
    /** Hang containment, not a performance budget — the pane composes from memory. */
    private const val RENDER_TIMEOUT_MS = 60_000L

    /** Long enough for a load to come back and repopulate the message, if one was started. */
    private const val FAILURE_SETTLE_MS = 5_000L
  }
}
