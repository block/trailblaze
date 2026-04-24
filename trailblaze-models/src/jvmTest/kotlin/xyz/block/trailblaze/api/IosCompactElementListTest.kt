package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
}
