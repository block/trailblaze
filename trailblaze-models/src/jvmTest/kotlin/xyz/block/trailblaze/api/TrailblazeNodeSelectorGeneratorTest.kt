package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrailblazeNodeSelectorGeneratorTest {

  // -- Helpers --

  private var nextId = 1L

  private fun node(
    detail: DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode {
    val id = nextId++
    return TrailblazeNode(nodeId = id, children = children, bounds = bounds, driverDetail = detail)
  }

  /** Overload that accepts any DriverNodeDetail variant. */
  private fun nodeOf(
    detail: DriverNodeDetail,
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode {
    val id = nextId++
    return TrailblazeNode(nodeId = id, children = children, bounds = bounds, driverDetail = detail)
  }

  private fun assertUniqueMatch(
    root: TrailblazeNode,
    target: TrailblazeNode,
  ): TrailblazeNodeSelector {
    val selector = TrailblazeNodeSelectorGenerator.findBestSelector(root, target)
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertTrue(
      result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch,
      "Expected SingleMatch but got $result for selector: ${selector.description()}",
    )
    assertEquals(target.nodeId, result.node.nodeId)
    return selector
  }

  // -- Strategy 1: uniqueId --

  @Test
  fun `uniqueId selector`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(uniqueId = "uid-42"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Other"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.uniqueId)
  }

  // -- Strategy 2: contentDescription alone --

  @Test
  fun `contentDescription alone for icon button`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(contentDescription = "Close"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Save"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.contentDescriptionRegex)
  }

  // -- Strategy 6: text alone --

  @Test
  fun `text alone for unique text`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Cancel"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.textRegex)
  }

  // -- Strategy 7: text + className disambiguation --

  @Test
  fun `text plus className disambiguation`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Fries",
        className = "android.widget.EditText",
      ),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Fries",
        className = "android.widget.TextView",
      ),
    )
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.textRegex)
    assertNotNull(match.classNameRegex)
  }

  // -- Strategy 6: editable text excluded --

  @Test
  fun `editable text excluded from text matching`() {
    nextId = 1L
    // Editable nodes should not use their text as a selector (user input is not stable)
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "user@example.com",
        isEditable = true,
        hintText = "Email",
        className = "android.widget.EditText",
      ),
    )
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Password label"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    // Should NOT use the editable text "user@example.com" — should use hintText instead
    assertTrue(match.textRegex == null || match.hintTextRegex != null)
  }

  // -- Strategy 4: hintText for empty input --

  @Test
  fun `hintText for empty input`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        hintText = "Enter your email",
      ),
    )
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.hintTextRegex)
  }

  // -- Strategy 8: resourceId + text --

  @Test
  fun `resourceId plus text`() {
    nextId = 1L
    // Both items share the same text, so text alone is ambiguous.
    // Both share the same resourceId, so resourceId alone is also ambiguous.
    // Only resourceId + text together (strategy 8) won't help here either since they're identical.
    // Instead, test that when texts differ but resourceId is shared, text alone (strategy 6)
    // is sufficient — verifying the cascade picks the simplest approach.
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        resourceId = "com.example:id/item_title",
        text = "Coffee",
      ),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        resourceId = "com.example:id/item_title",
        text = "Tea",
      ),
    )
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    // Text alone is unique, so the generator picks strategy 6 (simplest)
    assertNotNull(match.textRegex)
  }

  // -- Strategy 9: labeledByText --

  @Test
  fun `labeledByText for form field`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        labeledByText = "Email",
        isEditable = true,
      ),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        labeledByText = "Password",
        isEditable = true,
      ),
    )
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.labeledByTextRegex)
  }

  // -- Strategy 11: paneTitle for dialogs --

  @Test
  fun `paneTitle for dialogs`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(paneTitle = "Confirm deletion"),
    )
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Main content"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.paneTitleRegex)
  }

  // -- Strategy 12: className alone when unique --

  @Test
  fun `className alone when unique`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.SeekBar"),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Volume",
      ),
    )
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.classNameRegex)
  }

  // -- Strategy 13: className + state flags --

  @Test
  fun `className plus state flags`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        isPassword = true,
        isEditable = true,
      ),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        isEditable = true,
      ),
    )
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.classNameRegex)
    assertEquals(true, match.isPassword)
  }

  // -- Strategy 17: childOf unique parent --

  @Test
  fun `childOf unique parent`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "OK",
      ),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val otherOk = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "OK",
      ),
      bounds = TrailblazeNode.Bounds(10, 310, 100, 350),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        resourceId = "com.example:id/dialog_confirm",
      ),
      bounds = TrailblazeNode.Bounds(0, 100, 200, 200),
      children = listOf(target),
    )
    val otherParent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        resourceId = "com.example:id/dialog_delete",
      ),
      bounds = TrailblazeNode.Bounds(0, 300, 200, 400),
      children = listOf(otherOk),
    )
    val root = node(children = listOf(parent, otherParent))

    val selector = assertUniqueMatch(root, target)
    assertNotNull(selector.childOf, "Expected childOf selector")
  }

  // -- Strategy 18: collectionItemInfo --

  @Test
  fun `collectionItemInfo for list item`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        collectionItemInfo = DriverNodeDetail.AndroidAccessibility.CollectionItemInfo(
          rowIndex = 2,
          rowSpan = 1,
          columnIndex = 0,
          columnSpan = 1,
          isHeading = false,
        ),
      ),
      bounds = TrailblazeNode.Bounds(0, 200, 400, 250),
    )
    val otherItem = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        collectionItemInfo = DriverNodeDetail.AndroidAccessibility.CollectionItemInfo(
          rowIndex = 0,
          rowSpan = 1,
          columnIndex = 0,
          columnSpan = 1,
          isHeading = false,
        ),
      ),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 50),
    )
    val root = node(children = listOf(otherItem, target))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals(2, match.collectionItemRowIndex)
  }

  // -- Strategy 20: spatial below sibling --

  @Test
  fun `spatial below sibling`() {
    nextId = 1L
    val anchor = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Section Header"),
      bounds = TrailblazeNode.Bounds(0, 100, 400, 150),
    )
    // Two identical buttons: target is below anchor, other is above anchor
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
      ),
      bounds = TrailblazeNode.Bounds(0, 0, 200, 40),
    )
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
      ),
      bounds = TrailblazeNode.Bounds(0, 160, 200, 200),
    )
    val root = node(
      children = listOf(other, anchor, target),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 200),
    )

    val selector = assertUniqueMatch(root, target)
    // Should use spatial relationship (below anchor) to disambiguate from other
    assertTrue(
      selector.below != null || selector.leftOf != null || selector.rightOf != null || selector.above != null,
      "Expected spatial relationship in selector",
    )
  }

  // -- Strategy 21: index fallback --

  @Test
  fun `index fallback for identical nodes`() {
    nextId = 1L
    val node1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val node2 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 50, 100, 100),
    )
    val node3 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val root = node(
      children = listOf(node1, node2, node3),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 150),
    )

    val selector = assertUniqueMatch(root, node2)
    assertNotNull(selector.index, "Expected index-based selector for identical nodes")
  }

  // -- Degenerate: single node tree --

  @Test
  fun `single node tree`() {
    nextId = 1L
    val root = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Only node"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )

    val selector = assertUniqueMatch(root, root)
    assertNotNull(selector)
  }

  // ======================================================================
  // Compose strategy tests
  // ======================================================================

  // -- Compose Strategy 1: testTag --

  @Test
  fun `Compose - testTag selector`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.Compose(testTag = "submit_btn", text = "Submit"))
    val other = nodeOf(detail = DriverNodeDetail.Compose(testTag = "cancel_btn", text = "Cancel"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.Compose
    assertNotNull(match.testTag)
  }

  // -- Compose Strategy 2: role + text --

  @Test
  fun `Compose - role plus text selector`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.Compose(role = "Button", text = "Save"))
    val other = nodeOf(detail = DriverNodeDetail.Compose(role = "Button", text = "Delete"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.Compose
    assertNotNull(match.textRegex)
  }

  // -- Compose Strategy 2: text alone (no role) --

  @Test
  fun `Compose - text alone when unique`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.Compose(text = "Welcome"))
    val other = nodeOf(detail = DriverNodeDetail.Compose(text = "Goodbye"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.Compose
    assertNotNull(match.textRegex)
  }

  // -- Compose Strategy 3: childOf unique parent --

  @Test
  fun `Compose - childOf unique parent`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(text = "OK"),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val otherOk = nodeOf(
      detail = DriverNodeDetail.Compose(text = "OK"),
      bounds = TrailblazeNode.Bounds(10, 310, 100, 350),
    )
    val parent = nodeOf(
      detail = DriverNodeDetail.Compose(testTag = "confirm_dialog"),
      bounds = TrailblazeNode.Bounds(0, 100, 200, 200),
      children = listOf(target),
    )
    val otherParent = nodeOf(
      detail = DriverNodeDetail.Compose(testTag = "delete_dialog"),
      bounds = TrailblazeNode.Bounds(0, 300, 200, 400),
      children = listOf(otherOk),
    )
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(parent, otherParent))

    val selector = assertUniqueMatch(root, target)
    assertNotNull(selector.childOf, "Expected childOf selector for Compose")
  }

  // -- Compose Strategy 4: spatial below sibling --

  @Test
  fun `Compose - spatial below sibling`() {
    nextId = 1L
    val anchor = nodeOf(
      detail = DriverNodeDetail.Compose(text = "Section Title"),
      bounds = TrailblazeNode.Bounds(0, 100, 400, 150),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Image"),
      bounds = TrailblazeNode.Bounds(0, 0, 200, 40),
    )
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Image"),
      bounds = TrailblazeNode.Bounds(0, 160, 200, 200),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.Compose(),
      children = listOf(other, anchor, target),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 200),
    )

    val selector = assertUniqueMatch(root, target)
    assertTrue(
      selector.below != null || selector.above != null ||
        selector.leftOf != null || selector.rightOf != null,
      "Expected spatial relationship in Compose selector",
    )
  }
}
