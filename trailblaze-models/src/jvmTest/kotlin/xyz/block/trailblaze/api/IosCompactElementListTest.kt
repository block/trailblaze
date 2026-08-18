package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosCompactElementListTest {

  private var nextId = 0L

  private fun node(
    detail: DriverNodeDetail.IosMaestro = DriverNodeDetail.IosMaestro(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode =
    TrailblazeNode(
      nodeId = nextId++,
      driverDetail = detail,
      bounds = bounds,
      children = children,
    )

  @Test
  fun `clickable button with text gets element ref`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Sign In",
            clickable = true,
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UIButton \"Sign In\"")
    assertTrue(result.elementNodeIds.contains(0L))
  }

  @Test
  fun `text field with hint gets element ref`() {
    val input =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITextField",
            hintText = "Email address",
            clickable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UITextField \"Email address\"")
  }

  @Test
  fun `wide short empty text field with hint and resourceId is not filtered as a scrollbar`() {
    // Regression: an empty, unfocused SwiftUI TextField is captured by Maestro as a node carrying
    // only hintText (the placeholder) + resourceId — no className, no clickable flag — with bounds
    // like 16,78..386,112 (370x34). That aspect ratio (>10) previously tripped the
    // horizontal-scrollbar heuristic in isSystemUi and the field was dropped from the list, so the
    // agent couldn't see — or tap by ref — a field plainly on screen. Identifying metadata
    // (resourceId / hintText) now exempts a node from the chrome heuristics.
    val field =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            hintText = "Name",
            resourceId = "field_name",
          ),
        bounds = TrailblazeNode.Bounds(16, 78, 386, 112),
      )
    val root = node(children = listOf(field))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "Name")
    assertContains(result.text, "field_name")
    assertTrue(
      result.elementNodeIds.contains(field.nodeId),
      "Empty text field with a placeholder + resourceId should get an element ref, not be filtered",
    )
  }

  @Test
  fun `resourceId-only field stays out of compact view but is surfaced with a ref under ALL_ELEMENTS`() {
    // A SwiftUI TextField with NO placeholder exposes only an accessibility identifier — Maestro
    // gives it just a resourceId (no text/hintText/accessibilityText, no className). It must NOT
    // bloat the lean per-turn compact view (it isn't "meaningful"), but `requestDetailedViewHierarchy`
    // (ALL_ELEMENTS) must surface it with a ref so the agent can see and tap it.
    val field =
      node(
        detail = DriverNodeDetail.IosMaestro(resourceId = "field_secret"),
        bounds = TrailblazeNode.Bounds(16, 78, 386, 112),
      )
    val root = node(children = listOf(field))

    val compact = IosCompactElementList.build(root)
    assertFalse(
      compact.elementNodeIds.contains(field.nodeId),
      "A label-less resourceId-only field should stay out of the lean compact view",
    )

    val all = IosCompactElementList.build(root, details = setOf(SnapshotDetail.ALL_ELEMENTS))
    // Rendered as the generic `element` descriptor with the [id=…] annotation carrying the identity.
    assertContains(all.text, "element")
    assertContains(all.text, "field_secret")
    assertTrue(
      all.elementNodeIds.contains(field.nodeId),
      "ALL_ELEMENTS should surface the resourceId-only field with a ref",
    )
  }

  @Test
  fun `even a clickable label-less id-only field is gated to ALL_ELEMENTS, not the compact view`() {
    // A clickable element carrying only a resourceId (no text/hintText/accessibilityText/className)
    // IS "meaningful" (clickable). The `element` fallback is gated EXPLICITLY on includeAllElements
    // (not the branch's `includeAllElements || isMeaningful`), so even this meaningful node must NOT
    // leak into the lean default compact view — that keeps the default byte-identical. It surfaces
    // only under ALL_ELEMENTS (requestDetailedViewHierarchy).
    val button =
      node(
        detail = DriverNodeDetail.IosMaestro(resourceId = "icon_button", clickable = true),
        bounds = TrailblazeNode.Bounds(16, 78, 56, 118),
      )
    val root = node(children = listOf(button))

    assertFalse(
      IosCompactElementList.build(root).elementNodeIds.contains(button.nodeId),
      "A clickable label-less id-only element must stay out of the lean default compact view",
    )

    val all = IosCompactElementList.build(root, details = setOf(SnapshotDetail.ALL_ELEMENTS))
    assertContains(all.text, "icon_button")
    assertTrue(
      all.elementNodeIds.contains(button.nodeId),
      "ALL_ELEMENTS should surface the clickable id-only element with a ref",
    )
  }

  @Test
  fun `bare clickable node with no label, class, or id gets exact coordinates in default view`() {
    // A node with NO text, NO className, NO resourceId — just clickable=true — gets emitted
    // as @(centerX,centerY) via the isMeaningful branch.
    val button =
      node(
        detail = DriverNodeDetail.IosMaestro(clickable = true),
        bounds = TrailblazeNode.Bounds(left = 143, top = 659, right = 203, bottom = 697),
      )
    val root = node(children = listOf(button))

    val result = IosCompactElementList.build(root)
    assertTrue(
      result.elementNodeIds.contains(button.nodeId),
      "A bare clickable node must appear in the default compact view with coordinates",
    )
    // Center of Bounds(143, 659, 203, 697) → (173, 678)
    assertContains(result.text, "@(173,678)")
  }

  @Test
  fun `bare leaf node with all-null properties gets coordinates via structural else branch`() {
    // The real-world case: an iOS three-dot icon button with NO text, NO className, NO
    // resourceId, and even clickable=false (iOS accessibility doesn't mark it interactive).
    // Such a node fails isMeaningful entirely and falls into the structural "else" branch.
    // It must still be emitted with coordinates so the LLM can use tapOnPoint precisely.
    val threeDotsButton =
      node(
        detail = DriverNodeDetail.IosMaestro(), // all defaults — clickable=false
        bounds = TrailblazeNode.Bounds(left = 161, top = 658, right = 185, bottom = 698),
      )
    val label =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = "test.pdf"),
        bounds = TrailblazeNode.Bounds(left = 75, top = 670, right = 130, bottom = 686),
      )
    val row = node(children = listOf(threeDotsButton, label))
    val root = node(children = listOf(row))

    val result = IosCompactElementList.build(root)
    // Center of Bounds(161, 658, 185, 698) → (173, 678)
    assertContains(result.text, "@(173,678)")
    assertTrue(
      result.elementNodeIds.contains(threeDotsButton.nodeId),
      "Three-dot button nodeId must be in elementNodeIds",
    )
    // The labeled sibling must still appear too
    assertContains(result.text, "test.pdf")
  }

  @Test
  fun `clickable control hoists a lone icon child resourceId into its id annotation`() {
    // The overflow-button case: a clickable ButtonView labeled "More" (VoiceOver) wrapping a
    // single UIImageView that carries the accessibilityIdentifier "ellipsis-horizontal". Neither
    // node alone is a usable identified-tappable element — the compact view must attribute the
    // child's id to the clickable control so the agent can target it by id.
    val icon =
      node(
        detail =
          DriverNodeDetail.IosMaestro(className = "UIImageView", resourceId = "ellipsis-horizontal"),
        bounds = TrailblazeNode.Bounds(281, 83, 303, 105),
      )
    val button =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "ButtonView",
            accessibilityText = "More",
            clickable = true,
          ),
        bounds = TrailblazeNode.Bounds(272, 74, 312, 114),
        children = listOf(icon),
      )
    val root = node(children = listOf(button))

    val result = IosCompactElementList.build(root)
    assertContains(result.text, "ButtonView \"More\"")
    assertContains(result.text, "[id=ellipsis-horizontal]")
    assertTrue(
      result.elementNodeIds.contains(button.nodeId),
      "The clickable control must be emitted with a ref",
    )
  }

  @Test
  fun `a clickable control with a hoisted id is not dropped as a label duplicate`() {
    // A bottom-nav "More" tab and a top-right "More" overflow button share the VoiceOver label
    // "More" but are distinct, separately-tappable controls. The overflow button carries a
    // distinct id (via its icon child), so it must NOT be dedup-dropped just because "More" was
    // already emitted by the tab.
    val tab =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "CombinableView",
            accessibilityText = "More",
            resourceId = "applet-tab-more",
            clickable = true,
          ),
        bounds = TrailblazeNode.Bounds(300, 800, 380, 860),
      )
    val icon =
      node(
        detail =
          DriverNodeDetail.IosMaestro(className = "UIImageView", resourceId = "ellipsis-horizontal"),
        bounds = TrailblazeNode.Bounds(281, 83, 303, 105),
      )
    val overflow =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "ButtonView",
            accessibilityText = "More",
            clickable = true,
          ),
        bounds = TrailblazeNode.Bounds(272, 74, 312, 114),
        children = listOf(icon),
      )
    val root = node(children = listOf(tab, overflow))

    val result = IosCompactElementList.build(root)
    assertContains(result.text, "[id=applet-tab-more]")
    assertContains(result.text, "[id=ellipsis-horizontal]")
    assertTrue(
      result.elementNodeIds.contains(overflow.nodeId),
      "The overflow 'More' control must survive dedup because it carries a distinct id",
    )
  }

  @Test
  fun `label-only duplicate without an id is still deduped`() {
    // Regression guard: the id-aware dedup relaxation must NOT stop plain label duplicates from
    // being collapsed. Two identical labels with no distinguishing id → one line.
    val first =
      node(
        detail = DriverNodeDetail.IosMaestro(className = "UILabel", accessibilityText = "Details"),
        bounds = TrailblazeNode.Bounds(0, 200, 300, 240),
      )
    val second =
      node(
        detail = DriverNodeDetail.IosMaestro(className = "UILabel", accessibilityText = "Details"),
        bounds = TrailblazeNode.Bounds(0, 300, 300, 340),
      )
    val root = node(children = listOf(first, second))

    val result = IosCompactElementList.build(root)
    assertEquals(
      1,
      Regex("\"Details\"").findAll(result.text).count(),
      "A label-only duplicate with no distinguishing id must still be deduped",
    )
  }

  @Test
  fun `effectiveIosResourceId returns the node's own id when present`() {
    val n = node(detail = DriverNodeDetail.IosMaestro(resourceId = "own_id", clickable = true))
    assertEquals("own_id", effectiveIosResourceId(n))
  }

  @Test
  fun `effectiveIosResourceId hoists a lone descendant id onto a clickable control`() {
    val icon = node(detail = DriverNodeDetail.IosMaestro(resourceId = "ellipsis-horizontal"))
    val button = node(detail = DriverNodeDetail.IosMaestro(clickable = true), children = listOf(icon))
    assertEquals("ellipsis-horizontal", effectiveIosResourceId(button))
  }

  @Test
  fun `effectiveIosResourceId does not hoist onto a non-clickable wrapper`() {
    val icon = node(detail = DriverNodeDetail.IosMaestro(resourceId = "ellipsis-horizontal"))
    val wrapper = node(detail = DriverNodeDetail.IosMaestro(clickable = false), children = listOf(icon))
    assertEquals(null, effectiveIosResourceId(wrapper))
  }

  @Test
  fun `effectiveIosResourceId returns null when descendant ids are ambiguous`() {
    val a = node(detail = DriverNodeDetail.IosMaestro(resourceId = "id_a"))
    val b = node(detail = DriverNodeDetail.IosMaestro(resourceId = "id_b"))
    val button = node(detail = DriverNodeDetail.IosMaestro(clickable = true), children = listOf(a, b))
    assertEquals(null, effectiveIosResourceId(button))
  }

  @Test
  fun `effectiveIosResourceId does not cross a nested clickable boundary`() {
    // A nested clickable child owns its own subtree; its id must not hoist to the outer control.
    val nestedIcon = node(detail = DriverNodeDetail.IosMaestro(resourceId = "nested_id"))
    val nestedButton =
      node(detail = DriverNodeDetail.IosMaestro(clickable = true), children = listOf(nestedIcon))
    val outer =
      node(detail = DriverNodeDetail.IosMaestro(clickable = true), children = listOf(nestedButton))
    assertEquals(null, effectiveIosResourceId(outer))
  }

  @Test
  fun `table view becomes container with children indented`() {
    val cell1 =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITableViewCell",
            text = "Settings",
            clickable = true,
          ),
      )
    val cell2 =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITableViewCell",
            text = "Privacy",
            clickable = true,
          ),
      )
    val table =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITableView",
            scrollable = true,
          ),
        children = listOf(cell1, cell2),
      )
    val root = node(children = listOf(table))
    val result = IosCompactElementList.build(root)

    val lines = result.text.lines()
    assertTrue(
      lines.any { it.trim() == "UITableView:" },
      "Should have UITableView container header. Actual:\n${result.text}",
    )
    assertTrue(
      lines.any { it.contains("UITableViewCell \"Settings\"") },
      "Should have indented child element",
    )
    assertTrue(
      lines.any { it.contains("UITableViewCell \"Privacy\"") },
      "Should have second indented child",
    )
  }

  @Test
  fun `non-visible nodes are filtered out`() {
    val hidden =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Hidden",
            clickable = true,
            visible = false,
          ),
      )
    val visible =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Visible",
            clickable = true,
          ),
      )
    val root = node(children = listOf(hidden, visible))
    val result = IosCompactElementList.build(root)

    assertTrue(!result.text.contains("Hidden"))
    assertContains(result.text, "Visible")
  }

  @Test
  fun `ALL_ELEMENTS detail emits unlabeled non-interactive nodes`() {
    // A UIImageView with no clickable/focused/label is filtered by default but
    // should appear under ALL_ELEMENTS.
    val img =
      node(
        detail = DriverNodeDetail.IosMaestro(className = "UIImageView"),
      )
    val root = node(children = listOf(img))

    val defaultResult = IosCompactElementList.build(root)
    val allResult = IosCompactElementList.build(root, setOf(SnapshotDetail.ALL_ELEMENTS))

    assertTrue(
      !defaultResult.text.contains("UIImageView"),
      "Unlabeled UIImageView should be filtered out by default",
    )
    assertContains(allResult.text, "UIImageView")
  }

  @Test
  fun `structural wrapper nodes are transparent - children promoted`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Submit",
            clickable = true,
          ),
      )
    // UIView wrapper: not clickable, no text — should be transparent
    val wrapper =
      node(
        detail = DriverNodeDetail.IosMaestro(className = "UIView"),
        children = listOf(btn),
      )
    val root = node(children = listOf(wrapper))
    val result = IosCompactElementList.build(root)

    assertTrue(!result.text.contains("UIView"), "Wrapper should not appear")
    assertContains(result.text, "UIButton \"Submit\"")
  }

  @Test
  fun `child text nodes under clickable parent show as quoted strings`() {
    val subtitle =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UILabel",
            text = "Manage your devices",
          ),
      )
    val parent =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITableViewCell",
            text = "Connected Devices",
            clickable = true,
          ),
        children = listOf(subtitle),
      )
    val root = node(children = listOf(parent))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UITableViewCell \"Connected Devices\"")
    assertContains(result.text, "\"Manage your devices\"")
  }

  // -- State annotations --

  @Test
  fun `checked switch shows checked annotation`() {
    val toggle =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UISwitch",
            accessibilityText = "Dark Mode",
            checked = true,
          ),
      )
    val root = node(children = listOf(toggle))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UISwitch \"Dark Mode\" [checked]")
  }

  @Test
  fun `disabled button shows disabled annotation`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Submit",
            clickable = true,
            enabled = false,
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UIButton \"Submit\" [disabled]")
  }

  @Test
  fun `focused input shows focused annotation`() {
    val input =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITextField",
            text = "hello@example.com",
            clickable = true,
            focused = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UITextField \"hello@example.com\" [focused]")
  }

  @Test
  fun `password field shows password annotation`() {
    val pw =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITextField",
            hintText = "Password",
            clickable = true,
            password = true,
          ),
      )
    val root = node(children = listOf(pw))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UITextField \"Password\" [password]")
  }

  @Test
  fun `multiple annotations combine`() {
    val input =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITextField",
            hintText = "Password",
            clickable = true,
            focused = true,
            password = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "[focused]")
    assertContains(result.text, "[password]")
  }

  @Test
  fun `selected tab shows selected annotation`() {
    val tab =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITab",
            text = "Home",
            selected = true,
          ),
      )
    val root = node(children = listOf(tab))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UITab \"Home\" [selected]")
  }

  @Test
  fun `switch with checked state shows annotation`() {
    val toggle =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UISwitch",
            accessibilityText = "Dark Mode",
            checked = true,
          ),
      )
    val root = node(children = listOf(toggle))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UISwitch \"Dark Mode\" [checked]")
  }

  @Test
  fun `accessibility text used as fallback label`() {
    val icon =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIImageView",
            accessibilityText = "Back",
            clickable = true,
          ),
      )
    val root = node(children = listOf(icon))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UIImageView \"Back\"")
  }

  @Test
  fun `empty tree returns no elements found`() {
    val root = node(detail = DriverNodeDetail.IosMaestro(className = "UIView"))
    val result = IosCompactElementList.build(root)

    assertEquals("(no elements found)", result.text)
    assertTrue(result.elementNodeIds.isEmpty())
  }

  @Test
  fun `null-label parent recurses into null-label children to find deep text nodes`() {
    // Regression test: when both parent and child have null labels,
    // `childLabel != label` was `null != null` = false, silently dropping the subtree.
    val deepButton =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Sign In",
            clickable = true,
          ),
      )
    // Intermediate wrapper with no label (like UITransitionView in real iOS hierarchies)
    val wrapper =
      node(
        detail = DriverNodeDetail.IosMaestro(className = "UIView"),
        children = listOf(deepButton),
      )
    // Clickable parent with no label (like UIWindow in custom view hierarchy)
    val window =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIWindow",
            clickable = true,
          ),
        children = listOf(wrapper),
      )
    val root = node(children = listOf(window))
    val result = IosCompactElementList.build(root)

    assertTrue(
      result.text.contains("UIButton \"Sign In\""),
      "Deep button inside null-label chain should be found. Actual:\n${result.text}",
    )
  }

  @Test
  fun `navigation bar becomes container`() {
    val backBtn =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Back",
            clickable = true,
          ),
      )
    val title =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UILabel",
            text = "Settings",
          ),
      )
    val navBar =
      node(
        detail = DriverNodeDetail.IosMaestro(className = "UINavigationBar"),
        children = listOf(backBtn, title),
      )
    val root = node(children = listOf(navBar))
    val result = IosCompactElementList.build(root)

    val lines = result.text.lines()
    assertTrue(
      lines.any { it.trim() == "UINavigationBar:" },
      "Should have UINavigationBar container header. Actual:\n${result.text}",
    )
    assertTrue(lines.any { it.contains("UIButton \"Back\"") })
    assertTrue(lines.any { it.contains("UILabel \"Settings\"") })
  }

  // -- Stable identifier annotation (port of AXe [id=…] treatment) --

  @Test
  fun `resourceId surfaces as id= annotation`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Sign In",
            resourceId = "signin_button",
            clickable = true,
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UIButton \"Sign In\"")
    assertContains(result.text, "[id=signin_button]")
  }

  @Test
  fun `id annotation leads disabled annotation`() {
    val btn =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UIButton",
            text = "Delete",
            resourceId = "delete_button",
            clickable = true,
            enabled = false,
          ),
      )
    val root = node(children = listOf(btn))
    val result = IosCompactElementList.build(root)

    val line = result.text.lines().first { "Delete" in it }
    val idIdx = line.indexOf("[id=")
    val disabledIdx = line.indexOf("[disabled]")
    assertTrue(idIdx in 0..disabledIdx, "id before disabled in: $line")
  }

  // -- hintText + text composite (port of AXe label: value) --

  @Test
  fun `UITextField with hintText and text composes as 'hint, text'`() {
    val input =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITextField",
            text = "user@example.com",
            hintText = "Email",
            clickable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UITextField \"Email: user@example.com\"")
  }

  @Test
  fun `UITextField with only hintText still renders as hint`() {
    val input =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITextField",
            hintText = "Search",
            clickable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UITextField \"Search\"")
    assertTrue(!result.text.contains(":"), "no colon when only hintText present")
  }

  @Test
  fun `identical hint and text dedupe to one`() {
    val input =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITextField",
            text = "Search",
            hintText = "Search",
            clickable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "UITextField \"Search\"")
    assertTrue(!result.text.contains("Search: Search"), "identical hint/text should dedupe")
  }

  // -- accessibilityText + text composite (surfaces Maestro's AX label trapped in accessibilityText) --

  @Test
  fun `accessibilityText and text compose as 'label, value' for Contacts-style rows`() {
    // In Maestro's IOSDriver.mapViewHierarchy, AX label lands in attributes["accessibilityText"]
    // and AX value lands in attributes["text"]. A Contacts row for a mobile phone number has:
    //   accessibilityText = "mobile"      (from AXLabel)
    //   text              = "(408) 555-5270" (from AXValue via title-or-value fallback)
    val row =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "XCUIElementTypeStaticText",
            text = "(408) 555-5270",
            accessibilityText = "mobile",
          ),
      )
    val root = node(children = listOf(row))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "\"mobile: (408) 555-5270\"")
  }

  @Test
  fun `hintText takes precedence over accessibilityText as category`() {
    // When both are present, the placeholder (hintText) wins — it's a stronger semantic
    // signal for input fields than the AX label.
    val input =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "UITextField",
            text = "user@example.com",
            hintText = "Email",
            accessibilityText = "Email field",
            clickable = true,
          ),
      )
    val root = node(children = listOf(input))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "\"Email: user@example.com\"")
    assertTrue(
      !result.text.contains("Email field:"),
      "hintText should win over accessibilityText as the category",
    )
  }

  @Test
  fun `identical accessibilityText and text dedupe to one`() {
    val row =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "XCUIElementTypeButton",
            text = "OK",
            accessibilityText = "OK",
            clickable = true,
          ),
      )
    val root = node(children = listOf(row))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "\"OK\"")
    assertTrue(!result.text.contains("OK: OK"), "identical label/value should dedupe")
  }

  @Test
  fun `accessibilityText-only fallback still renders just the ax label`() {
    // An icon button with no text body — accessibilityText is the only label we have.
    val icon =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            className = "XCUIElementTypeButton",
            accessibilityText = "Close",
            clickable = true,
          ),
      )
    val root = node(children = listOf(icon))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "\"Close\"")
    assertTrue(!result.text.contains(":"), "no colon when only ax label present")
  }

  // -- In-text link children (link ranges inside a larger text element) --

  @Test
  fun `in-text link child gets its own element ref instead of a quoted string`() {
    // A disclosure sentence whose tappable link range is exposed as its own child
    // accessibility element (own bounds, no clickable flag from the driver). The child's
    // label is a proper substring of the parent's, so it must be promoted to a ref —
    // a quoted string would hide the only directly-tappable handle for the link.
    val link =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = "conversion fee"),
        bounds = TrailblazeNode.Bounds(239, 728, 341, 748),
      )
    val sentence =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            accessibilityText = "Exchange rate may include a conversion fee.",
          ),
        bounds = TrailblazeNode.Bounds(16, 727, 386, 747),
        children = listOf(link),
      )
    val root = node(children = listOf(sentence))
    val result = IosCompactElementList.build(root)

    assertContains(result.text, "\"Exchange rate may include a conversion fee.\"")
    assertContains(result.text, "\"conversion fee\"")
    assertFalse(
      result.text.lines().any { it.trim() == "\"conversion fee\"" },
      "link child must be an element line with a ref, not a bare quoted string",
    )
    assertTrue(
      result.elementNodeIds.contains(link.nodeId),
      "link child must be addressable (present in elementNodeIds)",
    )
  }

  @Test
  fun `multiple in-text links are each promoted, including one whose bounds match the parent`() {
    // A legal footer with three link ranges. One link wraps onto a second line, so the OS
    // reports its frame as the parent's full union rect — bounds equality must not block
    // promotion.
    val footerLabel = "Sample's Privacy Notice, Terms of Service and Open Source Software"
    val privacy =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = "Privacy Notice"),
        bounds = TrailblazeNode.Bounds(106, 689, 194, 705),
      )
    val terms =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = "Terms of Service"),
        bounds = TrailblazeNode.Bounds(207, 689, 308, 705),
      )
    val oss =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = "Open Source Software"),
        bounds = TrailblazeNode.Bounds(34, 689, 368, 718),
      )
    val footer =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = footerLabel),
        bounds = TrailblazeNode.Bounds(34, 689, 368, 718),
        children = listOf(privacy, terms, oss),
      )
    val root = node(children = listOf(footer))
    val result = IosCompactElementList.build(root)

    for (child in listOf(privacy, terms, oss)) {
      assertTrue(
        result.elementNodeIds.contains(child.nodeId),
        "expected link child ${child.nodeId} to be addressable",
      )
    }
  }

  @Test
  fun `child text that is not a substring of the parent label stays a quoted string`() {
    val subtitle =
      node(detail = DriverNodeDetail.IosMaestro(text = "Manage your devices"))
    val row =
      node(
        detail = DriverNodeDetail.IosMaestro(text = "Connected Devices"),
        children = listOf(subtitle),
      )
    val root = node(children = listOf(row))
    val result = IosCompactElementList.build(root)

    assertTrue(
      result.text.lines().any { it.trim() == "\"Manage your devices\"" },
      "non-link child text must stay a bare quoted string",
    )
    assertFalse(result.elementNodeIds.contains(subtitle.nodeId))
  }

  @Test
  fun `mirrored-label wrapper child is not promoted as an in-text link`() {
    // The common wrapper shape: a container whose single child repeats the container's
    // whole label. The child-label dedupe skips mirrored labels before the in-text-link
    // gate is evaluated, so the child must stay demoted — promoting it would double every
    // plain text element.
    val mirror =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = "Exchange rate applies"),
        bounds = TrailblazeNode.Bounds(16, 727, 386, 747),
      )
    val wrapper =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = "Exchange rate applies"),
        bounds = TrailblazeNode.Bounds(16, 727, 386, 747),
        children = listOf(mirror),
      )
    val root = node(children = listOf(wrapper))
    val result = IosCompactElementList.build(root)

    assertFalse(
      result.elementNodeIds.contains(mirror.nodeId),
      "a child whose label equals the parent's must not be promoted",
    )
  }

  @Test
  fun `labeled scroll indicators are still filtered as system UI`() {
    // Real UIScrollView indicators carry UIKit's own accessibility label and a percent
    // value (serialized waypoint captures show text "0%" + "Vertical scroll bar, 3 pages"),
    // so the labeled-leaf exemption from the aspect-ratio heuristic must not resurface
    // them — they're matched by the stable platform label instead.
    val vertical =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            text = "0%",
            accessibilityText = "Vertical scroll bar, 3 pages",
          ),
        bounds = TrailblazeNode.Bounds(369, 91, 399, 788),
      )
    val horizontal =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            text = "0%",
            accessibilityText = "Horizontal scroll bar, 1 page",
          ),
        bounds = TrailblazeNode.Bounds(20, 750, 398, 780),
      )
    val root = node(children = listOf(vertical, horizontal))
    val result = IosCompactElementList.build(root)

    assertFalse(result.elementNodeIds.contains(vertical.nodeId), "vertical scrollbar must stay filtered")
    assertFalse(result.elementNodeIds.contains(horizontal.nodeId), "horizontal scrollbar must stay filtered")
    assertFalse(result.text.contains("scroll bar"), "scrollbar labels must not appear in the element list")
  }

  @Test
  fun `localized scroll indicators are filtered by their percent value`() {
    // UIKit localizes "Vertical scroll bar, N pages", so the English prefix fast path
    // misses on non-English runtimes — but the indicator's value stays a bare percent,
    // which pairs with the extreme aspect ratio to catch it in any locale. A percent
    // label on a normal-ratio element is real content and must keep its ref.
    val vertical =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            text = "0%",
            accessibilityText = "Vertikale Bildlaufleiste, 3 Seiten",
          ),
        bounds = TrailblazeNode.Bounds(369, 91, 399, 788),
      )
    val percentText =
      node(
        detail = DriverNodeDetail.IosMaestro(text = "50%"),
        bounds = TrailblazeNode.Bounds(20, 300, 120, 340),
      )
    val root = node(children = listOf(vertical, percentText))
    val result = IosCompactElementList.build(root)

    assertFalse(result.elementNodeIds.contains(vertical.nodeId), "localized scrollbar must be filtered")
    assertTrue(result.elementNodeIds.contains(percentText.nodeId), "a normal-ratio percent label is real content")
  }

  @Test
  fun `blank text with a real accessibilityText does not look label-less to the scroll filter`() {
    // Captures serialize `text` as "" on nodes labeled only via accessibilityText. A plain
    // `text ?: accessibilityText` fallback takes the empty string, so a wide one-line link
    // (scrollbar-shaped, ratio > 10) would be dropped as a label-less scroll indicator
    // before the link-promotion path ever sees it.
    val link =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            text = "",
            accessibilityText = "Terms of Service",
          ),
        bounds = TrailblazeNode.Bounds(16, 727, 350, 756),
      )
    val root = node(children = listOf(link))
    val result = IosCompactElementList.build(root)

    assertTrue(
      result.elementNodeIds.contains(link.nodeId),
      "a blank-text link labeled via accessibilityText must keep its ref",
    )
  }

  @Test
  fun `long link child whose truncated label collides with the parent's is still promoted`() {
    // Two raw labels longer than the 80-char display cap that share their first 79 chars
    // truncate to the same display string. The dedupe must compare RAW labels, or this
    // proper-substring link never reaches the in-text-link gate and loses its ref.
    val linkText =
      "By continuing, you agree to the Terms of Service and acknowledge the Privacy Policy"
    check(linkText.length > 80) { "fixture must exceed the display cap to exercise the collision" }
    val link =
      node(
        detail = DriverNodeDetail.IosMaestro(accessibilityText = linkText),
        bounds = TrailblazeNode.Bounds(16, 727, 386, 790),
      )
    val paragraph =
      node(
        detail =
          DriverNodeDetail.IosMaestro(
            accessibilityText = "$linkText, including its arbitration clause.",
          ),
        bounds = TrailblazeNode.Bounds(16, 727, 386, 812),
        children = listOf(link),
      )
    val root = node(children = listOf(paragraph))
    val result = IosCompactElementList.build(root)

    assertTrue(
      result.elementNodeIds.contains(link.nodeId),
      "a truncation-colliding proper-substring link child must be promoted",
    )
  }

  @Test
  fun `composite row value child is deduped even when the composed label exceeds the display cap`() {
    // A category row composes its label as "category: value". When the composite exceeds
    // the 80-char display cap, truncation cuts the ": value" suffix, so a truncated-suffix
    // dedupe stops recognizing the value-mirror child — which is a proper substring of the
    // raw composite and would leak through the in-text-link gate as a noisy ref.
    val value = "Send, spend, and transfer your balance anytime without fees or waiting"
    val composite = "Balance details: $value"
    check(composite.length > 80) { "composed fixture must exceed the display cap" }
    val valueMirror =
      node(
        detail = DriverNodeDetail.IosMaestro(text = value),
        bounds = TrailblazeNode.Bounds(16, 400, 386, 440),
      )
    val row =
      node(
        detail = DriverNodeDetail.IosMaestro(text = value, accessibilityText = "Balance details"),
        bounds = TrailblazeNode.Bounds(16, 380, 386, 440),
        children = listOf(valueMirror),
      )
    val root = node(children = listOf(row))
    val result = IosCompactElementList.build(root)

    assertFalse(
      result.elementNodeIds.contains(valueMirror.nodeId),
      "a composite row's value-mirror child is static text, not an in-text link",
    )
  }

  @Test
  fun `composite row category child keeps its quoted string and is not promoted as a link`() {
    // A "Search: Kate" row also carries its CATEGORY as a static child (the non-clickable
    // "Search" magnifying-glass element). "Search" is a proper substring of the raw
    // composite, so without the prefix exclusion the in-text-link gate promotes it to a
    // noisy addressable ref.
    val categoryMirror =
      node(
        detail = DriverNodeDetail.IosMaestro(text = "Search"),
        bounds = TrailblazeNode.Bounds(16, 100, 56, 140),
      )
    val row =
      node(
        detail = DriverNodeDetail.IosMaestro(text = "Kate", accessibilityText = "Search"),
        bounds = TrailblazeNode.Bounds(16, 100, 386, 140),
        children = listOf(categoryMirror),
      )
    val root = node(children = listOf(row))
    val result = IosCompactElementList.build(root)

    assertFalse(
      result.elementNodeIds.contains(categoryMirror.nodeId),
      "a composite row's category-mirror child is static text, not an in-text link",
    )
    assertContains(result.text, "\"Search\"")
  }

}
