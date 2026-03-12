package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

/**
 * Universal tree node for Trailblaze view hierarchies across all drivers.
 *
 * The common surface is deliberately minimal: tree structure, identity, and bounds.
 * Everything meaningful for element matching lives in [driverDetail], which is
 * strongly typed per driver via [DriverNodeDetail].
 *
 * This avoids the [ViewHierarchyTreeNode] pattern of forcing every platform into a
 * lowest-common-denominator model. Each driver keeps its native richness intact,
 * enabling selectors that exploit the full property surface of the platform.
 *
 * ## Design principles
 * - **Minimal common base**: Only truly universal concepts (tree, bounds, identity)
 * - **Rich driver detail**: All platform-specific properties live in [driverDetail]
 * - **No forced normalization**: No shared `text`, `role`, `isEnabled` — those mean
 *   different things on different platforms
 * - **Selector-friendly**: [DriverNodeDetail] properties are annotated as matchable
 *   or display-only, guiding selector generators
 *
 * ## Usage
 * Each driver provides a mapper from its native model to [TrailblazeNode]:
 * - Android Accessibility: `AccessibilityNode.toTrailblazeNode()`
 * - Android Maestro: `TreeNode.toTrailblazeNode()` (future)
 * - Playwright: ARIA snapshot to TrailblazeNode (future)
 * - Compose: SemanticsNode to TrailblazeNode (future)
 *
 * @see DriverNodeDetail for the sealed hierarchy of driver-specific properties
 * @see ViewHierarchyTreeNode for the legacy Maestro-compatible model (still used for Maestro path)
 */
@Serializable
data class TrailblazeNode(
  /** Auto-assigned ID within a single tree capture. Not stable across captures. */
  val nodeId: Long = 0,

  /** Child nodes in the tree. */
  val children: List<TrailblazeNode> = emptyList(),

  /** Screen-coordinate bounding rectangle. Present on every platform. */
  val bounds: Bounds? = null,

  /**
   * Driver-specific properties. This is where all the richness lives.
   *
   * Pattern-match on this to access platform-native properties:
   * ```kotlin
   * when (val detail = node.driverDetail) {
   *   is DriverNodeDetail.AndroidAccessibility -> detail.className
   *   is DriverNodeDetail.AndroidMaestro -> detail.resourceId
   *   is DriverNodeDetail.Web -> detail.ariaDescriptor
   *   is DriverNodeDetail.Compose -> detail.testTag
   * }
   * ```
   */
  val driverDetail: DriverNodeDetail,
) {

  /** Screen-coordinate bounding rectangle. */
  @Serializable
  data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    /** Returns true if this bounds fully contains [other]. */
    fun contains(other: Bounds): Boolean =
      left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    /** Returns true if point (x, y) is within this bounds. */
    fun containsPoint(x: Int, y: Int): Boolean =
      x in left..right && y in top..bottom

    /** Returns true if this bounds overlaps with [other]. */
    fun intersects(other: Bounds): Boolean =
      left < other.right && right > other.left && top < other.bottom && bottom > other.top
  }

  /** Flattens this node and all descendants into a single list (pre-order DFS). */
  fun aggregate(): List<TrailblazeNode> =
    listOf(this) + children.flatMap { it.aggregate() }

  /** Returns the center point of this node's bounds, or null if bounds are unknown. */
  fun centerPoint(): Pair<Int, Int>? =
    bounds?.let { Pair(it.centerX, it.centerY) }

  /** Finds the first node matching [predicate] via DFS, or null. */
  fun findFirst(predicate: (TrailblazeNode) -> Boolean): TrailblazeNode? {
    if (predicate(this)) return this
    for (child in children) {
      child.findFirst(predicate)?.let { return it }
    }
    return null
  }

  /** Finds all nodes matching [predicate] in the tree. */
  fun findAll(predicate: (TrailblazeNode) -> Boolean): List<TrailblazeNode> {
    val results = mutableListOf<TrailblazeNode>()
    if (predicate(this)) results.add(this)
    children.forEach { results.addAll(it.findAll(predicate)) }
    return results
  }

  /**
   * Hit-tests the tree at (x, y) and returns the frontmost (deepest/smallest) node
   * whose bounds contain the point, or null if no node contains it.
   *
   * Uses the smallest-area heuristic: among all nodes containing the point, the one
   * with the smallest bounding area is the most specific (deepest in the Z-order).
   * This mirrors how touch dispatch works in Android's view system and accessibility
   * framework — the most specific (smallest) node at a point receives the event.
   */
  fun hitTest(x: Int, y: Int): TrailblazeNode? =
    aggregate()
      .filter { it.bounds?.containsPoint(x, y) == true }
      .minByOrNull { it.bounds!!.width.toLong() * it.bounds.height.toLong() }
}
