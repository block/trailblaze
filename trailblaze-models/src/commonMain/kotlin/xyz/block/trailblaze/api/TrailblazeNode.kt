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

  /**
   * Stable content-hashed ref for this element (e.g., "y778").
   *
   * Computed from the element's text, class name, and screen position. Same element
   * on the same screen always produces the same ref. Set by the compact element list
   * builder after tree construction — null until then.
   *
   * Format: 1 letter + 1-3 digits. Collisions get a letter suffix ("k42b").
   */
  val ref: String? = null,

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

  /** Returns a copy of this tree with refs populated from a nodeId→ref mapping. */
  fun withRefs(refMapping: Map<Long, String>): TrailblazeNode = copy(
    ref = refMapping[nodeId] ?: ref,
    children = children.map { it.withRefs(refMapping) },
  )

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
   * Hit-tests the tree at (x, y) and returns the node a touch there would act on, or null
   * if no node contains the point.
   *
   * This models real touch dispatch (Android `ViewGroup.dispatchTouchEvent`, iOS
   * `hitTest:withEvent:`): the point picks out one element, and an enclosing control can
   * only claim that touch when it is on that element's **own ancestor chain**. Two steps:
   *
   * 1. **Pick the frontmost element** among the nodes whose bounds contain the point:
   *    a node with real area over a degenerate (zero-area) one, then identifiable
   *    (text, resourceId, …) over propertyless containers, then smallest area, ties going
   *    to a *labeled* descendant when the tied nodes sit on one ancestor chain (the
   *    deepest element is what OS hit-testing returns) — unless the tied ancestor is
   *    itself interactive, because a control captured with bounds identical to its child
   *    keeps its own touch. A label-less descendant never displaces its ancestor, and
   *    sibling ties go to the first node in document order. Zero-area
   *    nodes sort last because
   *    [Bounds.containsPoint] is inclusive on both ends, so a `left == right` node contains
   *    its own center and its area of 0 would otherwise beat every real element there —
   *    but nothing can be tapped on a node with no pixels. The identifiability key only
   *    discriminates where propertyless nodes exist at all; on `androidAccessibility` every
   *    node carries a className, so step 1 is plain smallest-area there.
   * 2. **Climb to the control that owns it.** If the picked element is already interactive
   *    it takes the touch itself. Otherwise walk *its own ancestors*, nearest first, and
   *    return the first interactive one that **also contains the point** — the iOS case
   *    this exists for is a NativeButton wrapping a small non-interactive UIImageView icon,
   *    where the tappable parent is the real target. The climb stops at the first ancestor
   *    that encloses **more than one piece of text**: a control owns its own icon and
   *    label, whereas a scroll container, list or full-screen layout merely hosts
   *    independent items and is not what the tap is "on".
   *
   * The containment check on the climb is not redundant with step 1. Bounds do not nest in
   * every capture — in the committed web (ARIA) captures 77% of parent/child pairs are
   * non-nested — and without it the climb can return a control on the other side of the
   * screen from the point.
   *
   * The multiple-label bound is the difference between a control and a container, and it is
   * load-bearing. Ranking by interactivity alone (the pre-existing rule) let *any*
   * interactive node outrank a smaller one, so a focusable/scrollable ViewPager wrapping a
   * product list won every point on the screen: every row's own text lost to the pager, the
   * report inspector reported a tap mismatch on practically every element, and recorded taps
   * resolved their selector from the pager instead of the row. The bound deliberately also
   * excludes a clickable row carrying two labels of its own (title + subtitle) — the tap
   * resolves to the leaf under the point instead. That direction is safe (a gesture at the
   * leaf's center still lands inside the row) and measured better on the committed corpus
   * than every looser bound tried; see `hitTest returns the leaf inside a clickable row that
   * carries two labels of its own`.
   */
  fun hitTest(x: Int, y: Int): TrailblazeNode? {
    val ancestors = mutableListOf<TrailblazeNode>()
    var best: TrailblazeNode? = null
    var bestDegenerate = true
    var bestIdentifiable = false
    var bestArea = Long.MAX_VALUE
    var bestAncestors: List<TrailblazeNode> = emptyList()

    fun visit(node: TrailblazeNode) {
      val bounds = node.bounds
      if (bounds != null && bounds.containsPoint(x, y)) {
        val area = bounds.width.toLong() * bounds.height.toLong()
        val degenerate = area <= 0L
        val identifiable = node.driverDetail.hasIdentifiableProperties
        val wins = when {
          best == null -> true
          degenerate != bestDegenerate -> !degenerate
          identifiable != bestIdentifiable -> identifiable
          area != bestArea -> area < bestArea
          // Exact-area tie on one ancestor chain: the descendant is what real hit-testing
          // returns (UIKit walks subviews deepest-first; Android dispatches to children
          // before the parent's own handler). Matters for elements captured with bounds
          // identical to their ancestor's, e.g. an iOS in-text-link child spanning its
          // whole paragraph — without this the recorded selector silently describes the
          // paragraph instead of the link the tap resolved. Deliberately narrowed to that
          // shape, in both directions. The descendant must carry its own label: a
          // label-less structural descendant (a bare RecyclerView inside an id-bearing
          // container) would only swap the ancestor's stable selector for a weaker
          // class-name one. And the tied ancestor must not itself be interactive: the
          // control that owns the tap (with the ACTION_CLICK route and the stable id)
          // keeps its own touch, and step 2 could not recover it — a mirrored-label child
          // makes the wrapper "enclose multiple labels", which stops the climb. Sibling
          // ties (no ancestor relation) still go to the first node in document order.
          else -> node.driverDetail.ownLabel() != null &&
            best?.driverDetail?.isInteractive != true &&
            ancestors.any { it === best }
        }
        if (wins) {
          best = node
          bestDegenerate = degenerate
          bestIdentifiable = identifiable
          bestArea = area
          bestAncestors = ancestors.toList()
        }
      }
      ancestors.add(node)
      node.children.forEach { visit(it) }
      ancestors.removeAt(ancestors.size - 1)
    }
    visit(this)

    val target = best ?: return null
    if (target.driverDetail.isInteractive) return target
    // `bestAncestors` is root-first, so iterate backwards to walk outward from the node.
    for (i in bestAncestors.indices.reversed()) {
      val ancestor = bestAncestors[i]
      if (ancestor.enclosesMultipleLabels()) break
      val bounds = ancestor.bounds ?: continue
      if (bounds.containsPoint(x, y) && ancestor.driverDetail.isInteractive) return ancestor
    }
    return target
  }

  /** True when this node's subtree carries two or more distinct pieces of text. */
  private fun enclosesMultipleLabels(): Boolean = countLabels(limit = 2) >= 2

  /** Counts label-bearing nodes in this subtree, stopping as soon as [limit] is reached. */
  private fun countLabels(limit: Int): Int {
    var count = if (driverDetail.ownLabel() != null) 1 else 0
    for (child in children) {
      if (count >= limit) return count
      count += child.countLabels(limit - count)
    }
    return count
  }
}

/**
 * The node's own visible or spoken text, or null when it carries none.
 *
 * Distinct from [DriverNodeDetail.hasIdentifiableProperties], which also counts identity
 * properties a selector can match on (resourceId, className, testTag). Only text marks a
 * node as content a user reads, which is what separates a control from a container in
 * [TrailblazeNode.hitTest].
 *
 * Blank-aware at every step of each variant's fallback chain — NOT delegated to
 * `resolveText()`: captures serialize `text` as "" on nodes labeled only via
 * accessibilityText, and `resolveText()`'s elvis stops at the blank, so a trailing
 * blank-check would report such a node as unlabeled instead of falling back.
 *
 * Deliberately excludes `hintText` (unlike `resolveText()`): a hint is a prompt for
 * *absent* content, not content the node carries. An empty Search `EditText`
 * (`text = ""`, `hintText = "Search"`) wrapping its static `TextView("Search")` must not
 * read as labeled here, or the climb-stop counts two labels, treats the control as a
 * container, and strands the tap on the static child instead of the editable field.
 * In-text link children always carry their real text, so the tie never needs the hint.
 */
private fun DriverNodeDetail.ownLabel(): String? = when (this) {
  is DriverNodeDetail.AndroidAccessibility -> firstNonBlank(text, contentDescription)
  is DriverNodeDetail.AndroidMaestro -> firstNonBlank(text, accessibilityText)
  is DriverNodeDetail.IosMaestro -> firstNonBlank(text, accessibilityText)
  is DriverNodeDetail.IosAxe -> resolveText() // already blank-aware per step
  is DriverNodeDetail.Compose -> firstNonBlank(editableText, text, contentDescription)
  is DriverNodeDetail.Web -> ariaName?.takeIf { it.isNotBlank() }
}

private fun firstNonBlank(vararg candidates: String?): String? =
  candidates.firstOrNull { !it.isNullOrBlank() }

/**
 * Concise human-readable description of this node, e.g. `'Money' (Button)`.
 * Dispatches on [DriverNodeDetail] to use the best text and type for each platform.
 */
fun TrailblazeNode.describe(): String {
  val detail = driverDetail
  val (text, type) = when (detail) {
    is DriverNodeDetail.AndroidAccessibility ->
      detail.resolveText() to detail.className?.substringAfterLast('.')
    is DriverNodeDetail.AndroidMaestro ->
      detail.resolveText() to detail.className?.substringAfterLast('.')
    is DriverNodeDetail.IosMaestro ->
      detail.resolveText() to detail.className
    is DriverNodeDetail.IosAxe ->
      detail.resolveText() to detail.type
    is DriverNodeDetail.Compose ->
      detail.resolveText() to detail.role
    is DriverNodeDetail.Web ->
      detail.ariaName to detail.ariaRole
  }
  return when {
    text != null && type != null -> "'$text' ($type)"
    text != null -> "'$text'"
    type != null -> type
    else -> "element"
  }
}
