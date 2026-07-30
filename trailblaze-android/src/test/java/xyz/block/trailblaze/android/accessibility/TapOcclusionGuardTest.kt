package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Geometry throughout is the real captured hierarchy from the motivating failure: a "Loyalty
 * account deleted." snackbar overlaying an item row, from build 7602's `AgentDriverLog`.
 */
class TapOcclusionGuardTest {

  // --- The motivating repro ---

  @Test
  fun `snackbar overlapping the item row at the tap point is detected`() {
    val item = itemRow()
    val root = screenWith(grid(item), snackbar())

    val (x, y) = item.centerPoint()!!
    assertEquals(543 to 1393, x to y, "tap point is the item row's center")

    val verdict = assessTapOcclusion(root, item, x, y)
    assertNotNull(verdict, "snackbar covers the resolved tap point and paints after the item")
    assertEquals(SNACKBAR_ID, verdict.occluder.nodeId)
  }

  /**
   * The captured snackbar reports `isVisibleToUser=false` while on-screen and absorbing the tap.
   * Filtering candidates on that flag would skip the exact shape this check exists to catch —
   * the check would go quiet on its own headline repro.
   */
  @Test
  fun `occluder reporting isVisibleToUser=false is still detected`() {
    val item = itemRow()
    val invisibleFlagged = snackbar(isVisibleToUser = false)
    val root = screenWith(grid(item), invisibleFlagged)

    val verdict = assessTapOcclusion(root, item, 543, 1393)
    assertNotNull(verdict, "isVisibleToUser must not gate candidacy")
    assertEquals(SNACKBAR_ID, verdict.occluder.nodeId)
    assertTrue(
      verdict.description.contains("isVisibleToUser=false"),
      "the counter-intuitive flag belongs in the diagnostic: ${verdict.description}",
    )
  }

  /**
   * The snackbar animates in — captured at (170,1592) three seconds before it settled at
   * (170,1372). Measuring it mid-slide answers a different question than measuring it at tap
   * time, which is why the hit-test runs against a per-poll capture.
   */
  @Test
  fun `same snackbar mid-animation does not yet cover the tap point`() {
    val item = itemRow()
    val sliding = snackbar(bounds = TrailblazeNode.Bounds(170, 1592, 607, 1652))
    val root = screenWith(grid(item), sliding)

    assertNull(assessTapOcclusion(root, item, 543, 1393))
  }

  @Test
  fun `same snackbar does NOT trip the check when the tap point falls outside it`() {
    // The snackbar ends at x=607; the item runs to x=906. A tap at x=750 is genuinely clear.
    val item = itemRow()
    val root = screenWith(grid(item), snackbar())

    assertNull(assessTapOcclusion(root, item, 750, 1393))
  }

  // --- False positives the check must not introduce ---

  @Test
  fun `full-screen container that contains the target is not an occluder`() {
    val item = itemRow()
    val backdrop = node(SNACKBAR_ID, SCREEN, a11y(contentDescription = "Content", isClickable = true))
    val root = screenWith(grid(item), backdrop)

    assertNull(
      assessTapOcclusion(root, item, 543, 1393),
      "a node that fully contains the target is a container/backdrop, not an overlay",
    )
  }

  @Test
  fun `the target's own ancestors and descendants are never occluders`() {
    val label = node(SNACKBAR_ID, TrailblazeNode.Bounds(200, 1380, 500, 1410), a11y(text = "Blueberry Muffin"))
    val item = node(
      ITEM_ID,
      ITEM_BOUNDS,
      a11y(contentDescription = "Blueberry Muffin", isClickable = true),
      listOf(label),
    )
    val root = screenWith(grid(item))

    assertNull(assessTapOcclusion(root, item, 543, 1393))
  }

  /**
   * The target is resolved against the `filterImportantForAccessibility` view of the tree while
   * the hit-test scans the unfiltered root, so the target's own subtree has to be re-derived from
   * the unfiltered tree rather than walked on the passed-in node. A label-less clickable
   * click-catcher inside the row is exactly the shape the filter drops (it carries no readable
   * label, and interactive flags deliberately do not save a node from the filter), so walking the
   * filtered target's subtree leaves it out of the exclusion set and the row's own child is
   * reported as occluding the row, at that row's own center.
   */
  @Test
  fun `a filtered-out clickable child of the target does not occlude its own parent`() {
    val clickCatcher = node(
      CLICK_CATCHER_ID,
      // Inset inside the row: covers the row's center, but does not fully contain the row, so the
      // container/backdrop filter is not what suppresses it.
      TrailblazeNode.Bounds(184, 1373, 902, 1414),
      a11y(className = "android.view.View", isClickable = true, isImportantForAccessibility = false),
    )
    val item = node(
      ITEM_ID,
      ITEM_BOUNDS,
      a11y(text = "Blueberry Muffin", isClickable = true),
      listOf(clickCatcher),
    )
    val root = screenWith(grid(item))

    // The real filter, not a hand-built stand-in: this is the tree shape the selector resolves
    // against, so it is what decides whether the click-catcher reaches the exclusion set.
    val resolvedTarget = assertNotNull(
      root.filterImportantForAccessibility().findFirst { it.nodeId == ITEM_ID },
    )
    assertTrue(
      resolvedTarget.children.isEmpty(),
      "precondition: the filter drops the label-less clickable child from the resolved target",
    )
    assertTrue(
      root.aggregate().any { it.nodeId == CLICK_CATCHER_ID },
      "precondition: the click-catcher is still in the unfiltered tree the hit-test scans",
    )

    val (x, y) = item.centerPoint()!!
    assertNull(
      assessTapOcclusion(root, resolvedTarget, x, y),
      "the target's own descendant is never an occluder of the target",
    )
  }

  @Test
  fun `a non-content decoration overlapping the target is not an occluder`() {
    val item = itemRow()
    val divider = node(SNACKBAR_ID, SNACKBAR_BOUNDS, a11y(className = "android.view.View"))
    val root = screenWith(grid(item), divider)

    assertNull(
      assessTapOcclusion(root, item, 543, 1393),
      "no text, no contentDescription, not clickable — a decoration, not an interactive overlay",
    )
  }

  @Test
  fun `a node painted BEFORE the target does not occlude it`() {
    val badge = node(3, SNACKBAR_BOUNDS, a11y(text = "NEW"))
    val item = itemRow()
    val root = screenWith(node(2, SCREEN, a11y(), listOf(badge, item)))

    assertNull(assessTapOcclusion(root, item, 543, 1393))
  }

  @Test
  fun `a target absent from the tree yields no verdict`() {
    val root = screenWith(grid(itemRow()), snackbar())
    val strayTarget = node(9999, ITEM_BOUNDS, a11y(text = "Not in this tree", isClickable = true))

    assertNull(assessTapOcclusion(root, strayTarget, 543, 1393))
  }

  @Test
  fun `the topmost of several overlapping occluders is reported`() {
    val item = itemRow()
    val lower = node(5, SNACKBAR_BOUNDS, a11y(text = "lower overlay"))
    val upper = node(6, SNACKBAR_BOUNDS, a11y(text = "upper overlay"))
    val root = screenWith(grid(item), lower, upper)

    val verdict = assessTapOcclusion(root, item, 543, 1393)
    assertNotNull(verdict)
    assertEquals(6L, verdict.occluder.nodeId, "last in paint order wins")
  }

  // --- IME signal (previously covered by no test at all) ---

  @Test
  fun `ime bounds containing the tap point produce a signal`() {
    val signal = imeOcclusionSignal(
      imeBounds = TrailblazeNode.Bounds(0, 1200, 1080, 1920),
      imeShownAuthoritative = false,
      x = 543,
      y = 1393,
    )
    assertNotNull(signal)
  }

  @Test
  fun `ime bounds not containing the tap point produce no signal`() {
    assertNull(
      imeOcclusionSignal(
        imeBounds = TrailblazeNode.Bounds(0, 1200, 1080, 1920),
        imeShownAuthoritative = true,
        x = 543,
        y = 400,
      ),
      "measured bounds are authoritative — a shown IME elsewhere on screen is not occlusion",
    )
  }

  @Test
  fun `unmeasurable but shown ime is conservatively treated as occlusion`() {
    val signal = imeOcclusionSignal(imeBounds = null, imeShownAuthoritative = true, x = 543, y = 1393)
    assertNotNull(signal)
    assertTrue(signal.contains("degraded"), signal)
  }

  @Test
  fun `no ime at all produces no signal`() {
    assertNull(imeOcclusionSignal(imeBounds = null, imeShownAuthoritative = false, x = 543, y = 1393))
  }

  /**
   * `android.graphics.Rect.contains` — the check this predicate replaced — is half-open: a point
   * on the right or bottom edge is outside. `Bounds.containsPoint` is inclusive, so reusing it
   * would have silently widened IME occlusion by one pixel on two edges.
   */
  @Test
  fun `ime containment is half-open on the right and bottom edges`() {
    val bounds = TrailblazeNode.Bounds(0, 1200, 1080, 1920)
    assertNotNull(imeOcclusionSignal(bounds, false, x = 0, y = 1200), "top-left corner is inside")
    assertNull(imeOcclusionSignal(bounds, false, x = 1080, y = 1500), "right edge is outside")
    assertNull(imeOcclusionSignal(bounds, false, x = 500, y = 1920), "bottom edge is outside")
    assertNotNull(imeOcclusionSignal(bounds, false, x = 1079, y = 1919), "one pixel in is inside")
  }

  @Test
  fun `an empty ime rect contains nothing`() {
    assertNull(imeOcclusionSignal(TrailblazeNode.Bounds(500, 500, 500, 500), false, x = 500, y = 500))
  }

  // --- fixtures ---

  private fun itemRow() = node(ITEM_ID, ITEM_BOUNDS, a11y(text = "Blueberry Muffin", isClickable = true))

  private fun snackbar(
    bounds: TrailblazeNode.Bounds = SNACKBAR_BOUNDS,
    isVisibleToUser: Boolean = true,
  ) = node(SNACKBAR_ID, bounds, a11y(
    className = "android.widget.TextView",
    text = "Loyalty account deleted.",
    isVisibleToUser = isVisibleToUser,
  ))

  private fun screenWith(vararg children: TrailblazeNode) =
    node(1, SCREEN, a11y(className = "android.widget.FrameLayout"), children.toList())

  private fun grid(vararg items: TrailblazeNode) = node(
    2,
    TrailblazeNode.Bounds(0, 400, 1080, 1900),
    a11y(className = "androidx.recyclerview.widget.RecyclerView"),
    items.toList(),
  )

  private fun node(
    id: Long,
    bounds: TrailblazeNode.Bounds,
    detail: DriverNodeDetail.AndroidAccessibility,
    children: List<TrailblazeNode> = emptyList(),
  ) = TrailblazeNode(nodeId = id, bounds = bounds, driverDetail = detail, children = children)

  private fun a11y(
    className: String? = null,
    text: String? = null,
    contentDescription: String? = null,
    isClickable: Boolean = false,
    isVisibleToUser: Boolean = true,
    isImportantForAccessibility: Boolean = true,
  ) = DriverNodeDetail.AndroidAccessibility(
    className = className,
    text = text,
    contentDescription = contentDescription,
    isClickable = isClickable,
    isVisibleToUser = isVisibleToUser,
    isImportantForAccessibility = isImportantForAccessibility,
  )

  private companion object {
    const val ITEM_ID = 4L
    const val SNACKBAR_ID = 5L
    const val CLICK_CATCHER_ID = 6L
    val SCREEN = TrailblazeNode.Bounds(0, 0, 1080, 1920)
    val ITEM_BOUNDS = TrailblazeNode.Bounds(180, 1369, 906, 1418)
    val SNACKBAR_BOUNDS = TrailblazeNode.Bounds(170, 1372, 607, 1432)
  }
}
