package xyz.block.trailblaze.api

import xyz.block.trailblaze.util.escapeForSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailblazeNodeSelectorGeneratorAndroidAccessibilityTest : TrailblazeNodeSelectorGeneratorTestBase() {

  /** Convenience for Android Accessibility nodes (the most common case). */
  private fun node(
    detail: DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = nodeOf(detail, bounds, children)

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

  // -- Strategy 1: composeTestTag (third tier of identity strategy) --

  @Test
  fun `composeTestTag selector when no uniqueId or resourceId`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(composeTestTag = "checkout_btn"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Other"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.composeTestTagRegex)
  }

  @Test
  fun `resourceId beats composeTestTag in identity tier`() {
    // When both are present, resourceId wins because it sits above composeTestTag in the
    // identity-tier fallback chain (uniqueId > resourceId > composeTestTag).
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        resourceId = "com.example:id/checkout",
        composeTestTag = "checkout_btn",
      ),
    )
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Other"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.resourceIdRegex)
    // composeTestTag is captured on the node but not consulted when resourceId resolves uniquely.
  }

  // -- Strategy 16b: roleDescription + className --

  @Test
  fun `roleDescription plus className disambiguation`() {
    // Three siblings make BOTH roleDescription and className load-bearing:
    //   target           : role=Toggle, class=ImageButton
    //   sharesClass      : role=Tab,    class=ImageButton  (kills className-only)
    //   sharesRoleDescr  : role=Toggle, class=ImageView    (kills role-only)
    // After minimization neither field should drop — both are individually
    // necessary to disambiguate the target.
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        roleDescription = "Toggle",
        className = "android.widget.ImageButton",
      ),
    )
    val sharesClass = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        roleDescription = "Tab",
        className = "android.widget.ImageButton",
      ),
    )
    val sharesRoleDescr = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        roleDescription = "Toggle",
        className = "android.widget.ImageView",
      ),
    )
    val root = node(children = listOf(target, sharesClass, sharesRoleDescr))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.roleDescriptionRegex)
    assertNotNull(match.classNameRegex)
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
    // Three siblings keep both text AND className load-bearing post-minimization:
    //   target      : text=Fries,    class=EditText
    //   sharesText  : text=Fries,    class=TextView   (kills text-only)
    //   sharesClass : text=Lemonade, class=EditText   (kills className-only)
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Fries",
        className = "android.widget.EditText",
      ),
    )
    val sharesText = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Fries",
        className = "android.widget.TextView",
      ),
    )
    val sharesClass = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Lemonade",
        className = "android.widget.EditText",
      ),
    )
    val root = node(children = listOf(target, sharesText, sharesClass))

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
    // Three siblings make BOTH className and isPassword load-bearing:
    //   target           : class=EditText, isPassword=true
    //   sharesClass      : class=EditText, isPassword=false  (kills className-only)
    //   sharesPassword   : class=TextView, isPassword=true   (kills isPassword-only)
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        isPassword = true,
        isEditable = true,
      ),
    )
    val sharesClass = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        isEditable = true,
      ),
    )
    val sharesPassword = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        isPassword = true,
      ),
    )
    val root = node(children = listOf(target, sharesClass, sharesPassword))

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

  // -- Strategy 19: collectionItemInfo --

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
  fun `index fallback for identical nodes keeps the className anchor alongside index`() {
    // Three nodes that share className=View — disambiguable only by position.
    // The selector must carry an index, but it should NOT be purely positional:
    // the className anchor is retained so the ordinal isn't naked (a bare index
    // shifts whenever anything before the target changes).
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
    val match = selector.driverMatch as? DriverNodeMatch.AndroidAccessibility
    assertNotNull(match, "index must be paired with a content/structural anchor, not be naked")
    assertEquals("android.view.View", match.classNameRegex)
  }

  @Test
  fun `index fallback for truly attribute-less nodes is a bare index`() {
    // No matchable attributes at all (no text/contentDescription/className/
    // resourceId/uniqueId) — there is nothing to anchor on, so a bare index is
    // the legitimate last resort.
    nextId = 1L
    val node1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val node2 = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 50, 100, 100),
    )
    val node3 = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val root = node(
      children = listOf(node1, node2, node3),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 150),
    )

    val selector = assertUniqueMatch(root, node2)
    assertNotNull(selector.index, "Expected index-based selector for identical nodes")
    assertNull(
      selector.driverMatch,
      "attribute-less nodes have no anchor — bare index is the legitimate last resort",
    )
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

  // -- Stable text anchor: volatile trailing count is generalized (Fix A) --

  /** Resolves [selector] against [tree], asserting a single match on the node with [text]. */
  private fun assertResolvesToTextNode(
    tree: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    text: String,
  ) {
    val result = TrailblazeNodeSelectorResolver.resolve(tree, selector)
    assertTrue(
      result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch,
      "Expected SingleMatch but got $result for selector: ${selector.description()}",
    )
    val match = (result.node.driverDetail as DriverNodeDetail.AndroidAccessibility)
    assertEquals(text, match.text)
  }

  @Test
  fun `count-in-parens text generalizes so a changed count still resolves`() {
    // Recording: a tab labeled with a live count. The generated text anchor must pin the
    // stable head ("All tickets") and allow any count, so replay resolves even though the
    // count moved (307 -> 12). Without the fix the literal pin "All tickets (307)" NoMatches.
    nextId = 1L
    val recorded = node(detail = DriverNodeDetail.AndroidAccessibility(text = "All tickets (307)"))
    val sibling = node(detail = DriverNodeDetail.AndroidAccessibility(text = "My tickets (5)"))
    val recordedRoot = node(children = listOf(recorded, sibling))

    val selector = assertUniqueMatch(recordedRoot, recorded)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.textRegex)

    // Replay tree: same screen, count changed.
    nextId = 100L
    val replayTarget = node(detail = DriverNodeDetail.AndroidAccessibility(text = "All tickets (12)"))
    val replaySibling = node(detail = DriverNodeDetail.AndroidAccessibility(text = "My tickets (9)"))
    val replayRoot = node(children = listOf(replayTarget, replaySibling))

    assertResolvesToTextNode(replayRoot, selector, "All tickets (12)")

    // Negative control: the generalized anchor must NOT widen onto the unrelated sibling.
    val onSibling = TrailblazeNodeSelectorResolver.resolve(
      node(children = listOf(replaySibling)),
      selector,
    )
    assertTrue(
      onSibling is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch,
      "Generalized 'All tickets' anchor must not match the 'My tickets' sibling, got $onSibling",
    )
  }

  @Test
  fun `trailing bare count text generalizes so a changed count still resolves`() {
    nextId = 1L
    val recorded = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Cart 3"))
    val sibling = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Saved 8"))
    val recordedRoot = node(children = listOf(recorded, sibling))

    val selector = assertUniqueMatch(recordedRoot, recorded)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertNotNull(match.textRegex)

    nextId = 100L
    val replayTarget = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Cart 9"))
    val replayRoot = node(children = listOf(replayTarget))

    assertResolvesToTextNode(replayRoot, selector, "Cart 9")
  }

  @Test
  fun `pure-numeric text stays literal so a different value does not match`() {
    // A value-pinned label like a count badge: "307" must keep matching exactly "307"
    // and reject "308", or value assertions silently pass on the wrong number.
    nextId = 1L
    val recorded = node(detail = DriverNodeDetail.AndroidAccessibility(text = "307"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Tickets"))
    val recordedRoot = node(children = listOf(recorded, other))

    val selector = assertUniqueMatch(recordedRoot, recorded)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("307", match.textRegex, "pure-numeric text must stay literal, not be generalized")

    val changed = node(detail = DriverNodeDetail.AndroidAccessibility(text = "308"))
    val changedRoot = node(children = listOf(changed))
    val result = TrailblazeNodeSelectorResolver.resolve(changedRoot, selector)
    assertTrue(
      result is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch,
      "Literal '307' must not match '308', got $result",
    )
  }

  @Test
  fun `currency text stays literal so a different amount does not match`() {
    nextId = 1L
    val recorded = node(detail = DriverNodeDetail.AndroidAccessibility(text = "$3.00"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Total"))
    val recordedRoot = node(children = listOf(recorded, other))

    val selector = assertUniqueMatch(recordedRoot, recorded)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    // Same output escapeForSelector would have produced — no generalization of the amount.
    assertEquals(escapeForSelector("$3.00"), match.textRegex)

    val changed = node(detail = DriverNodeDetail.AndroidAccessibility(text = "$4.00"))
    val changedRoot = node(children = listOf(changed))
    val result = TrailblazeNodeSelectorResolver.resolve(changedRoot, selector)
    assertTrue(
      result is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch,
      "Literal '\$3.00' must not match '\$4.00', got $result",
    )
  }

  @Test
  fun `plain text with no volatile count is byte-identical to escapeForSelector`() {
    // Regression guard mirroring MinimizerTest's 'text-plus-className collapses to text alone':
    // text that carries no trailing numeric must pass through unchanged so existing recordings
    // and the minimizer's stability ordering are untouched.
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Cancel"))
    val root = node(children = listOf(target, other))

    val selector = assertUniqueMatch(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals(escapeForSelector("Submit"), match.textRegex)
    assertEquals("Submit", match.textRegex)
  }

}
