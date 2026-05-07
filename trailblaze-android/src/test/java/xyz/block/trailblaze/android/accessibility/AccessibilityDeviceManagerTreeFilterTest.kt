package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver

/**
 * Regression fixture for a customer-picker-style tap failure, plus the
 * [SnapshotDetail.ALL_ELEMENTS] escape hatch that the fallback path preserves.
 *
 * The bug: [AccessibilityDeviceManager]'s resolver paths (executeTapOnElement et al.)
 * were calling `tree.toTrailblazeNode()` directly, while [AccessibilityServiceScreenState]
 * (the LLM-facing tree) applied [filterImportantForAccessibility]. When a non-important
 * intermediate node sits between a `containsChild`-matched parent and its target child,
 * the unfiltered tree breaks the immediate-child relationship while the filtered tree
 * preserves it (children are promoted up when the non-important parent is dropped).
 *
 * Reproduction shape: a clickable row [ViewGroup] with a Compose layout wrapper child
 * with isImportantForAccessibility=false; that wrapper's children include the
 * [FrameLayout] target the user wants to tap. The LLM sees the FrameLayout as a direct
 * child of the ViewGroup; the resolver did not.
 *
 * Resolver contract: filtered-first with unfiltered fallback. The default (filtered)
 * path matches the LLM and recording-playback flows; the unfiltered fallback preserves
 * the `--all` / [SnapshotDetail.ALL_ELEMENTS] escape hatch for selectors targeting
 * `isImportantForAccessibility=false` nodes.
 */
class AccessibilityDeviceManagerTreeFilterTest {

  @Test
  fun `containsChild selector resolves only after non-important intermediate is filtered out`() {
    // Synthetic tree mirroring a clickable-row picker shape:
    //   ViewGroup (row, important, clickable, bounds 0/581/1080/862)
    //     └── ComposeWrapper (NOT important — the regression trigger)
    //           └── FrameLayout (customer_name_wrapper, "customer name 2",
    //                            bounds 180/704/462/759)
    val targetChild = node(
      bounds = TrailblazeNode.Bounds(180, 704, 462, 759),
      detail = androidA11y(
        className = "android.widget.FrameLayout",
        resourceId = "com.example.app:id/customer_name_wrapper",
        contentDescription = "customer name 2",
        isImportantForAccessibility = true,
      ),
    )
    val nonImportantWrapper = node(
      bounds = TrailblazeNode.Bounds(0, 581, 1080, 862),
      detail = androidA11y(
        className = "androidx.compose.ui.platform.ComposeView",
        isImportantForAccessibility = false,
      ),
      children = listOf(targetChild),
    )
    val rowViewGroup = node(
      bounds = TrailblazeNode.Bounds(0, 581, 1080, 862),
      detail = androidA11y(
        className = "android.view.ViewGroup",
        isImportantForAccessibility = true,
        isClickable = true,
      ),
      children = listOf(nonImportantWrapper),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      detail = androidA11y(className = "android.view.ViewGroup", isImportantForAccessibility = true),
      children = listOf(rowViewGroup),
    )

    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        classNameRegex = "\\Qandroid.view.ViewGroup\\E",
      ),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(
          classNameRegex = "\\Qandroid.widget.FrameLayout\\E",
          resourceIdRegex = "\\Qcom.example.app:id/customer_name_wrapper\\E",
          contentDescriptionRegex = "customer name 2",
        ),
      ),
    )

    // Regression repro: against the unfiltered tree, FrameLayout is a grandchild,
    // so containsChild (immediate-child only) yields NoMatch.
    val unfilteredResult = TrailblazeNodeSelectorResolver.resolve(root, selector)
    assertTrue(
      unfilteredResult is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch,
      "Unfiltered tree should not match — confirms the regression repro is real. Got: $unfilteredResult",
    )

    // Fix verification: filtering promotes FrameLayout up to be a direct child of the
    // row ViewGroup, matching the LLM's view of the tree, so the selector resolves and
    // centerPoint is the row's center, not (0, 0).
    val filteredResult = TrailblazeNodeSelectorResolver.resolve(
      root.filterImportantForAccessibility(),
      selector,
    )
    assertTrue(
      filteredResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch,
      "Filtered tree should match the row ViewGroup. Got: $filteredResult",
    )
    val matchedBounds = filteredResult.node.bounds
    assertEquals(TrailblazeNode.Bounds(0, 581, 1080, 862), matchedBounds)
    val center = filteredResult.node.centerPoint()
    assertEquals(540 to 721, center, "Filtered match's center should land on the row, not (0, 0)")
  }

  @Test
  fun `selector targeting a non-important node falls back to unfiltered tree resolution`() {
    // Models the SnapshotDetail.ALL_ELEMENTS escape hatch: a selector generated against
    // the unfiltered tree that explicitly targets a node with isImportantForAccessibility=false.
    // The filtered tree drops that node entirely, so a filter-only resolver would NoMatch.
    // The fallback path resolves it correctly.
    val targetNonImportantNode = node(
      bounds = TrailblazeNode.Bounds(100, 200, 300, 400),
      detail = androidA11y(
        className = "android.view.View",
        resourceId = "com.example.app:id/decorative_overlay",
        isImportantForAccessibility = false,
      ),
    )
    val parent = node(
      bounds = TrailblazeNode.Bounds(0, 0, 1080, 1920),
      detail = androidA11y(
        className = "android.view.ViewGroup",
        isImportantForAccessibility = true,
      ),
      children = listOf(targetNonImportantNode),
    )

    val selector = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        classNameRegex = "\\Qandroid.view.View\\E",
        resourceIdRegex = "\\Qcom.example.app:id/decorative_overlay\\E",
      ),
    )

    // Filtered tree drops the target entirely → NoMatch.
    val filteredResult = TrailblazeNodeSelectorResolver.resolve(
      parent.filterImportantForAccessibility(),
      selector,
    )
    assertTrue(
      filteredResult is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch,
      "Filtered tree should drop the non-important target. Got: $filteredResult",
    )

    // Unfiltered tree (the fallback path) resolves it.
    val unfilteredResult = TrailblazeNodeSelectorResolver.resolve(parent, selector)
    assertTrue(
      unfilteredResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch,
      "Unfiltered fallback should match the non-important target. Got: $unfilteredResult",
    )
    val matchedBounds = unfilteredResult.node.bounds
    assertEquals(TrailblazeNode.Bounds(100, 200, 300, 400), matchedBounds)
  }

  // --- Test helpers ---

  private fun node(
    bounds: TrailblazeNode.Bounds,
    detail: DriverNodeDetail,
    children: List<TrailblazeNode> = emptyList(),
  ) = TrailblazeNode(
    nodeId = synthNodeId++,
    bounds = bounds,
    children = children,
    driverDetail = detail,
  )

  private fun androidA11y(
    className: String,
    resourceId: String? = null,
    contentDescription: String? = null,
    isImportantForAccessibility: Boolean = true,
    isClickable: Boolean = false,
  ) = DriverNodeDetail.AndroidAccessibility(
    className = className,
    resourceId = resourceId,
    contentDescription = contentDescription,
    isImportantForAccessibility = isImportantForAccessibility,
    isClickable = isClickable,
  )

  companion object {
    private var synthNodeId = 1L
  }
}
