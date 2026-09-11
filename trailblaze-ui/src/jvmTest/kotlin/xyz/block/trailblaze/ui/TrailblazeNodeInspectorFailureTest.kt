package xyz.block.trailblaze.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.ui.images.ImageLoader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the node inspector against a loader that produces nothing and pins what the pane says.
 *
 * The failure this guards is silence: before, a loader that returned no model left an empty pane
 * with node overlays drawn over nothing and no message anywhere, which is what made the
 * ProGuard/Coil breakage (block/trailblaze#194) so hard to place. `ScreenshotDiagnosticsTest`
 * covers the wording in isolation; this proves the composable is actually wired to it, and that a
 * screenshot inlined as a `data:` URI does not get painted into the pane verbatim.
 */
@OptIn(ExperimentalTestApi::class)
class TrailblazeNodeInspectorFailureTest {

  private object NothingToLoad : ImageLoader {
    override fun getImageModel(sessionId: String, screenshotFile: String?): Any? = null
  }

  private val node = TrailblazeNode(
    nodeId = 1,
    bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 10, bottom = 10),
    driverDetail = DriverNodeDetail.Web(ariaRole = "button", ariaName = "Submit"),
  )

  @Test
  fun `a loader that produces nothing says so, and names the screenshot`() = runComposeUiTest {
    setContent {
      MaterialTheme {
        Surface {
          TrailblazeNodeInspector(
            sessionId = "session-1",
            screenshotFile = "screenshot-1234.png",
            trailblazeNodeTree = node,
            deviceWidth = 100,
            deviceHeight = 200,
            selectedNode = null,
            hoveredNode = null,
            onNodeSelected = {},
            onNodeHovered = {},
            imageLoader = NothingToLoad,
          )
        }
      }
    }

    // Naming the screenshot is the point: "Failed to load screenshot" alone does not say whether
    // the reference was wrong or the loader was, and the two are looked at in different places.
    onNodeWithText("Failed to load screenshot: nothing to load for screenshot-1234.png")
      .assertIsDisplayed()
  }

  @Test
  fun `an inlined screenshot is not painted into the pane verbatim`() = runComposeUiTest {
    val payload = "A".repeat(500_000)

    setContent {
      MaterialTheme {
        Surface {
          TrailblazeNodeInspector(
            sessionId = "session-1",
            screenshotFile = "data:image/png;base64,$payload",
            trailblazeNodeTree = node,
            deviceWidth = 100,
            deviceHeight = 200,
            selectedNode = null,
            hoveredNode = null,
            onNodeSelected = {},
            onNodeHovered = {},
            imageLoader = NothingToLoad,
          )
        }
      }
    }

    // The pane is a `SelectableText` with no scroll container and no line limit. Assert the two
    // halves separately: that the summary is what's on screen, and — the thing that actually
    // matters — that the payload is not. A length bound on the message would still pass if some
    // other cap let a large slice of base64 through.
    onNodeWithText(
      "Failed to load screenshot: nothing to load for data:image/png;base64,",
      substring = true,
    ).assertIsDisplayed()

    assertTrue(
      onAllNodesWithText(payload.take(1_000), substring = true).fetchSemanticsNodes().isEmpty(),
      "the inlined image payload must not be rendered into the pane",
    )
  }
}
