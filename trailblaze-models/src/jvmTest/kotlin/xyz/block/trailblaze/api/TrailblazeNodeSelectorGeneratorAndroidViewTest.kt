package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertNotNull

class TrailblazeNodeSelectorGeneratorAndroidViewTest : TrailblazeNodeSelectorGeneratorTestBase() {

  /** Convenience for live-View nodes. */
  private fun node(
    detail: DriverNodeDetail.AndroidView = DriverNodeDetail.AndroidView(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = nodeOf(detail, bounds, children)

  @Test
  fun `resourceId selector`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidView(resourceId = "com.example:id/checkout"))
    val other = node(detail = DriverNodeDetail.AndroidView(text = "Other"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidView
    assertNotNull(match.resourceIdRegex)
  }

  @Test
  fun `view tag selector for a node the accessibility tree could not see`() {
    nextId = 1L
    // `tag` exists only on the live View — an accessibility-projected capture of this same
    // screen would have nothing to match on.
    val target = node(detail = DriverNodeDetail.AndroidView(tag = "checkout_button"))
    val other = node(detail = DriverNodeDetail.AndroidView(text = "Other"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidView
    assertNotNull(match.tagRegex)
  }

  @Test
  fun `hintText selector for an empty editable field`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidView(
        className = "android.widget.EditText",
        hintText = "Enter your email",
        isEditable = true,
      ),
    )
    val other = node(detail = DriverNodeDetail.AndroidView(text = "Submit"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidView
    assertNotNull(match.hintTextRegex)
  }

  @Test
  fun `same-text siblings are disambiguated by their parent`() {
    nextId = 1L
    // Two "OK" buttons with identical properties: only the surrounding structure separates
    // them, so the generated selector must anchor on the identified parent rather than
    // degrade to a positional index.
    val target = node(
      detail = DriverNodeDetail.AndroidView(
        className = "android.widget.Button",
        text = "OK",
      ),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val otherOk = node(
      detail = DriverNodeDetail.AndroidView(
        className = "android.widget.Button",
        text = "OK",
      ),
      bounds = TrailblazeNode.Bounds(10, 310, 100, 350),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidView(resourceId = "com.example:id/dialog_confirm"),
      bounds = TrailblazeNode.Bounds(0, 100, 200, 200),
      children = listOf(target),
    )
    val otherParent = node(
      detail = DriverNodeDetail.AndroidView(resourceId = "com.example:id/dialog_delete"),
      bounds = TrailblazeNode.Bounds(0, 300, 200, 400),
      children = listOf(otherOk),
    )
    val root = node(
      children = listOf(parent, otherParent),
      bounds = TrailblazeNode.Bounds(0, 0, 200, 400),
    )

    val selector = assertUniqueMatch(root, target)
    assertNotNull(selector.childOf, "Expected childOf selector, got: ${selector.description()}")
  }
}
