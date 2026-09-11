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
 * account deleted." snackbar overlaying an item row, from a recorded `AgentDriverLog`.
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

  /**
   * The regression this guard shipped with: with no screen height to reason about, "the IME is up
   * but unmeasurable" was reported as occlusion at every coordinate on screen — including points
   * in the top tenth of a tall screen, which a bottom-docked keyboard cannot reach at any size.
   */
  @Test
  fun `unmeasurable ime does not occlude a tap the keyboard cannot reach`() {
    assertNull(
      imeOcclusionSignal(
        imeBounds = null,
        imeShownAuthoritative = true,
        x = 935,
        y = 200,
        screenWidth = 1080,
        screenHeight = 2400,
      ),
      "y=200 on a 2400px screen is above any possible bottom-docked keyboard",
    )
    assertNull(
      imeOcclusionSignal(
        imeBounds = null,
        imeShownAuthoritative = true,
        x = 540,
        y = 540,
        screenWidth = 1080,
        screenHeight = 2400,
      ),
      "y=540 on a 2400px screen is above any possible bottom-docked keyboard",
    )
  }

  @Test
  fun `unmeasurable ime still occludes a tap in keyboard territory`() {
    val signal = imeOcclusionSignal(
      imeBounds = null,
      imeShownAuthoritative = true,
      x = 540,
      y = 2000,
      screenWidth = 1080,
      screenHeight = 2400,
    )
    assertNotNull(signal, "a tap in the bottom of the screen is still conservatively occluded")
    assertTrue(signal.contains("degraded"), signal)
  }

  /**
   * The 60% bound has to bite exactly at 40% of the screen, or the constant is decorative: a
   * looser reading would clear taps the keyboard really can cover.
   */
  @Test
  fun `the unmeasurable ime bound starts at 40 percent down the screen`() {
    assertNull(
      imeOcclusionSignal(null, true, x = 0, y = 959, screenWidth = 1080, screenHeight = 2400),
      "one pixel above the bound is clear",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 0, y = 960, screenWidth = 1080, screenHeight = 2400),
      "the bound itself is occluded",
    )
  }

  /**
   * A height that does not divide evenly by the percentage, pinning the boundary where integer
   * truncation is visible: 60% of 731 is 438.6, so the cutoff is 731 - 438 = 293 rather than the
   * 292.4 a fractional computation would produce. Both round to the same verdict for every
   * integer `y` — this is here so a future change to the arithmetic has to stay that way.
   */
  @Test
  fun `the bound is exact at a screen height that does not divide evenly`() {
    assertNull(
      imeOcclusionSignal(null, true, x = 0, y = 292, screenWidth = 400, screenHeight = 731),
      "one pixel above the cutoff is clear",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 0, y = 293, screenWidth = 400, screenHeight = 731),
      "the cutoff itself is occluded",
    )
  }

  /**
   * A wider-than-tall screen gets no relaxation at all, because an IME there can enter fullscreen
   * extract mode and cover the whole screen — so the bottom-docked premise the bound rests on does
   * not hold. This is the same coordinate the taller-than-wide test above clears.
   */
  @Test
  fun `a wider than tall screen keeps the fully conservative answer`() {
    assertNotNull(
      imeOcclusionSignal(null, true, x = 935, y = 200, screenWidth = 1920, screenHeight = 1080),
      "a fullscreen IME can occlude y=200 here, so the tap must not be cleared",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 100, y = 10, screenWidth = 1920, screenHeight = 1080),
      "not even the very top of a wider-than-tall screen is ruled out",
    )
  }

  /** Equal width and height is not taller-than-wide, so it gets no relaxation either. */
  @Test
  fun `a square screen keeps the fully conservative answer`() {
    assertNotNull(
      imeOcclusionSignal(null, true, x = 100, y = 10, screenWidth = 1080, screenHeight = 1080),
      "height must exceed width for the bottom-docked premise to be worth anything",
    )
  }

  @Test
  fun `an unknown screen size keeps the fully conservative answer`() {
    assertNotNull(
      imeOcclusionSignal(null, true, x = 935, y = 200, screenWidth = null, screenHeight = null),
      "without a size there is no way to tell top from bottom, so do not clear the tap",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 935, y = 200, screenWidth = 0, screenHeight = 2400),
      "a zero width cannot establish that the screen is taller than it is wide",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 935, y = 200, screenWidth = null, screenHeight = 2400),
      "a height with no width cannot establish that the screen is taller than it is wide",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 935, y = 200, screenWidth = 1080, screenHeight = null),
      "a width with no height leaves nothing to measure the cutoff against",
    )
  }

  /**
   * A non-positive height is rejected on its own, not merely as a side effect of failing the
   * taller-than-wide comparison — so the width here is smaller than the height would need to be
   * for that comparison to do the rejecting.
   */
  @Test
  fun `a non-positive screen height keeps the fully conservative answer`() {
    assertNotNull(
      imeOcclusionSignal(null, true, x = 935, y = 200, screenWidth = -100, screenHeight = 0),
      "a zero height is not a usable measurement",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 935, y = 200, screenWidth = -100, screenHeight = -50),
      "a negative height is not a usable measurement",
    )
  }

  /**
   * An off-screen tap point cannot be reasoned about geometrically, so it keeps the conservative
   * answer. Without the lower guard a negative `y` compares as "above the cutoff" and would be
   * cleared, which is a silent loss of the pre-existing behavior.
   */
  @Test
  fun `an off screen tap point is never cleared`() {
    assertNotNull(
      imeOcclusionSignal(null, true, x = 540, y = -1, screenWidth = 1080, screenHeight = 2400),
      "a negative y is off screen, not above the keyboard",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 540, y = -5000, screenWidth = 1080, screenHeight = 2400),
      "an arbitrarily negative y is still not clear",
    )
    assertNotNull(
      imeOcclusionSignal(null, true, x = 540, y = 3000, screenWidth = 1080, screenHeight = 2400),
      "a y past the bottom of the screen is in keyboard territory, not above it",
    )
  }

  /** Measured bounds stay authoritative — the height bound must not widen or narrow them. */
  @Test
  fun `a measured ime is unaffected by the screen height bound`() {
    val bounds = TrailblazeNode.Bounds(0, 1200, 1080, 1920)
    assertNull(
      imeOcclusionSignal(bounds, true, x = 543, y = 1950, screenWidth = 1080, screenHeight = 2400),
      "below the measured IME is clear even though it is in the bottom 60% of the screen",
    )
    assertNotNull(
      imeOcclusionSignal(bounds, true, x = 543, y = 1393, screenWidth = 1080, screenHeight = 2400),
      "inside the measured IME is occluded",
    )
    assertNotNull(
      imeOcclusionSignal(bounds, true, x = 543, y = 1393, screenWidth = 1920, screenHeight = 1080),
      "and a measured IME is still authoritative on a wider-than-tall screen",
    )
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
