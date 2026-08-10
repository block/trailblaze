package xyz.block.trailblaze.host.axe

import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Viewport clamp for the IOS_AXE driver.
 *
 * `axe describe-ui` reports the whole scroll content, not just what is on screen, while the
 * Maestro/XCUITest path (IOS_HOST) filters its hierarchy to the viewport at capture time
 * (maestro-client `ViewHierarchy.from` applies `TreeNode.filterOutOfBounds`). Without the
 * same clamp, an element below the fold still resolves on AXE: asserts pass for off-screen
 * elements (so they stop being scroll barriers) and element taps dispatch a blind HID tap at
 * off-screen coordinates with reported success.
 *
 * This object is the adapter-side twin of Maestro's filter: it gives IOS_AXE
 * "visible = actually on screen" semantics, matching stock Maestro's `filterOutOfBounds`.
 * IOS_HOST is believed to share those semantics from the Maestro 2.6.1 source, but some
 * IOS_HOST deployments were observed not to filter off-viewport nodes — tracked separately.
 * [clamp] prunes any node less than [MIN_VISIBLE_FRACTION] visible in the viewport unless
 * one of its descendants survives (a scroll surface spanning far past the screen keeps its
 * on-screen children).
 *
 * Frames and the viewport are both in points: device dimensions come from the AXe root
 * `AXApplication` frame, the same coordinate space `describe-ui` and `axe tap` use.
 */
internal object AxeViewportClamp {

  /** Maestro's visibility cutoff — `filterOutOfBounds` prunes below 10% visible. */
  private const val MIN_VISIBLE_FRACTION = 0.1

  /**
   * Returns the tree with off-viewport nodes pruned, or null when nothing is visible.
   * Faithful port of Maestro's `TreeNode.filterOutOfBounds`: a node survives when its
   * [visibleFraction] is at least 10% or any child survives. Bounds are left untouched —
   * Maestro taps the raw bounds center of partially visible elements, so no coordinate
   * clamping happens here either.
   */
  fun clamp(node: TrailblazeNode, viewportWidth: Int, viewportHeight: Int): TrailblazeNode? {
    val survivingChildren = node.children.mapNotNull { clamp(it, viewportWidth, viewportHeight) }
    val fraction = visibleFraction(node.bounds, viewportWidth, viewportHeight)
    if (fraction < MIN_VISIBLE_FRACTION && survivingChildren.isEmpty()) return null
    return node.copy(children = survivingChildren)
  }

  /**
   * Fraction of [bounds] inside the viewport — a port of Maestro's
   * `UiElement.getVisiblePercentage`, including its edge cases: missing or 0x0 bounds are
   * 0% visible; degenerate 1-D bounds (zero width XOR zero height) are kept via the NaN
   * parity below; bounds overflowing the whole viewport on every side are 100% visible.
   */
  private fun visibleFraction(bounds: TrailblazeNode.Bounds?, viewportWidth: Int, viewportHeight: Int): Double {
    if (bounds == null) return 0.0
    if (bounds.width == 0 && bounds.height == 0) return 0.0

    val overflowsViewport = bounds.left <= 0 &&
      bounds.top <= 0 &&
      bounds.right >= viewportWidth &&
      bounds.bottom >= viewportHeight
    if (overflowsViewport) return 1.0

    val visibleX = maxOf(0, minOf(bounds.right, viewportWidth) - maxOf(bounds.left, 0))
    val visibleY = maxOf(0, minOf(bounds.bottom, viewportHeight) - maxOf(bounds.top, 0))
    val totalArea = bounds.width.toLong() * bounds.height.toLong()
    // Degenerate 1-D bounds: Maestro's 0/0 division yields NaN, whose < 0.1 comparison is
    // false, so such nodes are kept — return 1.0 for the same keep decision without the NaN.
    if (totalArea == 0L) return 1.0
    return (visibleX.toLong() * visibleY.toLong()).toDouble() / totalArea.toDouble()
  }
}
