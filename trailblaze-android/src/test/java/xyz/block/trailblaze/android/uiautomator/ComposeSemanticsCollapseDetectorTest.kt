package xyz.block.trailblaze.android.uiautomator

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pure-JVM cover for [ComposeSemanticsCollapseDetector], the gate that decides whether a
 * UiAutomator-captured hierarchy is exhibiting the intermittent "Compose main screen collapses
 * to one node" bug seen on the instrumentation/UiAutomator capture path for Compose-heavy apps.
 *
 * The synthetic trees here mirror the real captured shape: a main-content ComposeView that
 * collapsed to a single childless `View` next to a fully-rendered bottom-navigation sibling.
 */
class ComposeSemanticsCollapseDetectorTest {

  private val deviceWidth = 1920
  private val deviceHeight = 1080
  private val composeViewClass = "androidx.compose.ui.platform.ComposeView"

  private fun node(
    className: String? = null,
    resourceId: String? = null,
    text: String? = null,
    accessibilityText: String? = null,
    x1: Int = 0,
    y1: Int = 0,
    x2: Int = 0,
    y2: Int = 0,
    children: List<ViewHierarchyTreeNode> = emptyList(),
  ) = ViewHierarchyTreeNode(
    className = className,
    resourceId = resourceId,
    text = text,
    accessibilityText = accessibilityText,
    x1 = x1,
    y1 = y1,
    x2 = x2,
    y2 = y2,
    children = children,
  )

  /** A full-screen ComposeView with [n] text-free leaf-View descendants (no text anywhere). */
  private fun composeViewWithDescendants(n: Int) = node(
    className = composeViewClass,
    resourceId = "com.example.dev:id/navigation_body",
    x1 = 0, y1 = 54, x2 = 1920, y2 = 995,
    children = (1..n).map { node(className = "android.view.View", x1 = 0, y1 = 54, x2 = 1920, y2 = 995) },
  )

  /** A full-screen ComposeView main pane that collapsed to a single childless View. */
  private fun collapsedBody() = node(
    className = composeViewClass,
    resourceId = "com.example.dev:id/navigation_body",
    x1 = 0, y1 = 54, x2 = 1920, y2 = 995,
    children = listOf(node(className = "android.view.View", x1 = 0, y1 = 54, x2 = 1920, y2 = 995)),
  )

  /** A healthy bottom nav ComposeView that exported its full Compose tree. */
  private fun healthyNavBar() = node(
    className = composeViewClass,
    resourceId = "com.example.dev:id/navigation_bar",
    x1 = 0, y1 = 995, x2 = 1920, y2 = 1080,
    children = listOf(
      node(
        className = "android.view.View",
        x1 = 0, y1 = 995, x2 = 1920, y2 = 1080,
        children = listOf(
          node(className = "android.widget.TextView", text = "Checkout", x1 = 0, y1 = 995, x2 = 480, y2 = 1080),
          node(className = "android.widget.TextView", text = "Transactions", x1 = 480, y1 = 995, x2 = 960, y2 = 1080),
          node(className = "android.widget.TextView", text = "Notifications", x1 = 960, y1 = 995, x2 = 1440, y2 = 1080),
          node(className = "android.widget.TextView", text = "More", x1 = 1440, y1 = 995, x2 = 1920, y2 = 1080),
        ),
      ),
    ),
  )

  @Test
  fun `detects the collapsed main-content ComposeView next to a healthy nav bar`() {
    val root = node(
      x1 = 0, y1 = 0, x2 = 1920, y2 = 1080,
      children = listOf(collapsedBody(), healthyNavBar()),
    )
    val detected = ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(root, deviceWidth, deviceHeight)
    assertNotNull(detected, "should flag the collapsed navigation_body ComposeView")
    assertEquals("com.example.dev:id/navigation_body", detected.resourceId)
  }

  @Test
  fun `healthy main-content ComposeView is not flagged`() {
    val healthyBody = node(
      className = composeViewClass,
      resourceId = "com.example.dev:id/navigation_body",
      x1 = 0, y1 = 54, x2 = 1920, y2 = 995,
      children = listOf(
        node(
          className = "android.view.View",
          x1 = 0, y1 = 54, x2 = 1920, y2 = 995,
          children = listOf(
            node(className = "android.widget.TextView", text = "Orange Juice", x1 = 0, y1 = 100, x2 = 400, y2 = 160),
            node(className = "android.widget.TextView", text = "Coffee", x1 = 0, y1 = 160, x2 = 400, y2 = 220),
            node(className = "android.widget.EditText", text = "Search all items", x1 = 0, y1 = 54, x2 = 1920, y2 = 100),
          ),
        ),
      ),
    )
    val root = node(x1 = 0, y1 = 0, x2 = 1920, y2 = 1080, children = listOf(healthyBody, healthyNavBar()))
    assertNull(ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(root, deviceWidth, deviceHeight))
  }

  @Test
  fun `small empty ComposeView below the area threshold is ignored`() {
    // A decorative/empty Compose surface (e.g. a chip) far below the content-pane size threshold.
    val smallEmpty = node(
      className = composeViewClass,
      resourceId = "com.example.dev:id/badge",
      x1 = 0, y1 = 0, x2 = 120, y2 = 60,
      children = listOf(node(className = "android.view.View", x1 = 0, y1 = 0, x2 = 120, y2 = 60)),
    )
    val root = node(x1 = 0, y1 = 0, x2 = 1920, y2 = 1080, children = listOf(smallEmpty))
    assertNull(ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(root, deviceWidth, deviceHeight))
  }

  @Test
  fun `large empty non-ComposeView container is ignored`() {
    // Only ComposeView host wrappers exhibit the lazy-semantics collapse; a generic empty
    // container is a different (and legitimate) shape we must not touch.
    val largeEmptyFrame = node(
      className = "android.widget.FrameLayout",
      x1 = 0, y1 = 54, x2 = 1920, y2 = 995,
      children = listOf(node(className = "android.view.View", x1 = 0, y1 = 54, x2 = 1920, y2 = 995)),
    )
    val root = node(x1 = 0, y1 = 0, x2 = 1920, y2 = 1080, children = listOf(largeEmptyFrame))
    assertNull(ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(root, deviceWidth, deviceHeight))
  }

  @Test
  fun `non-positive device dimensions return null without throwing`() {
    val root = node(children = listOf(collapsedBody()))
    assertNull(ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(root, 0, 0))
    assertNull(ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(root, -1, deviceHeight))
  }

  @Test
  fun `descendant-count boundary is inclusive at the max`() {
    // Exactly MAX_COLLAPSED_DESCENDANTS (4) text-free descendants → still collapsed.
    val atBoundary = node(x1 = 0, y1 = 0, x2 = 1920, y2 = 1080, children = listOf(composeViewWithDescendants(4)))
    assertNotNull(
      ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(atBoundary, deviceWidth, deviceHeight),
      "a large text-free ComposeView with exactly 4 descendants should be flagged",
    )
    // One past the cap (5) → no longer treated as collapsed (a real pane has many descendants).
    val pastBoundary = node(x1 = 0, y1 = 0, x2 = 1920, y2 = 1080, children = listOf(composeViewWithDescendants(5)))
    assertNull(
      ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(pastBoundary, deviceWidth, deviceHeight),
      "5 descendants is past the cap and should not be flagged",
    )
  }

  @Test
  fun `accessibilityText counts as meaningful text and is not flagged`() {
    // content-desc lands in accessibilityText; a pane whose only descendant carries an
    // accessibility label (and no visible text) must NOT be treated as collapsed.
    val labelledBody = node(
      className = composeViewClass,
      resourceId = "com.example.dev:id/navigation_body",
      x1 = 0, y1 = 54, x2 = 1920, y2 = 995,
      children = listOf(node(className = "android.view.View", accessibilityText = "Accounts", x1 = 0, y1 = 54, x2 = 1920, y2 = 995)),
    )
    val root = node(x1 = 0, y1 = 0, x2 = 1920, y2 = 1080, children = listOf(labelledBody))
    assertNull(ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(root, deviceWidth, deviceHeight))
  }

  @Test
  fun `with two collapsed ComposeViews the first in DFS order is returned`() {
    val topHalf = node(
      className = composeViewClass,
      resourceId = "com.example.dev:id/pane_a",
      x1 = 0, y1 = 0, x2 = 1920, y2 = 540,
      children = listOf(node(className = "android.view.View", x1 = 0, y1 = 0, x2 = 1920, y2 = 540)),
    )
    val bottomHalf = node(
      className = composeViewClass,
      resourceId = "com.example.dev:id/pane_b",
      x1 = 0, y1 = 540, x2 = 1920, y2 = 1080,
      children = listOf(node(className = "android.view.View", x1 = 0, y1 = 540, x2 = 1920, y2 = 1080)),
    )
    val root = node(x1 = 0, y1 = 0, x2 = 1920, y2 = 1080, children = listOf(topHalf, bottomHalf))
    val detected = ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(root, deviceWidth, deviceHeight)
    assertNotNull(detected, "should flag a collapsed pane when there are two")
    assertEquals("com.example.dev:id/pane_a", detected.resourceId, "DFS returns the first collapsed ComposeView")
  }
}
