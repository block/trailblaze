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

  // -- Compose testTag matching --

  @Test
  fun `composeTestTag matches via composeTestTagRegex`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(composeTestTag = "checkout_btn"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(composeTestTag = "cancel_btn"))
    val root = node(children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(composeTestTagRegex = "checkout_btn"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `composeTestTagRegex with no testTag in node returns NoMatch`() {
    // A selector that constrains composeTestTag should not match nodes that don't expose one.
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"))
    val root = node(children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(composeTestTagRegex = "anything"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  // -- roleDescription matching --

  @Test
  fun `roleDescription matches via roleDescriptionRegex`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        roleDescription = "Toggle",
        className = "android.widget.ImageButton",
      ),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        roleDescription = "Tab",
        className = "android.widget.ImageButton",
      ),
    )
    val root = node(children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(roleDescriptionRegex = "Toggle"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
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

  // -- Literal fallback --

  @Test
  fun `invalid regex falls back to literal`() {
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

  @Test
  fun `unescaped currency regex falls back to literal`() {
    nextId = 1L
    // "$3.00" compiles as a valid regex but can never match (the bare `$` is an end-of-input
    // anchor). The literal fallback matches it against the element's actual text, so an
    // unescaped price authored as natural-language text still resolves.
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "\$3.00"))
    val root = node(children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "\$3.00"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `literal fallback is case-sensitive`() {
    nextId = 1L
    // "abc" is a valid regex that doesn't match "ABC" (case-sensitive), and the literal
    // fallback ("ABC" == "abc") also fails — so this must NOT match, mirroring Maestro.
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "ABC"))
    val root = node(children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "abc"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  @Test
  fun `literal fallback is case-sensitive on the compile-failure path`() {
    nextId = 1L
    // "[unclosed" fails to compile as a regex; the literal fallback is case-sensitive,
    // so a case-different value ("[UNCLOSED") must NOT match. Locks in that both fallback
    // paths (valid-but-unmatchable regex above, and compile failure here) are case-sensitive.
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "[UNCLOSED"))
    val root = node(children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch( DriverNodeMatch.AndroidAccessibility(textRegex = "[unclosed"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
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

  // ======================================================================
  // IosMaestro selector against an IosAxe tree (cross-dialect bridge)
  //
  // A trail recorded under the legacy Maestro iOS driver carries `iosMaestro:` selectors.
  // These tests pin that those selectors still resolve when replayed against the newer
  // AXe driver's DriverNodeDetail.IosAxe nodes.
  // ======================================================================

  @Test
  fun `IosMaestro bridge - textRegex matches AXe label`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(label = "John Appleseed"))
    val other = nodeOf(detail = DriverNodeDetail.IosAxe(label = "Jane Doe"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "John Appleseed"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - textRegex matches AXe value`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(value = "50%"))
    val other = nodeOf(detail = DriverNodeDetail.IosAxe(value = "10%"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "50%"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - textRegex matches AXe title`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(title = "Settings"))
    val other = nodeOf(detail = DriverNodeDetail.IosAxe(title = "Profile"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Settings"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - accessibilityTextRegex matches AXe label`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(label = "Add"))
    val other = nodeOf(detail = DriverNodeDetail.IosAxe(label = "Remove"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(accessibilityTextRegex = "Add"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - resourceIdRegex matches AXe uniqueId`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(uniqueId = "login_button", label = "Log In"))
    val other = nodeOf(detail = DriverNodeDetail.IosAxe(uniqueId = "signup_button", label = "Sign Up"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(resourceIdRegex = "login_button"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - classNameRegex matches AXe type or role`() {
    nextId = 1L
    val byType = nodeOf(detail = DriverNodeDetail.IosAxe(type = "Button", label = "OK"))
    val byRole = nodeOf(detail = DriverNodeDetail.IosAxe(role = "AXButton", label = "Cancel"))
    val neither = nodeOf(detail = DriverNodeDetail.IosAxe(type = "StaticText", label = "Hello"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(byType, byRole, neither))

    val resultType = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(classNameRegex = ".*Button")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches>(resultType)
    assertEquals(setOf(byType.nodeId, byRole.nodeId), resultType.nodes.map { it.nodeId }.toSet())
  }

  @Test
  fun `IosMaestro bridge - hintTextRegex matches AXe help`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(help = "Enter your email address", label = "Email"))
    val other = nodeOf(detail = DriverNodeDetail.IosAxe(help = "Enter your password", label = "Password"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(hintTextRegex = "Enter your email address"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - hintTextRegex matches a text input's placeholder-as-label`() {
    nextId = 1L
    // iOS surfaces an empty text field's placeholder as AXLabel (help is null) — e.g. the
    // Contacts search field. A decorative sibling with the same label (magnifying-glass Image)
    // must NOT match: placeholder-as-label only applies to text-input types.
    val searchField = nodeOf(detail = DriverNodeDetail.IosAxe(type = "TextField", label = "Search"))
    val searchIcon = nodeOf(detail = DriverNodeDetail.IosAxe(type = "Image", label = "Search"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(searchIcon, searchField))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(hintTextRegex = "Search"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(searchField.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - MAESTRO dialect is case-insensitive`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(label = "Log In"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "log in"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - AND semantics across textRegex and resourceIdRegex`() {
    nextId = 1L
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(label = "Log In", uniqueId = "login_button"))
    // Same text, different id — must not match when both constraints are specified.
    val wrongId = nodeOf(detail = DriverNodeDetail.IosAxe(label = "Log In", uniqueId = "other_button"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target, wrongId))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Log In", resourceIdRegex = "login_button"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - only unbridgeable fields specified returns NoMatch`() {
    nextId = 1L
    // AXe exposes no `focused` equivalent — a selector that constrains only `focused` must
    // not degenerate into matching every node in the tree.
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(label = "Anything"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(focused = true),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  @Test
  fun `IosMaestro bridge - focused and selected constraints fail closed`() {
    nextId = 1L
    // AXe carries no focused/selected signal, so the bridge cannot evaluate these
    // constraints faithfully — dropping them would false-match (e.g. a waypoint requiring
    // `focused: true` matching its non-focused sibling screen). The selector must not match
    // even though its bridgeable textRegex constraint would.
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(label = "Log In"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Log In", focused = true, selected = true),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  @Test
  fun `IosMaestro bridge - classNameRegex matches Maestro-era class alias`() {
    nextId = 1L
    // Maestro's iOS tree reported label views (`LabelView`, `UILabel`) where AXe reports
    // the semantic `StaticText` — and for tab-bar/nav items the label lives directly on a
    // `Button` node — so a bare label-view class is genuinely ambiguous across both and
    // resolves as MultipleMatches. An unmapped custom class must NOT match anything.
    val staticText = nodeOf(detail = DriverNodeDetail.IosAxe(type = "StaticText", label = "More"))
    val button = nodeOf(detail = DriverNodeDetail.IosAxe(type = "Button", label = "More"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(staticText, button))

    val viaLabelView = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(classNameRegex = "LabelView")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches>(viaLabelView)
    assertEquals(listOf(staticText.nodeId, button.nodeId), viaLabelView.nodes.map { it.nodeId })

    val viaUiLabel = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(classNameRegex = "UILabel")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches>(viaUiLabel)
    assertEquals(listOf(staticText.nodeId, button.nodeId), viaUiLabel.nodes.map { it.nodeId })

    val viaButtonLabel = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(classNameRegex = "UIButtonLabel")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(viaButtonLabel)
    assertEquals(button.nodeId, viaButtonLabel.node.nodeId)

    val viaCustomClass = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.IosMaestro(classNameRegex = "CustomAppTitleNavigationBarItemView"),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(viaCustomClass)
  }

  @Test
  fun `IosMaestro bridge - LabelView-recorded tab item resolves to its Button node`() {
    nextId = 1L
    // Tab-bar/nav items carry no separate StaticText on an AXe tree — the Button node holds
    // the label. A recorded `textRegex + classNameRegex: LabelView` tap must resolve to it
    // rather than silently dropping to the recorded-coordinate fallback.
    val moreTab = nodeOf(detail = DriverNodeDetail.IosAxe(type = "Button", label = "More"))
    val itemsTab = nodeOf(detail = DriverNodeDetail.IosAxe(type = "Button", label = "Items"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(moreTab, itemsTab))

    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.IosMaestro(textRegex = "More", classNameRegex = "LabelView"),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(moreTab.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - invalid-regex pattern degrades to exact literal`() {
    nextId = 1L
    // A recorded price like "$0.00" is regex-unmatchable (leading `$` is an end anchor) —
    // the bridge must still resolve it via the exact-equality fallback, and must not
    // false-match a different price.
    val target = nodeOf(detail = DriverNodeDetail.IosAxe(label = "$0.00"))
    val other = nodeOf(detail = DriverNodeDetail.IosAxe(label = "$5.00"))
    val root = nodeOf(detail = DriverNodeDetail.IosAxe(), children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "$0.00"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  // ======================================================================
  // IosAxe container-chrome guard
  //
  // The AXe Application root (and its Windows) carry the app name as AXLabel — the Settings
  // app's root is labeled "Settings" and sized to the screen. A text-driven selector matching
  // it would tap screen center instead of the intended element, so text matching skips the
  // container chrome unless the selector pins it explicitly (roleRegex/typeRegex/uniqueId).
  // ======================================================================

  @Test
  fun `IosAxe - anchored label selector skips the Application root and resolves the row`() {
    nextId = 1L
    val row = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Cell", role = "AXCell", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 300, 402, 344),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Application", role = "AXApplication", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      children = listOf(row),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosAxe(labelRegex = "^Settings$"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(row.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosAxe - label selector matching only the Application and Window chrome returns NoMatch`() {
    nextId = 1L
    val window = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Window", role = "AXWindow", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Application", role = "AXApplication", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      children = listOf(window),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosAxe(labelRegex = "^Settings$"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  @Test
  fun `IosAxe - explicit typeRegex still matches the Application root`() {
    nextId = 1L
    val root = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Application", role = "AXApplication", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosAxe(typeRegex = "Application", labelRegex = "^Settings$"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(root.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosAxe - bare containsChild text selector cannot match the chrome via a labeled Window`() {
    nextId = 1L
    // Recorded shape with no driver match on the candidate: `containsChild: {textRegex: …}`.
    // The Application's direct child is a Window labeled with the app name, so without the
    // selector-level guard the Application matches via that child, sorts first at (0,0), and
    // taps screen center. The row's real wrapper must win instead.
    val row = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Cell", role = "AXCell", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 300, 402, 344),
    )
    val group = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Other", role = "AXGroup"),
      bounds = TrailblazeNode.Bounds(0, 280, 402, 360),
      children = listOf(row),
    )
    val window = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Window", role = "AXWindow", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      children = listOf(group),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Application", role = "AXApplication", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      children = listOf(window),
    )

    val selector = TrailblazeNodeSelector(
      containsChild = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "Settings")),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(group.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosAxe - bare containsDescendants text selector skips the Application and Window chrome`() {
    nextId = 1L
    // Every ancestor of a text-bearing node matches a bare containsDescendants text selector,
    // including the screen-sized chrome — which would sort first at (0,0). Only the real
    // wrapper below the chrome may resolve.
    val label = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "StaticText", role = "AXStaticText", label = "General"),
      bounds = TrailblazeNode.Bounds(16, 310, 386, 334),
    )
    val rowWrapper = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Cell", role = "AXCell"),
      bounds = TrailblazeNode.Bounds(0, 300, 402, 344),
      children = listOf(label),
    )
    val window = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Window", role = "AXWindow"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      children = listOf(rowWrapper),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Application", role = "AXApplication", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      children = listOf(window),
    )

    val selector = TrailblazeNodeSelector(
      containsDescendants = listOf(
        TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(textRegex = "General")),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(rowWrapper.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - textRegex skips the Application root`() {
    nextId = 1L
    val row = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "StaticText", role = "AXStaticText", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 300, 402, 344),
    )
    val root = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Application", role = "AXApplication", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
      children = listOf(row),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Settings"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(row.nodeId, result.node.nodeId)
  }

  @Test
  fun `IosMaestro bridge - explicit classNameRegex still matches the Application root`() {
    nextId = 1L
    // The bridged pin mirrors the native typeRegex escape hatch: classNameRegex bridges to
    // the AXe type/role, so a selector that names the container explicitly may match it.
    val root = nodeOf(
      detail = DriverNodeDetail.IosAxe(type = "Application", role = "AXApplication", label = "Settings"),
      bounds = TrailblazeNode.Bounds(0, 0, 402, 874),
    )

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.IosMaestro(textRegex = "Settings", classNameRegex = "Application"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(root.nodeId, result.node.nodeId)
  }

  // --- TargetTemplateContext expansion at the resolver entry ---
  //
  // The 3-arg `resolve(root, selector, target)` overload expands `{{target.appId}}`
  // placeholders before matching. These tests pin the resolver-level integration —
  // SelectorTemplating's own tests pin the substitution rules in isolation; these pin
  // that the new chokepoint actually invokes them on the way in.

  @Test
  fun `resolver expands target appId placeholder in resourceIdRegex`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.example.test:id/foo"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.other:id/foo"))
    val root = node(children = listOf(target, other))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "^{{target.appId}}:id/foo$"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root = root,
      selector = selector,
      target = TargetTemplateContext(appId = "com.example.test"),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }

  @Test
  fun `resolver falls back to appIds alternation when appId is null`() {
    nextId = 1L
    val dev = node(detail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.example.dev:id/foo"))
    val prod = node(detail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.example:id/foo"))
    val miss = node(detail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.other:id/foo"))
    val root = node(children = listOf(dev, prod, miss))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "^{{target.appId}}:id/foo$"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root = root,
      selector = selector,
      target = TargetTemplateContext(appId = null, appIds = listOf("com.example.dev", "com.example")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches>(result)
    assertEquals(setOf(dev.nodeId, prod.nodeId), result.nodes.map { it.nodeId }.toSet())
  }

  @Test
  fun `resolver expands placeholder inside nested containsChild selector`() {
    nextId = 1L
    val child = node(detail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.example.test:id/inner"))
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "outer"),
      children = listOf(child),
    )
    val root = node(children = listOf(parent))

    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "outer"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(
          resourceIdRegex = "^{{target.appId}}:id/inner$",
        ),
      ),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root = root,
      selector = selector,
      target = TargetTemplateContext(appId = "com.example.test"),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(parent.nodeId, result.node.nodeId)
  }

  @Test
  fun `resolver with null target leaves placeholder un-substituted and matches nothing`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(resourceId = "com.example.test:id/foo"))
    val root = node(children = listOf(target))

    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "^{{target.appId}}:id/foo$"),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root = root, selector = selector, target = null)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }
}
