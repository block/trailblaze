package xyz.block.trailblaze.android.uiautomator

import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Detects the "collapsed Compose surface" shape in a UiAutomator-captured view hierarchy.
 *
 * ## What this catches
 *
 * Jetpack Compose only exports its semantic tree into the platform `AccessibilityNodeInfo`
 * tree when it believes accessibility is active, and it does so *lazily* per
 * `androidx.compose.ui.platform.ComposeView`. The UiAutomator capture path
 * ([AndroidOnDeviceUiAutomatorScreenState.dumpViewHierarchy]) reads UiAutomation's **cached**
 * node tree and never `refresh()`es it. So if UiAutomation cached a `ComposeView` *before* its
 * semantics were exported, the dump replays that stale, childless node even after the content
 * has rendered: a full-screen content pane shows up as a single opaque
 * `ComposeView -> View` with nothing under it, while sibling native/Compose views render fine.
 *
 * The accessibility-service capture path never hits this because it `refresh()`es every node
 * (busting the cache) before reading. This detector exists so the UiAutomator path can notice
 * the collapse and re-dump through the same refresh-ing walk.
 *
 * ## Why these thresholds
 *
 * Empirically (observed across a Compose-heavy app's instrumentation-driver CI captures): a
 * *healthy* main-content `ComposeView` carries 19–180 descendants and always exposes label text;
 * a *collapsed* one has a single childless descendant and no text whatsoever. The gap is enormous,
 * so the gate is
 * deliberately conservative — it only fires for a **large** on-screen `ComposeView` whose entire
 * subtree is both tiny and text-free, which is never a legitimately-rendered content pane.
 *
 * Precision is not safety-critical here: the recovery only *adopts* a re-dump when the collapse
 * is actually resolved, so a false positive costs one bounded extra dump and changes nothing.
 * The thresholds are tuned to avoid paying that cost on the 90%+ of captures that are healthy.
 */
internal object ComposeSemanticsCollapseDetector {

  /** Class name UiAutomator reports for the Compose host wrapper. */
  private const val COMPOSE_VIEW_CLASS = "androidx.compose.ui.platform.ComposeView"

  /**
   * Minimum fraction of the screen a `ComposeView` must occupy to be considered a content pane
   * worth recovering. Small decorative/empty Compose surfaces (chips, dividers, icons) stay well
   * under this and are ignored.
   */
  private const val MIN_AREA_FRACTION = 0.20

  /**
   * Maximum descendant count for a subtree to count as "collapsed". The observed collapse has a
   * single descendant; the smallest healthy pane has 19. A cap of 4 sits comfortably in that gap
   * while tolerating a couple of structural host-view wrappers.
   */
  private const val MAX_COLLAPSED_DESCENDANTS = 4

  /**
   * Returns the first large on-screen `ComposeView` that has collapsed to an empty, text-free
   * subtree, or `null` if no such node exists. The returned node is informational (used for
   * logging which surface collapsed).
   */
  fun detectCollapsedComposeSurface(
    root: ViewHierarchyTreeNode,
    deviceWidth: Int,
    deviceHeight: Int,
  ): ViewHierarchyTreeNode? {
    if (deviceWidth <= 0 || deviceHeight <= 0) return null
    val screenArea = deviceWidth.toLong() * deviceHeight.toLong()
    return ViewHierarchyTreeNode.dfs(root) { node -> isCollapsedComposeSurface(node, screenArea) }
  }

  private fun isCollapsedComposeSurface(node: ViewHierarchyTreeNode, screenArea: Long): Boolean {
    if (node.className != COMPOSE_VIEW_CLASS) return false

    val bounds = node.bounds ?: return false
    val width = bounds.x2 - bounds.x1
    val height = bounds.y2 - bounds.y1
    if (width <= 0 || height <= 0) return false
    val areaFraction = (width.toLong() * height.toLong()).toDouble() / screenArea.toDouble()
    if (areaFraction < MIN_AREA_FRACTION) return false

    // `aggregate()` returns this node + every descendant. A healthy content pane has many of
    // both nodes and text; a collapsed one has neither.
    val subtree = node.aggregate()
    val descendantCount = subtree.size - 1
    if (descendantCount > MAX_COLLAPSED_DESCENDANTS) return false

    val hasMeaningfulText = subtree.any { it !== node && it.hasMeaningfulText() }
    return !hasMeaningfulText
  }

  private fun ViewHierarchyTreeNode.hasMeaningfulText(): Boolean =
    !text.isNullOrBlank() || !accessibilityText.isNullOrBlank() || !hintText.isNullOrBlank()
}
