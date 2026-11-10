package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The canonical-selector bridge: an `androidAccessibility` selector — the one shape every
 * Android driver can evaluate — resolves against the ANDROID_TEST driver's in-process hybrid
 * tree of [DriverNodeDetail.AndroidView] and [DriverNodeDetail.Compose] nodes.
 *
 * The selector cases here are the real ones from the three in-process trails
 * (`square-android-inprocess`), which today carry per-backend `androidView:`/`compose:` keys.
 * This suite is the proof that the same predicates resolve to the same nodes with the backend
 * DERIVED from where the matched node lives, not declared in the trail.
 *
 * Strictness is load-bearing in both directions: a predicate a backend cannot answer must fail
 * the constraint (never be silently ignored), and NATIVE dialect case-sensitivity must survive
 * the bridge — a selector must never match more loosely in-process than it would on the a11y
 * tree it is canonical for.
 */
class CanonicalSelectorBridgeTest {

  private var nextId = 1L

  private fun node(
    detail: DriverNodeDetail,
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode {
    val id = nextId++
    return TrailblazeNode(nodeId = id, children = children, bounds = bounds, driverDetail = detail)
  }

  private fun a11ySelector(match: DriverNodeMatch.AndroidAccessibility, index: Int? = null) =
    TrailblazeNodeSelector.withMatch(match, index = index)

  /** A miniature of the real hybrid tree: Views-in-Compose checkout plus a Compose landing. */
  private fun hybridTree(): Pair<TrailblazeNode, Map<String, Long>> {
    nextId = 1L
    val ids = mutableMapOf<String, Long>()
    fun remember(key: String, n: TrailblazeNode): TrailblazeNode {
      ids[key] = n.nodeId
      return n
    }
    val keypadDigit = remember(
      "digit2",
      node(DriverNodeDetail.AndroidView(text = "2", isClickable = true)),
    )
    val chargeButton = remember(
      "charge",
      node(DriverNodeDetail.AndroidView(text = "Charge $25.00", isClickable = true)),
    )
    val libraryTab = remember(
      "library",
      node(DriverNodeDetail.AndroidView(text = "Library", isClickable = true)),
    )
    val signIn = remember(
      "signIn",
      node(DriverNodeDetail.Compose(text = "Sign in", hasClickAction = true)),
    )
    val taggedField = remember(
      "tagged",
      node(DriverNodeDetail.Compose(testTag = "email_field", hasSetTextAction = true)),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(keypadDigit, chargeButton, libraryTab, signIn, taggedField),
    )
    return root to ids
  }

  // -- The real trails' selectors, backend-free --

  @Test
  fun `text selector resolves a View node without naming the backend`() {
    val (root, ids) = hybridTree()
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Library")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(ids["library"], result.node.nodeId)
  }

  /**
   * The accessibility tree publishes a text field's description on a node of its own, apart from
   * the field's empty text, so a recording names the field by its description as text.
   */
  @Test
  fun `text selector resolves an empty Compose field by its description`() {
    val field = node(
      DriverNodeDetail.Compose(editableText = "", contentDescription = "Search for people", hasSetTextAction = true),
    )
    val label = node(DriverNodeDetail.Compose(text = "Search for people"), bounds = TrailblazeNode.Bounds(0, 400, 1080, 500))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(field),
    )

    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Search for people")),
    )

    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(field.nodeId, result.node.nodeId)
    // Only a field's description reads as text; a plain node's text still wins over its own.
    val described = node(DriverNodeDetail.Compose(text = "Send", contentDescription = "Search for people"))
    val plainRoot = root.copy(children = listOf(described, label))
    val plain = TrailblazeNodeSelectorResolver.resolve(
      plainRoot,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Search for people")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(plain)
    assertEquals(label.nodeId, plain.node.nodeId)
  }

  @Test
  fun `text selector resolves a Compose node without naming the backend`() {
    val (root, ids) = hybridTree()
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Sign in")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(ids["signIn"], result.node.nodeId)
  }

  @Test
  fun `escaped regex from the keypad trail matches the computed charge amount`() {
    val (root, ids) = hybridTree()
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = """Charge \$25\.00""")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(ids["charge"], result.node.nodeId)
  }

  @Test
  fun `index disambiguates duplicates across the whole hybrid tree`() {
    nextId = 1L
    val first = node(
      DriverNodeDetail.Compose(text = "Automation Test Item"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val second = node(
      DriverNodeDetail.Compose(text = "Automation Test Item"),
      bounds = TrailblazeNode.Bounds(0, 200, 100, 250),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(second, first),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(
        DriverNodeMatch.AndroidAccessibility(textRegex = "Automation Test Item"),
        index = 0,
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(first.nodeId, result.node.nodeId)
  }

  // -- Identity mappings --

  @Test
  fun `resourceIdRegex matches a Compose testTag, the testTagsAsResourceId contract`() {
    val (root, ids) = hybridTree()
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "email_field")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(ids["tagged"], result.node.nodeId)
  }

  @Test
  fun `composeTestTagRegex matches a Compose node and never a View node`() {
    val (root, ids) = hybridTree()
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(composeTestTagRegex = "email_field")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(ids["tagged"], result.node.nodeId)
  }

  // -- Strictness: unanswerable predicates fail, never loosen --

  @Test
  fun `classNameRegex never matches a Compose node even when its text matches`() {
    nextId = 1L
    val composeButton = node(
      DriverNodeDetail.Compose(text = "Sign in", role = "Button", hasClickAction = true),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(composeButton),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(
        DriverNodeMatch.AndroidAccessibility(
          textRegex = "Sign in",
          classNameRegex = "android.widget.Button",
        ),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  /**
   * A recorded `classNameRegex` names what the accessibility tree published, which for a custom
   * view is the framework class it reports through `getAccessibilityClassName()` — never the
   * runtime class. A real trail selects a Settings row as `android.view.View` + `index: 6`, with
   * no text, id or description to fall back on, so matching the runtime class finds nothing.
   */
  @Test
  fun `classNameRegex matches the a11y class name a custom View reports, not its runtime class`() {
    nextId = 1L
    val row = node(
      DriverNodeDetail.AndroidView(
        className = "com.example.settings.SettingsRowView",
        accessibilityClassName = "android.view.View",
      ),
    )
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(row),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(classNameRegex = "android.view.View")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(row.nodeId, result.node.nodeId)
  }

  /**
   * A recorded `roleDescriptionRegex: Switch` names a Compose switch by the role description the
   * accessibility delegate publishes for it. A role the delegate does not describe declines.
   */
  @Test
  fun `role description matches a Compose switch and declines a role it does not describe`() {
    nextId = 1L
    val switch = node(DriverNodeDetail.Compose(role = "Switch", toggleableState = "Off"))
    val checkbox = node(DriverNodeDetail.Compose(role = "Checkbox", toggleableState = "Off"))
    val root = node(DriverNodeDetail.Compose(), children = listOf(switch, checkbox))
    val switchResult = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(roleDescriptionRegex = "Switch", isChecked = false)),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(switchResult)
    assertEquals(switch.nodeId, switchResult.node.nodeId)
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(
      TrailblazeNodeSelectorResolver.resolve(
        root,
        a11ySelector(DriverNodeMatch.AndroidAccessibility(roleDescriptionRegex = "Checkbox")),
      ),
    )
  }

  /**
   * A recorded `stateDescriptionRegex: Selected` names what the accessibility delegate published
   * for a selectable Compose node. The projected description answers it; the app's own description
   * still answers on a node whose collector projected none.
   */
  @Test
  fun `state description matches the delegate's projected text, and the app's own without one`() {
    nextId = 1L
    val selectedRow = node(DriverNodeDetail.Compose(text = "Card", isSelected = true, accessibilityStateDescription = "Selected"))
    val otherRow = node(DriverNodeDetail.Compose(text = "Coupon", accessibilityStateDescription = "Not selected"))
    val unprojected = node(DriverNodeDetail.Compose(text = "Gift card", stateDescription = "Chosen"))
    val root = node(DriverNodeDetail.Compose(), children = listOf(selectedRow, otherRow, unprojected))
    val selected = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(stateDescriptionRegex = "Selected")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(selected)
    assertEquals(selectedRow.nodeId, selected.node.nodeId)
    val chosen = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(stateDescriptionRegex = "Chosen")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(chosen)
    assertEquals(unprojected.nodeId, chosen.node.nodeId)
  }

  /**
   * A recorded `hintTextRegex` names a Compose text field by its label. The field matches, and a
   * node without the projection — the label's own Text node, or a tree that projects nothing —
   * declines rather than matching loosely.
   */
  @Test
  fun `hint text matches a Compose text field by its projected label and nothing else`() {
    nextId = 1L
    val label = node(DriverNodeDetail.Compose(text = "Name"))
    val field = node(
      DriverNodeDetail.Compose(editableText = "", hasSetTextAction = true, accessibilityHintText = "Name"),
      children = listOf(label),
    )
    val unprojected = node(DriverNodeDetail.Compose(editableText = "", hasSetTextAction = true))
    val root = node(DriverNodeDetail.Compose(), children = listOf(field, unprojected))
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(hintTextRegex = "Name")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(field.nodeId, result.node.nodeId)
  }

  /**
   * Grid position is answered on the View backend, and answered strictly: a view that is not a
   * collection item reports null and so fails a constraint naming a position rather than matching
   * it loosely. A real trail's grid tiles carry no other distinguishing property.
   */
  @Test
  fun `collection row and column match a View node and a non-item never does`() {
    nextId = 1L
    val tile = node(
      DriverNodeDetail.AndroidView(
        resourceId = "checkout_grid_tile_empty",
        collectionItemRowIndex = 0,
        collectionItemColumnIndex = 1,
      ),
    )
    val plain = node(DriverNodeDetail.AndroidView(resourceId = "checkout_grid_tile_empty"))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(tile, plain),
    )
    val match = DriverNodeMatch.AndroidAccessibility(
      resourceIdRegex = "checkout_grid_tile_empty",
      collectionItemRowIndex = 0,
      collectionItemColumnIndex = 1,
    )
    val result = TrailblazeNodeSelectorResolver.resolve(root, a11ySelector(match))
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(tile.nodeId, result.node.nodeId)

    val otherColumn = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(match.copy(collectionItemColumnIndex = 0)),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(otherColumn)
  }

  @Test
  fun `uniqueId never matches on either in-process backend`() {
    val (root, _) = hybridTree()
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Library", uniqueId = "x")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  @Test
  fun `bridge keeps NATIVE case-sensitivity`() {
    val (root, _) = hybridTree()
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = "library")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.NoMatch>(result)
  }

  // -- State mappings --

  @Test
  fun `isCheckable maps to View isChecked nullability`() {
    nextId = 1L
    val checkbox = node(DriverNodeDetail.AndroidView(text = "Remember me", isChecked = false))
    val label = node(DriverNodeDetail.AndroidView(text = "Remember me"))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(checkbox, label),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(
        DriverNodeMatch.AndroidAccessibility(textRegex = "Remember me", isCheckable = true),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(checkbox.nodeId, result.node.nodeId)
  }

  @Test
  fun `isChecked maps to Compose toggleableState On`() {
    nextId = 1L
    val on = node(DriverNodeDetail.Compose(text = "Wifi", toggleableState = "On"))
    val off = node(DriverNodeDetail.Compose(text = "Wifi", toggleableState = "Off"))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(on, off),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Wifi", isChecked = true)),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(on.nodeId, result.node.nodeId)
  }

  /**
   * Compose's accessibility delegate publishes a selectable node that is not a tab as checkable,
   * checked when selected: a bottom-navigation item reads `checked` on the accessibility tree, and
   * recordings name the open one that way. A tab publishes `selected` instead.
   */
  @Test
  fun `isChecked maps to a selected Compose node that is not a tab`() {
    nextId = 1L
    fun item(selected: Boolean, label: String, role: String? = null) = node(
      DriverNodeDetail.Compose(
        role = role,
        isSelected = selected,
        accessibilityStateDescription = if (selected) "Selected" else "Not selected",
        hasClickAction = true,
      ),
      children = listOf(node(DriverNodeDetail.Compose(role = "Image", contentDescription = label))),
    )
    val open = item(selected = true, label = "Neighborhoods")
    val closed = item(selected = false, label = "Home")
    val tab = item(selected = true, label = "Explore", role = "Tab")
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(open, closed, tab),
    )
    fun checked(checked: Boolean) = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(isChecked = checked),
        containsChild = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = ".+"),
        ),
      ),
    )

    val selected = assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(checked(true))
    assertEquals(open.nodeId, selected.node.nodeId, "the selected item reads checked; the selected tab does not")
    // Unchecked is everything else, the tab included, as `AccessibilityNodeInfo.isChecked` reads.
    val unselected = assertIs<TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches>(checked(false))
    assertEquals(setOf(closed.nodeId, tab.nodeId), unselected.nodes.map { it.nodeId }.toSet())
  }

  /**
   * Whether a node is selectable comes from the semantics, not its state description: an app's own
   * description replaces the delegate's "Not selected", and any node can be described that way.
   */
  @Test
  fun `isCheckable follows whether a Compose node is selectable, whatever its state description`() {
    nextId = 1L
    fun item(label: String, selectable: Boolean, description: String) = node(
      DriverNodeDetail.Compose(
        isSelected = false,
        isSelectable = selectable,
        accessibilityStateDescription = description,
        hasClickAction = true,
      ),
      children = listOf(node(DriverNodeDetail.Compose(role = "Image", contentDescription = label))),
    )
    val customDescribed = item("Home", selectable = true, description = "Tap to open Home")
    val describedButNotSelectable = item("Help", selectable = false, description = "Not selected")
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(customDescribed, describedButNotSelectable),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(isCheckable = true),
        containsChild = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = ".+"),
        ),
      ),
    )

    val match = assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(customDescribed.nodeId, match.node.nodeId)
  }

  @Test
  fun `isClickable maps to Compose hasClickAction`() {
    nextId = 1L
    val actionable = node(DriverNodeDetail.Compose(text = "Sign in", hasClickAction = true))
    val plainText = node(DriverNodeDetail.Compose(text = "Sign in"))
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
      children = listOf(actionable, plainText),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(
        DriverNodeMatch.AndroidAccessibility(textRegex = "Sign in", isClickable = true),
      ),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(actionable.nodeId, result.node.nodeId)
  }

  @Test
  fun `isEditable maps to Compose hasSetTextAction`() {
    val (root, ids) = hybridTree()
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(isEditable = true)),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(ids["tagged"], result.node.nodeId)
  }

  // -- The a11y detail path is untouched --

  @Test
  fun `a11y selector against a11y detail nodes still matches directly`() {
    nextId = 1L
    val target = node(DriverNodeDetail.AndroidAccessibility(text = "Submit"))
    val root = node(
      DriverNodeDetail.AndroidAccessibility(),
      children = listOf(target),
    )
    val result = TrailblazeNodeSelectorResolver.resolve(
      root,
      a11ySelector(DriverNodeMatch.AndroidAccessibility(textRegex = "Submit")),
    )
    assertIs<TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch>(result)
    assertEquals(target.nodeId, result.node.nodeId)
  }
}
