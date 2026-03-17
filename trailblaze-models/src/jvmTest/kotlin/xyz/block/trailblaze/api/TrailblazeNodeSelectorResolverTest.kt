package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailblazeNodeSelectorResolverTest {

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

  // -- SingleMatch --

  @Test
  fun `single match returns SingleMatch`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Cancel"))
    val root = node(children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  // -- NoMatch --

  @Test
  fun `no match returns NoMatch`() {
    nextId = 1L
    val root = node(
      children = listOf(
        node(detail = DriverNodeDetail.AndroidAccessibility(text = "A")),
        node(detail = DriverNodeDetail.AndroidAccessibility(text = "B")),
      ),
    )

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "C"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  // -- MultipleMatches --

  @Test
  fun `multiple matches returns MultipleMatches`() {
    nextId = 1L
    val root = node(
      children = listOf(
        node(detail = DriverNodeDetail.AndroidAccessibility(text = "Item")),
        node(detail = DriverNodeDetail.AndroidAccessibility(text = "Item")),
      ),
    )

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Item"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches>(result)
    assertEquals(2, result.nodes.size)
  }

  // -- Regex special chars --

  @Test
  fun `regex special chars in currency are escaped`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "\$3.00"))
    val root = node(children = listOf(target))

    // Properly escaped regex pattern
    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = Regex.escape("\$3.00")),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  // -- Invalid regex fallback --

  @Test
  fun `invalid regex falls back to case-insensitive literal`() {
    nextId = 1L
    // Text with characters that form an invalid regex when unescaped
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "[unclosed"))
    val root = node(children = listOf(target))

    // "[unclosed" is invalid regex (unclosed bracket) — should fall back to literal match
    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "[unclosed"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
  }

  // -- below predicate --

  @Test
  fun `below predicate matches target below anchor`() {
    nextId = 1L
    val anchor = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Header"),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 50),
    )
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Content"),
      bounds = TrailblazeNode.Bounds(0, 60, 400, 110),
    )
    val root = node(children = listOf(anchor, target))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Content"),
      below = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Header"),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  // -- below boundary: overlapping rejected --

  @Test
  fun `below predicate rejects overlapping elements`() {
    nextId = 1L
    val anchor = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Header"),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 100),
    )
    // Target overlaps with anchor (top=80 < anchor.bottom=100)
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Content"),
      bounds = TrailblazeNode.Bounds(0, 80, 400, 150),
    )
    val root = node(children = listOf(anchor, target))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Content"),
      below = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Header"),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  // -- above predicate --

  @Test
  fun `above predicate matches target above anchor`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Title"),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 50),
    )
    val anchor = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Footer"),
      bounds = TrailblazeNode.Bounds(0, 60, 400, 110),
    )
    val root = node(children = listOf(target, anchor))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Title"),
      above = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Footer"),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  // -- leftOf/rightOf --

  @Test
  fun `leftOf predicate`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Left"),
      bounds = TrailblazeNode.Bounds(0, 0, 50, 50),
    )
    val anchor = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Right"),
      bounds = TrailblazeNode.Bounds(60, 0, 120, 50),
    )
    val root = node(children = listOf(target, anchor))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Left"),
      leftOf = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Right"),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
  }

  @Test
  fun `rightOf predicate`() {
    nextId = 1L
    val anchor = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Left"),
      bounds = TrailblazeNode.Bounds(0, 0, 50, 50),
    )
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Right"),
      bounds = TrailblazeNode.Bounds(60, 0, 120, 50),
    )
    val root = node(children = listOf(anchor, target))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Right"),
      rightOf = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Left"),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
  }

  // -- childOf scoping --

  @Test
  fun `childOf scopes search to parent subtree`() {
    nextId = 1L
    val innerTarget = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "OK"),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val outsideOk = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "OK"),
      bounds = TrailblazeNode.Bounds(10, 310, 100, 350),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        resourceId = "com.example:id/dialog",
      ),
      children = listOf(innerTarget),
    )
    val root = node(children = listOf(parent, outsideOk))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "OK"),
      childOf = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(
          resourceIdRegex = Regex.escape("com.example:id/dialog"),
        ),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(innerTarget.nodeId, result.node.nodeId)
  }

  // -- containsDescendants: all must match --

  @Test
  fun `containsDescendants requires all to match`() {
    nextId = 1L
    val child1 = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Title"))
    val child2 = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Subtitle"))
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.LinearLayout"),
      children = listOf(child1, child2),
    )
    val root = node(children = listOf(target))

    // Both descendants present — should match
    val selectorAll = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(
        classNameRegex = Regex.escape("android.widget.LinearLayout"),
      ),
      containsDescendants = listOf(
        TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Title"),
        ),
        TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Subtitle"),
        ),
      ),
    )
    val resultAll = TrailblazeNodeSelectorResolver.resolve(root, selectorAll)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(resultAll)

    // Partial match — should fail
    val selectorPartial = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(
        classNameRegex = Regex.escape("android.widget.LinearLayout"),
      ),
      containsDescendants = listOf(
        TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Title"),
        ),
        TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Missing"),
        ),
      ),
    )
    val resultPartial = TrailblazeNodeSelectorResolver.resolve(root, selectorPartial)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(resultPartial)
  }

  // -- containsChild: direct only --

  @Test
  fun `containsChild matches direct children only`() {
    nextId = 1L
    val grandchild = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Deep"))
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Middle"),
      children = listOf(grandchild),
    )
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.FrameLayout"),
      children = listOf(child),
    )
    val root = node(children = listOf(target))

    // Direct child "Middle" should match
    val selectorDirect = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(
        classNameRegex = Regex.escape("android.widget.FrameLayout"),
      ),
      containsChild = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Middle"),
      ),
    )
    val resultDirect = TrailblazeNodeSelectorResolver.resolve(root, selectorDirect)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(resultDirect)

    // Grandchild "Deep" should NOT match containsChild (it's not a direct child)
    val selectorGrandchild = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(
        classNameRegex = Regex.escape("android.widget.FrameLayout"),
      ),
      containsChild = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Deep"),
      ),
    )
    val resultGrandchild = TrailblazeNodeSelectorResolver.resolve(root, selectorGrandchild)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(resultGrandchild)
  }

  // -- index selects nth --

  @Test
  fun `index selects nth match`() {
    nextId = 1L
    val node0 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Item"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val node1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Item"),
      bounds = TrailblazeNode.Bounds(0, 50, 100, 100),
    )
    val node2 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Item"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val root = node(children = listOf(node0, node1, node2))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Item"),
      index = 1,
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(node1.nodeId, result.node.nodeId)
  }

  // -- index out of range --

  @Test
  fun `index out of range returns NoMatch`() {
    nextId = 1L
    val root = node(
      children = listOf(
        node(detail = DriverNodeDetail.AndroidAccessibility(text = "Item")),
      ),
    )

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Item"),
      index = 5,
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  // -- sorting order --

  @Test
  fun `results sorted top-to-bottom then left-to-right`() {
    nextId = 1L
    // Create nodes in non-spatial order
    val bottomRight = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "X"),
      bounds = TrailblazeNode.Bounds(200, 200, 300, 250),
    )
    val topLeft = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "X"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val topRight = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "X"),
      bounds = TrailblazeNode.Bounds(200, 0, 300, 50),
    )
    val root = node(children = listOf(bottomRight, topLeft, topRight))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "X"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches>(result)

    // Should be sorted: topLeft, topRight, bottomRight
    assertEquals(topLeft.nodeId, result.nodes[0].nodeId)
    assertEquals(topRight.nodeId, result.nodes[1].nodeId)
    assertEquals(bottomRight.nodeId, result.nodes[2].nodeId)
  }

  // -- resolveToCenter --

  @Test
  fun `resolveToCenter returns center point coordinates`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Button"),
      bounds = TrailblazeNode.Bounds(100, 200, 300, 280),
    )
    val root = node(children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Button"),
    )
    val center = TrailblazeNodeSelectorResolver.resolveToCenter(root, selector)
    assertNotNull(center)
    assertEquals(200, center.first) // (100+300)/2
    assertEquals(240, center.second) // (200+280)/2
  }

  @Test
  fun `resolveToCenter returns null for no match`() {
    nextId = 1L
    val root = node(
      children = listOf(
        node(detail = DriverNodeDetail.AndroidAccessibility(text = "A")),
      ),
    )

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Z"),
    )
    val center = TrailblazeNodeSelectorResolver.resolveToCenter(root, selector)
    assertNull(center)
  }

  // ======================================================================
  // Compose variant matching
  // ======================================================================

  @Test
  fun `Compose - match by testTag`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.Compose(testTag = "submit_btn", text = "Submit"))
    val other = nodeOf(detail = DriverNodeDetail.Compose(testTag = "cancel_btn", text = "Cancel"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Compose(testTag = "submit_btn"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `Compose - match by role and text`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.Compose(role = "Button", text = "Save"))
    val other = nodeOf(detail = DriverNodeDetail.Compose(role = "Button", text = "Delete"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Compose(role = "Button", textRegex = "Save"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `Compose - match by toggleableState`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Checkbox", toggleableState = "On"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.Compose(role = "Checkbox", toggleableState = "Off"),
    )
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Compose(toggleableState = "On"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `Compose - match by editableText`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(editableText = "hello@example.com"),
    )
    val other = nodeOf(detail = DriverNodeDetail.Compose(text = "Label"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Compose(
        editableTextRegex = Regex.escape("hello@example.com"),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
  }

  @Test
  fun `Compose - boolean state matching`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Compose(isPassword = true, isEnabled = true),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.Compose(isPassword = false, isEnabled = true),
    )
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Compose(isPassword = true),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  // ======================================================================
  // AndroidMaestro variant matching
  // ======================================================================

  @Test
  fun `AndroidMaestro - match by resourceId`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(resourceId = "com.example:id/btn_ok", text = "OK"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(resourceId = "com.example:id/btn_cancel", text = "Cancel"),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(),
      children = listOf(target, other),
    )

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidMaestro(
        resourceIdRegex = Regex.escape("com.example:id/btn_ok"),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `AndroidMaestro - match by text resolveText priority`() {
    nextId = 1L
    // resolveText() priority: text > hintText > accessibilityText
    val target = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(hintText = "Enter email"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(text = "Submit"),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(),
      children = listOf(target, other),
    )

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidMaestro(textRegex = "Enter email"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `AndroidMaestro - match by boolean state`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(text = "Item", checked = true),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(text = "Item", checked = false),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.AndroidMaestro(),
      children = listOf(target, other),
    )

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidMaestro(textRegex = "Item", checked = true),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  // ======================================================================
  // Web variant matching
  // ======================================================================

  @Test
  fun `Web - match by ariaRole and ariaName`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Web(ariaRole = "button", ariaName = "Submit"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.Web(ariaRole = "button", ariaName = "Cancel"),
    )
    val root = nodeOf(detail = DriverNodeDetail.Web(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "Submit"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `Web - match by dataTestId`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Web(dataTestId = "login-form", ariaRole = "form"),
    )
    val other = nodeOf(detail = DriverNodeDetail.Web(ariaRole = "navigation"))
    val root = nodeOf(detail = DriverNodeDetail.Web(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Web(dataTestId = "login-form"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
  }

  @Test
  fun `Web - match by cssSelector`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Web(cssSelector = "#main-content", ariaRole = "main"),
    )
    val other = nodeOf(detail = DriverNodeDetail.Web(ariaRole = "banner"))
    val root = nodeOf(detail = DriverNodeDetail.Web(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Web(cssSelector = "#main-content"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
  }

  @Test
  fun `Web - match by nthIndex`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.Web(ariaRole = "link", ariaName = "Home", nthIndex = 1),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.Web(ariaRole = "link", ariaName = "Home", nthIndex = 0),
    )
    val root = nodeOf(detail = DriverNodeDetail.Web(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Web(ariaRole = "link", nthIndex = 1),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  // ======================================================================
  // Cross-driver variant mismatch
  // ======================================================================

  @Test
  fun `cross-driver mismatch returns NoMatch`() {
    nextId = 1L
    // Tree uses Compose nodes
    val target = nodeOf(detail = DriverNodeDetail.Compose(text = "Submit"))
    val root = nodeOf(detail = DriverNodeDetail.Compose(), children = listOf(target))

    // Selector uses AndroidAccessibility matcher — should not match Compose nodes
    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "Submit"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  @Test
  fun `cross-driver Web selector on Android tree returns NoMatch`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"))
    val root = node(children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "Submit"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  // ======================================================================
  // IosMaestro variant matching
  // ======================================================================

  @Test
  fun `IosMaestro - match by resourceId`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(resourceId = "login_button", text = "Log In"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(resourceId = "signup_button", text = "Sign Up"),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosMaestro(),
      children = listOf(target, other),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(resourceIdRegex = "login_button"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro - match by text resolveText priority`() {
    nextId = 1L
    // resolveText() priority: text > hintText > accessibilityText
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(accessibilityText = "Back button"),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Next"),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosMaestro(),
      children = listOf(target, other),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Back button"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro - match by boolean state`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Item", selected = true),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(text = "Item", selected = false),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosMaestro(),
      children = listOf(target, other),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Item", selected = true),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro - match by className and hintText`() {
    nextId = 1L
    val target = nodeOf(
      detail = DriverNodeDetail.IosMaestro(
        className = "UITextField",
        hintText = "Email address",
      ),
    )
    val other = nodeOf(
      detail = DriverNodeDetail.IosMaestro(
        className = "UITextField",
        hintText = "Password",
      ),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosMaestro(),
      children = listOf(target, other),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(
        classNameRegex = "UITextField",
        hintTextRegex = "Email address",
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `cross-driver IosMaestro selector on Android tree returns NoMatch`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"))
    val root = node(children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Submit"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }
}
