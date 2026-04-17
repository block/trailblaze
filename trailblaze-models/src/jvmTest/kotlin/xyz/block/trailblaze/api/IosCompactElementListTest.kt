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
}
