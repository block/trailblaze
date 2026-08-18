package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TrailblazeNode.hitTest] and [TrailblazeNodeSelectorGenerator.resolveFromTap],
 * covering Z-index ordering (smallest-area wins), nested overlaps, and full round-trip
 * tap → selector → resolve → hit-test verification.
 */
class TrailblazeNodeHitTestAndTapResolutionTest {

  private var nextId = 1L

  private fun node(
    detail: DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode {
    val id = nextId++
    return TrailblazeNode(nodeId = id, children = children, bounds = bounds, driverDetail = detail)
  }

  // ======================================================================
  // hitTest: basic cases
  // ======================================================================

  @Test
  fun `hitTest returns null for point outside all bounds`() {
    nextId = 1L
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(
        node(bounds = TrailblazeNode.Bounds(10, 10, 50, 50)),
      ),
    )
    assertNull(root.hitTest(200, 200))
  }

  @Test
  fun `hitTest returns single node when point is inside`() {
    nextId = 1L
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Button"),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(child),
    )
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(child.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest returns root when point is inside root but outside children`() {
    nextId = 1L
    val child = node(
      bounds = TrailblazeNode.Bounds(10, 10, 50, 40),
    )
    val root = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Root"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )
    // Point at (80, 80) is inside root but outside child
    val hit = root.hitTest(80, 80)
    assertNotNull(hit)
    assertEquals(root.nodeId, hit.nodeId)
  }

  // ======================================================================
  // hitTest: Z-index ordering (smallest area wins)
  // ======================================================================

  @Test
  fun `hitTest picks smallest overlapping node - child over parent`() {
    nextId = 1L
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Child Button"),
      bounds = TrailblazeNode.Bounds(20, 20, 80, 40),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 500, 500),
      children = listOf(parent),
    )

    // Tap at child's center (50, 30) - should hit child, not parent or root
    val hit = root.hitTest(50, 30)
    assertNotNull(hit)
    assertEquals(child.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest picks grandchild over child over parent`() {
    nextId = 1L
    val grandchild = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Icon"),
      bounds = TrailblazeNode.Bounds(30, 30, 70, 60),
    )
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.Button"),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 80),
      children = listOf(grandchild),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )

    // Tap at grandchild center (50, 45)
    val hit = parent.hitTest(50, 45)
    assertNotNull(hit)
    assertEquals(grandchild.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest picks smaller sibling when overlapping`() {
    nextId = 1L
    // Overlapping siblings: a large background and a small floating button
    val background = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
    )
    val floatingButton = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "FAB"),
      bounds = TrailblazeNode.Bounds(320, 320, 380, 380),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(background, floatingButton),
    )

    // Tap at floating button center (350, 350) — both contain the point,
    // but floating button is smaller
    val hit = root.hitTest(350, 350)
    assertNotNull(hit)
    assertEquals(floatingButton.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest handles dialog overlay on top of content`() {
    nextId = 1L
    // Content behind dialog
    val contentButton = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Background Button"),
      bounds = TrailblazeNode.Bounds(50, 200, 350, 260),
    )
    val contentArea = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.LinearLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
      children = listOf(contentButton),
    )

    // Dialog overlay (smaller area, but contains the tap point)
    val dialogButton = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "OK"),
      bounds = TrailblazeNode.Bounds(140, 220, 260, 260),
    )
    val dialog = node(
      detail = DriverNodeDetail.AndroidAccessibility(paneTitle = "Confirm"),
      bounds = TrailblazeNode.Bounds(100, 200, 300, 400),
      children = listOf(dialogButton),
    )

    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
      children = listOf(contentArea, dialog),
    )

    // Tap at (200, 240) — both contentButton and dialogButton contain this point.
    // dialogButton is smaller, so it should win
    val hit = root.hitTest(200, 240)
    assertNotNull(hit)
    assertEquals(dialogButton.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest boundary - point on exact edge is within bounds`() {
    nextId = 1L
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Edge"),
      bounds = TrailblazeNode.Bounds(10, 10, 50, 40),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )
    // Point on exact left edge
    val hit = root.hitTest(10, 25)
    assertNotNull(hit)
    assertEquals(child.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest node without bounds is ignored`() {
    nextId = 1L
    val noBoundsChild = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Ghost"),
      bounds = null,
    )
    val visibleChild = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Visible"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(noBoundsChild, visibleChild),
    )
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(visibleChild.nodeId, hit.nodeId)
  }

  // ======================================================================
  // hitTest: propertyless node preference
  // ======================================================================

  @Test
  fun `hitTest prefers identifiable node over smaller propertyless container`() {
    nextId = 1L
    // Smaller propertyless container — 60x30 = 1800 area
    val empty = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(20, 10, 80, 40),
    )
    // Slightly larger node with properties — 80x40 = 3200 area
    val labeled = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Contacts",
        resourceId = "com.example:id/contacts",
      ),
      bounds = TrailblazeNode.Bounds(10, 5, 90, 45),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(empty, labeled),
    )

    // Both nodes contain (50, 25). The labeled node should win despite being larger.
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(labeled.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest still picks smallest when all nodes have properties`() {
    nextId = 1L
    val small = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Small"),
      bounds = TrailblazeNode.Bounds(20, 10, 80, 40),
    )
    val large = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Large"),
      bounds = TrailblazeNode.Bounds(10, 5, 90, 45),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(small, large),
    )

    // When both have properties, smallest area wins as before
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(small.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest falls back to smallest when no nodes have properties`() {
    nextId = 1L
    val small = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(20, 10, 80, 40),
    )
    val large = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(10, 5, 90, 45),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(small, large),
    )

    // When neither has properties, smallest area wins as fallback
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(small.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest equal-bounds tie on one ancestor chain goes to the descendant`() {
    nextId = 1L
    // An in-text-link child captured with bounds identical to its paragraph parent: real
    // OS hit-testing returns the deepest element, so the recorded selector must describe
    // the link, not the paragraph that merely contains it.
    val link = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "currency spread"),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
    )
    val paragraph = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "The price includes a currency spread charged by the exchange",
      ),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
      children = listOf(link),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(paragraph),
    )

    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(link.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest equal-bounds tie between siblings still goes to the first in document order`() {
    nextId = 1L
    val first = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "First"),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
    )
    val second = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Second"),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(first, second),
    )

    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(first.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest equal-bounds tie against an interactive ancestor stays on the ancestor`() {
    nextId = 1L
    // A clickable wrapper captured with a non-interactive mirrored-label child at identical
    // bounds: the wrapper is the control that owns the tap (ACTION_CLICK, stable id), and
    // the climb cannot recover it — the mirrored label makes the wrapper "enclose multiple
    // labels" — so the tie itself must keep the wrapper.
    val mirror = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Exchange rate applies"),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
    )
    val wrapper = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Exchange rate applies",
        isClickable = true,
      ),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
      children = listOf(mirror),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(wrapper),
    )

    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(wrapper.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest equal-bounds tie with a label-less descendant stays on the ancestor`() {
    nextId = 1L
    // A bare structural descendant (no text of its own) captured with bounds identical to
    // its id-bearing container: letting it win would only swap the ancestor's stable
    // resource-id selector for a weaker class-name one.
    val list = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.support.v7.widget.RecyclerView",
      ),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
    )
    val container = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
        resourceId = "com.example:id/fragment_container",
      ),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
      children = listOf(list),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(container),
    )

    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(container.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest equal-bounds tie counts a blank-text descendant labeled via accessibilityText`() {
    nextId = 1L
    // Captures serialize `text` as "" on nodes labeled only via accessibilityText. The
    // labeled-descendant requirement must see through the blank, or the recorded selector
    // regresses to describing the paragraph instead of the link.
    val link = TrailblazeNode(
      nodeId = 2L,
      children = emptyList(),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
      driverDetail = DriverNodeDetail.IosMaestro(text = "", accessibilityText = "currency spread"),
    )
    val paragraph = TrailblazeNode(
      nodeId = 1L,
      children = listOf(link),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
      driverDetail = DriverNodeDetail.IosMaestro(
        accessibilityText = "The price includes a currency spread charged by the exchange",
      ),
    )

    val hit = paragraph.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(link.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest climbs past an empty editable field's hint to the field itself`() {
    nextId = 1L
    // An empty Search EditText carries `text = ""` plus a hint, and wraps its static
    // TextView label. A hint is a prompt for absent content, not a label the field
    // carries — if it counted, the field would "enclose multiple labels", the climb
    // would stop, and the tap would strand on the static child instead of the field.
    val staticLabel = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.TextView",
        text = "Search",
      ),
      bounds = TrailblazeNode.Bounds(30, 15, 80, 35),
    )
    val searchField = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.EditText",
        text = "",
        hintText = "Search",
        isClickable = true,
      ),
      bounds = TrailblazeNode.Bounds(10, 10, 200, 40),
      children = listOf(staticLabel),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 100),
      children = listOf(searchField),
    )

    val hit = root.hitTest(55, 25)
    assertNotNull(hit)
    assertEquals(searchField.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest iOS propertyless container scenario`() {
    nextId = 1L
    // Simulates the iOS scenario: small className-only container nested inside an identifiable element
    val emptyContainer = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(25, 15, 75, 35),
      driverDetail = DriverNodeDetail.IosMaestro(className = "UIView"), // className only, no text/resourceId
      children = emptyList(),
    )
    val contactsButton = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(10, 5, 90, 45),
      driverDetail = DriverNodeDetail.IosMaestro(
        resourceId = "Contacts",
        accessibilityText = "Contacts",
      ),
      children = listOf(emptyContainer),
    )
    val root = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      driverDetail = DriverNodeDetail.IosMaestro(),
      children = listOf(contactsButton),
    )

    // Tap at center of the empty container — should still resolve to the Contacts button
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(contactsButton.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest prefers interactive NativeButton over non-interactive icon child with resourceId`() {
    nextId = 1L
    // Reproduces the iOS dismiss-button failure: a UIImageView icon (resourceId="x", 24×24px)
    // inside a NativeButton was beating the NativeButton on the area tiebreaker, causing
    // hitTest to return the non-interactive icon. The generated selector idRegex:"x" matched
    // the icon but never triggered a tap action — 49 retries, modal unchanged.
    //
    // With the isInteractive tiebreaker, NativeButton (clickable=true) wins over the icon
    // (non-interactive) even though the icon is smaller.
    val iconChild = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(346, 603, 370, 627), // 24×24 px
      driverDetail = DriverNodeDetail.IosMaestro(className = "UIImageView", resourceId = "x"),
      children = emptyList(),
    )
    val uiView = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(346, 602, 370, 628),
      driverDetail = DriverNodeDetail.IosMaestro(className = "UIView"),
      children = listOf(iconChild),
    )
    val nativeButton = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(346, 602, 370, 628), // 24×26 px — larger but interactive
      driverDetail = DriverNodeDetail.IosMaestro(className = "NativeButton", clickable = true),
      children = listOf(uiView),
    )
    val accessibilityView = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(346, 602, 370, 628),
      driverDetail = DriverNodeDetail.IosMaestro(className = "AccessibilityView", accessibilityText = "Dismiss"),
      children = listOf(nativeButton),
    )
    val root = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(0, 0, 400, 874),
      driverDetail = DriverNodeDetail.IosMaestro(),
      children = listOf(accessibilityView),
    )

    val hit = root.hitTest(358, 615)
    assertNotNull(hit)
    // Must be NativeButton (interactive), not the UIImageView icon child (non-interactive)
    assertEquals(nativeButton.nodeId, hit!!.nodeId)
  }

  // ======================================================================
  // hitTest: an interactive container must not swallow the content inside it
  // ======================================================================

  /**
   * A scrollable pager wrapping a list of rows is interactive (focusable/scrollable) and
   * contains every point on the screen, so ranking by interactivity alone handed it every
   * tap: the inspector reported "this tap lands on the pager, not this element" for every
   * row, and a recorded tap resolved its selector from the pager.
   *
   * The row's own label is what a tap there is on.
   */
  @Test
  fun `hitTest returns the row label rather than the scrollable pager wrapping the list`() {
    nextId = 1L
    val rows = (0..2).map { i ->
      node(
        detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup"),
        bounds = TrailblazeNode.Bounds(0, 200 + i * 100, 1080, 300 + i * 100),
        children = listOf(
          node(
            detail = DriverNodeDetail.AndroidAccessibility(
              className = "android.widget.TextView",
              text = listOf("Items", "Services", "Discounts")[i],
              resourceId = "com.example:id/list_row_title",
            ),
            bounds = TrailblazeNode.Bounds(180, 230 + i * 100, 940, 270 + i * 100),
          ),
        ),
      )
    }
    val pager = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "androidx.viewpager.widget.ViewPager",
        resourceId = "com.example:id/view_pager",
        isFocusable = true,
        isScrollable = true,
      ),
      bounds = TrailblazeNode.Bounds(0, 200, 1080, 900),
      children = rows,
    )
    val root = node(bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920), children = listOf(pager))

    val hit = root.hitTest(560, 350) // center of the "Services" label
    assertNotNull(hit)
    assertEquals(rows[1].children.single().nodeId, hit.nodeId)
  }

  /** The same list, with rows that carry the click handler: the row is the tap target. */
  @Test
  fun `hitTest returns the clickable row when the row itself is the control`() {
    nextId = 1L
    val label = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView", text = "Services"),
      bounds = TrailblazeNode.Bounds(180, 230, 940, 270),
    )
    val row = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup", isClickable = true),
      bounds = TrailblazeNode.Bounds(0, 200, 1080, 300),
      children = listOf(label),
    )
    val pager = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "androidx.viewpager.widget.ViewPager",
        resourceId = "com.example:id/view_pager",
        isFocusable = true,
        isScrollable = true,
      ),
      bounds = TrailblazeNode.Bounds(0, 200, 1080, 900),
      children = listOf(row),
    )
    val root = node(bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920), children = listOf(pager))

    val hit = root.hitTest(560, 250)
    assertNotNull(hit)
    assertEquals(row.nodeId, hit.nodeId)
  }

  /**
   * The multiple-label bound is the single condition separating a control from a container, so
   * pin both sides of it: one label climbs (the test above), two stops here. A clickable row
   * carrying a title *and* a subtitle reads as a container, and the tap resolves to the leaf
   * under the point.
   *
   * The direction is deliberate and safe — a gesture at the leaf's center still lands inside
   * the row — and measured better across the committed captures than every looser bound tried
   * (counting distinct label values, allowing two labels, or requiring the ancestor to have no
   * interactive descendant). The cost is that the leaf's text may not be unique on the screen,
   * so the generator can fall through to a weaker selector than the row would have produced.
   */
  @Test
  fun `hitTest returns the leaf inside a clickable row that carries two labels of its own`() {
    nextId = 1L
    val title = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView", text = "Services"),
      bounds = TrailblazeNode.Bounds(180, 230, 940, 270),
    )
    val subtitle = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView", text = "3 available"),
      bounds = TrailblazeNode.Bounds(180, 280, 940, 310),
    )
    val row = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup", isClickable = true),
      bounds = TrailblazeNode.Bounds(0, 200, 1080, 320),
      children = listOf(title, subtitle),
    )
    val root = node(bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920), children = listOf(row))

    val hit = root.hitTest(560, 250)
    assertNotNull(hit)
    assertEquals(title.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest skips an interactive ancestor whose bounds exclude the point`() {
    nextId = 1L
    // Bounds do not nest in every capture — 77% of parent/child pairs in the committed web
    // (ARIA) captures are non-nested — so the climb has to re-check containment or it can
    // return a control on the other side of the screen.
    val label = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView", text = "Close chat"),
      bounds = TrailblazeNode.Bounds(100, 1380, 400, 1420),
    )
    val detachedControl = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup", isClickable = true),
      bounds = TrailblazeNode.Bounds(0, 0, 300, 200),
      children = listOf(label),
    )
    val root = node(bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920), children = listOf(detachedControl))

    val hit = root.hitTest(200, 1400)
    assertNotNull(hit)
    assertEquals(label.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest prefers a real element over a zero-area node at the same point`() {
    nextId = 1L
    // Bounds.containsPoint is inclusive on both ends, so a left == right node contains its own
    // center and its area of 0 would otherwise beat every real element there.
    val text = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView", text = "Subtotal"),
      bounds = TrailblazeNode.Bounds(100, 260, 980, 340),
    )
    val spacer = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.view.View",
        resourceId = "com.example:id/spacer",
      ),
      bounds = TrailblazeNode.Bounds(540, 300, 540, 300),
    )
    val root = node(bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920), children = listOf(text, spacer))

    val hit = root.hitTest(540, 300)
    assertNotNull(hit)
    assertEquals(text.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest returns the innermost control when interactive nodes nest`() {
    nextId = 1L
    val icon = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.ImageView"),
      bounds = TrailblazeNode.Bounds(40, 40, 80, 80),
    )
    val innerButton = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.Button", isClickable = true),
      bounds = TrailblazeNode.Bounds(20, 20, 100, 100),
      children = listOf(icon),
    )
    val outerCard = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup", isClickable = true),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 200),
      children = listOf(innerButton),
    )
    val root = node(bounds = TrailblazeNode.Bounds(0, 0, 400, 800), children = listOf(outerCard))

    val hit = root.hitTest(60, 60)
    assertNotNull(hit)
    assertEquals(innerButton.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest ignores an interactive container that is not an ancestor of the tapped element`() {
    nextId = 1L
    // A full-screen clickable scrim drawn behind the sheet: it contains the point, is
    // interactive, and is unrelated to what was tapped.
    val scrim = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View", isClickable = true),
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
    )
    val heading = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.TextView", text = "Details"),
      bounds = TrailblazeNode.Bounds(100, 400, 980, 460),
    )
    val sheet = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.ViewGroup"),
      bounds = TrailblazeNode.Bounds(60, 360, 1020, 900),
      children = listOf(heading),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      children = listOf(scrim, sheet),
    )

    val hit = root.hitTest(540, 430)
    assertNotNull(hit)
    assertEquals(heading.nodeId, hit.nodeId)
  }

  // ======================================================================
  // resolveFromTap: basic cases
  // ======================================================================

  @Test
  fun `resolveFromTap returns null for empty area`() {
    nextId = 1L
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(
        node(bounds = TrailblazeNode.Bounds(10, 10, 50, 50)),
      ),
    )
    assertNull(TrailblazeNodeSelectorGenerator.resolveFromTap(root, 999, 999))
  }

  @Test
  fun `resolveFromTap generates valid unique selector`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"),
      bounds = TrailblazeNode.Bounds(50, 100, 200, 150),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Cancel"),
      bounds = TrailblazeNode.Bounds(50, 200, 200, 250),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(target, other),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 125, 125)
    assertNotNull(result)
    assertEquals(target.nodeId, result.targetNode.nodeId)
    assertNotNull(result.resolvedCenter)
    assertTrue(result.roundTripValid, "Round-trip should be valid for simple case")
  }

  @Test
  fun `resolveFromTap selector resolves back to same node`() {
    nextId = 1L
    val button = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Buy Now",
        className = "android.widget.Button",
      ),
      bounds = TrailblazeNode.Bounds(100, 300, 300, 360),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
      children = listOf(button),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 200, 330)
    assertNotNull(result)

    // Verify the selector resolves to the same node
    val resolveResult = TrailblazeNodeSelectorResolver.resolve(root, result.selector)
    assertTrue(resolveResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
    assertEquals(button.nodeId, resolveResult.node.nodeId)
  }

  // ======================================================================
  // resolveFromTap: Z-index / child overlap scenarios
  // ======================================================================

  @Test
  fun `resolveFromTap hits child not parent when tapping overlapping area`() {
    nextId = 1L
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Child Text"),
      bounds = TrailblazeNode.Bounds(20, 20, 80, 60),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        resourceId = "com.example:id/container",
      ),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(parent),
    )

    // Tap at child center — should resolve to child, not parent
    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 50, 40)
    assertNotNull(result)
    assertEquals(child.nodeId, result.targetNode.nodeId)
  }

  @Test
  fun `resolveFromTap roundTripValid detects when center hits different node`() {
    nextId = 1L
    // Parent with child exactly at center — selector for parent resolves to parent center,
    // but hitTest at parent center would hit the child instead
    val smallChild = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Label"),
      bounds = TrailblazeNode.Bounds(40, 40, 60, 60), // centered in parent
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
        resourceId = "com.example:id/wrapper",
      ),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(smallChild),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(parent),
    )

    // Tap the parent at a corner where child is NOT present — hits parent
    val tapResult = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 5, 5)
    assertNotNull(tapResult)
    assertEquals(parent.nodeId, tapResult.targetNode.nodeId)

    // The selector resolves to parent's center (50, 50) which is covered by the child.
    // So roundTripValid should be false if the center point hits the child.
    if (tapResult.resolvedCenter != null) {
      val (cx, cy) = tapResult.resolvedCenter
      val hitAtCenter = root.hitTest(cx, cy)
      if (hitAtCenter?.nodeId != parent.nodeId) {
        // The round trip correctly detected this
        assertTrue(!tapResult.roundTripValid)
      }
    }
  }

  @Test
  fun `resolveFromTap roundTripValid true when no child overlap at center`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Big Button"),
      bounds = TrailblazeNode.Bounds(50, 50, 350, 150),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(target),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 200, 100)
    assertNotNull(result)
    assertTrue(result.roundTripValid)
  }

  @Test
  fun `resolveFromTap with deeply nested hierarchy`() {
    nextId = 1L
    val deepLeaf = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Deep Leaf"),
      bounds = TrailblazeNode.Bounds(45, 45, 55, 55),
    )
    val level2 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(40, 40, 60, 60),
      children = listOf(deepLeaf),
    )
    val level1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.LinearLayout"),
      bounds = TrailblazeNode.Bounds(20, 20, 80, 80),
      children = listOf(level2),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(level1),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 50, 50)
    assertNotNull(result)
    // Should hit the deepest/smallest node
    assertEquals(deepLeaf.nodeId, result.targetNode.nodeId)
  }

  @Test
  fun `resolveFromTap with multiple siblings picks correct one`() {
    nextId = 1L
    val button1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "First"),
      bounds = TrailblazeNode.Bounds(10, 10, 100, 50),
    )
    val button2 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Second"),
      bounds = TrailblazeNode.Bounds(10, 60, 100, 100),
    )
    val button3 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Third"),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 200, 200),
      children = listOf(button1, button2, button3),
    )

    // Tap second button
    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 55, 80)
    assertNotNull(result)
    assertEquals(button2.nodeId, result.targetNode.nodeId)
    assertTrue(result.roundTripValid)

    // The selector should uniquely identify button2
    val resolveResult = TrailblazeNodeSelectorResolver.resolve(root, result.selector)
    assertTrue(resolveResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
    assertEquals(button2.nodeId, resolveResult.node.nodeId)
  }

  // ======================================================================
  // resolveFromTap: identical nodes (index fallback)
  // ======================================================================

  @Test
  fun `resolveFromTap with identical nodes uses index and round trips`() {
    nextId = 1L
    val items = (0..2).map { i ->
      node(
        detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
        bounds = TrailblazeNode.Bounds(0, i * 60, 100, i * 60 + 50),
      )
    }
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 200),
      children = items,
    )

    // Tap middle item (center at 50, 85)
    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 50, 85)
    assertNotNull(result)
    assertEquals(items[1].nodeId, result.targetNode.nodeId)

    // Verify selector resolves back
    val resolveResult = TrailblazeNodeSelectorResolver.resolve(root, result.selector)
    assertTrue(resolveResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
    assertEquals(items[1].nodeId, resolveResult.node.nodeId)
  }

  // ======================================================================
  // resolveFromTap: Compose driver variant
  // ======================================================================

  @Test
  fun `resolveFromTap works with Compose nodes`() {
    nextId = 1L
    val target = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(50, 100, 250, 160),
      driverDetail = DriverNodeDetail.Compose(testTag = "submit_btn", text = "Submit"),
      children = emptyList(),
    )
    val other = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(50, 200, 250, 260),
      driverDetail = DriverNodeDetail.Compose(testTag = "cancel_btn", text = "Cancel"),
      children = emptyList(),
    )
    val root = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      driverDetail = DriverNodeDetail.Compose(),
      children = listOf(target, other),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 150, 130)
    assertNotNull(result)
    assertEquals(target.nodeId, result.targetNode.nodeId)
    assertTrue(result.roundTripValid)
  }

  // ======================================================================
  // Fixture-driven: Android accessibility overlapping-bounds round-trip
  // ======================================================================

  /**
   * Regression for the Square pre-login Sign-in tap. Pre-login screens have an unlabeled
   * clickable `android.view.View` wrapping a TextView that carries the visible label
   * ("Sign in") but no `ACTION_CLICK`, plus a sibling Button child (also no `ACTION_CLICK`).
   * All three nodes share a center point — hitTest must pick the clickable wrapper, the
   * generated selector must round-trip back to it, and the recording must come out single-
   * shape (`androidAccessibility` populated, no Maestro slot leakage).
   *
   * Loaded from `fixtures/android-accessibility/sign-in-button.json` so the assertions sit
   * against a real captured tree shape, not a hand-rolled approximation. The fixture is
   * deliberately tiny — root + one wrapper + two children — so the test stays readable.
   */
  @Test
  fun `accessibility hitTest prefers clickable wrapper over text child sharing center`() {
    val tree = loadAccessibilityFixture("sign-in-button.json")
    // Center of the wrapper. The TextView "Sign in" (412..509, 1752..1800) and the
    // unlabeled Button (80..840, 1712..1840) both contain this point too, so the
    // interactive-first ordering on hitTest is what pins down the right answer.
    val hit = tree.hitTest(SIGN_IN_CENTER_X, SIGN_IN_CENTER_Y)
    assertNotNull(hit, "hitTest should return a node at the wrapper center")
    assertEquals(
      SIGN_IN_WRAPPER_NODE_ID,
      hit.nodeId,
      "hitTest must select the clickable wrapper (nodeId=$SIGN_IN_WRAPPER_NODE_ID), " +
        "not the TextView (6) or Button (7) children. Got nodeId=${hit.nodeId} " +
        "(${hit.driverDetail::class.simpleName}).",
    )
  }

  @Test
  fun `accessibility selector for clickable wrapper is single-shape and round-trips`() {
    val tree = loadAccessibilityFixture("sign-in-button.json")
    val hit = tree.hitTest(SIGN_IN_CENTER_X, SIGN_IN_CENTER_Y)
    assertNotNull(hit)

    // findBestSelector verifies isUniqueMatch internally — if it returns at all, the
    // selector resolves back to exactly `hit`. Pin the *shape* invariant: accessibility
    // recordings must populate the androidAccessibility slot only, never the maestro one.
    val selector = TrailblazeNodeSelectorGenerator.findBestSelector(tree, hit)
    assertNotNull(
      selector.androidAccessibility,
      "Selector for an AndroidAccessibility-shaped node must populate `androidAccessibility`",
    )
    assertNull(
      selector.androidMaestro,
      "Single-format invariant: accessibility recordings must NOT also populate the " +
        "Maestro slot. Found: ${selector.androidMaestro}",
    )

    // Full round-trip: tap at center → generate → resolve → re-hit-test must close back
    // on the same nodeId. resolveFromTap is the canonical entry point that does all of
    // it, including the roundTripValid check.
    val resolution = TrailblazeNodeSelectorGenerator.resolveFromTap(
      tree,
      SIGN_IN_CENTER_X,
      SIGN_IN_CENTER_Y,
    )
    assertNotNull(resolution)
    assertEquals(SIGN_IN_WRAPPER_NODE_ID, resolution.targetNode.nodeId)
    assertTrue(
      resolution.roundTripValid,
      "Round-trip closure broken: resolved selector's center hit-tests back to a " +
        "different node. resolvedCenter=${resolution.resolvedCenter}",
    )
  }

  private fun loadAccessibilityFixture(name: String): TrailblazeNode {
    val resource = checkNotNull(this::class.java.classLoader.getResource("fixtures/android-accessibility/$name")) {
      "Fixture not found on test classpath: fixtures/android-accessibility/$name"
    }
    return xyz.block.trailblaze.logs.client.TrailblazeJson.defaultWithoutToolsInstance
      .decodeFromString(TrailblazeNode.serializer(), resource.readText())
  }

  companion object {
    /** Center of the "Sign in" wrapper in `sign-in-button.json`. */
    private const val SIGN_IN_CENTER_X = 460
    private const val SIGN_IN_CENTER_Y = 1776

    /** nodeId of the clickable `android.view.View` wrapper in `sign-in-button.json`. */
    private const val SIGN_IN_WRAPPER_NODE_ID = 8L
  }
}
