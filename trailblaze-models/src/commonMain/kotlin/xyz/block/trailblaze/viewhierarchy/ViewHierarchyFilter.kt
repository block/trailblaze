package xyz.block.trailblaze.viewhierarchy

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeElementSelector.Companion.isBlank
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * ViewHierarchyFilter provides functionality to filter view hierarchy elements
 * to only those that are visible and interactable, reducing the size of data
 * sent to the LLM.
 */
abstract class ViewHierarchyFilter(
  protected val screenWidth: Int,
  protected val screenHeight: Int,
) {

  /** Summary of elements removed by geometric occlusion filtering. */
  data class OcclusionSummary(
    val occluderNodeId: Long,
    val occluderClassName: String?,
    val occludedCount: Int,
  )

  /** Result of [filterOccludedElements] containing the surviving elements and occlusion metadata. */
  data class OcclusionResult(
    val elements: List<ViewHierarchyTreeNode>,
    val occlusionSummaries: List<OcclusionSummary>,
  )

  /** Occlusion summaries from the most recent call to [filterInteractableViewHierarchyTreeNodes]. */
  var occlusionSummaries: List<OcclusionSummary> = emptyList()
    protected set

  /** Maximum number of offscreen elements to include in the filtered output. */
  private val MAX_OFFSCREEN_ELEMENTS = 20

  /** Elements with ≥ this fraction of their area covered by a higher-z element are filtered. */
  protected val OCCLUSION_THRESHOLD = 0.95

  /** Minimum occluder size as a fraction of screen area — avoids treating small elements as occluders. */
  private val MIN_OCCLUDER_SCREEN_FRACTION = 0.25

  /**
   * Bounds represents the rectangular bounds of a UI element.
   */
  @Serializable
  data class Bounds(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
  ) {
    val width: Int = x2 - x1
    val height: Int = y2 - y1

    val centerX: Int = x1 + width / 2
    val centerY: Int = y1 + height / 2

    /**
     * Check if this bounds fully contains another bounds.
     */
    fun contains(other: Bounds): Boolean = (
      x1 <= other.x1 &&
        y1 <= other.y1 &&
        x2 >= other.x2 &&
        y2 >= other.y2
      )

    /**
     * Compute the fraction of this element's area that is covered by [occluder].
     * Returns 0.0 when there is no overlap or this element has zero area; 1.0 when
     * fully covered.
     */
    fun occludedBy(occluder: Bounds): Double {
      val overlapX = maxOf(0, minOf(x2, occluder.x2) - maxOf(x1, occluder.x1))
      val overlapY = maxOf(0, minOf(y2, occluder.y2) - maxOf(y1, occluder.y1))
      val overlapArea = overlapX.toLong() * overlapY
      val myArea = width.toLong() * height
      return if (myArea > 0) overlapArea.toDouble() / myArea else 0.0
    }
  }

  /**
   * Filter the view hierarchy to only include interactable elements.
   *
   * @param viewHierarchy The original view hierarchy
   * @return Filtered view hierarchy with only interactable elements
   */
  abstract fun filterInteractableViewHierarchyTreeNodes(viewHierarchy: ViewHierarchyTreeNode): ViewHierarchyTreeNode

  /**
   * Find elements that are visible on screen using platform-specific logic.
   */
  protected abstract fun findVisibleViewHierarchyTreeNodes(
    elements: List<ViewHierarchyTreeNode>,
    screenBounds: Bounds,
  ): List<ViewHierarchyTreeNode>

  /**
   * Common implementation to create the filtered view hierarchy root node.
   */
  protected fun createFilteredRoot(
    viewHierarchy: ViewHierarchyTreeNode,
    visibleElements: List<ViewHierarchyTreeNode>,
    allElements: List<ViewHierarchyTreeNode>,
  ): ViewHierarchyTreeNode {
    val interactableViewHierarchyTreeNodes: List<ViewHierarchyTreeNode> =
      findInteractableViewHierarchyTreeNodes(visibleElements)

    // Deduplicate: remove nodes that appear both as top-level entries and as
    // descendants of another top-level entry (due to aggregate() flattening
    // while retaining .children references).
    val deduplicated = deduplicateNodes(interactableViewHierarchyTreeNodes)

    // Find interactable elements that are offscreen but near the viewport
    // (within one screen dimension in each direction). These help the LLM
    // know what's available if it scrolls.
    val offscreenElements = allElements
      .filter { elem ->
        visibleElements.none { it === elem } &&
          elem.bounds != null &&
          isCompletelyOffscreen(elem.bounds) &&
          isNearViewport(elem.bounds)
      }
    val offscreenInteractable = deduplicateNodes(
      findInteractableViewHierarchyTreeNodes(offscreenElements),
    )
      .sortedBy { node -> distanceToViewport(node.bounds!!) }
      .take(MAX_OFFSCREEN_ELEMENTS)

    return ViewHierarchyTreeNode(
      children = deduplicated + offscreenInteractable,
      x1 = 0,
      y1 = 0,
      x2 = screenWidth,
      y2 = screenHeight,
    )
  }

  private fun deduplicateNodes(
    nodes: List<ViewHierarchyTreeNode>,
  ): List<ViewHierarchyTreeNode> = nodes.filterNot { candidate ->
    nodes.any { parent ->
      parent !== candidate && parent.hasDescendant(candidate)
    }
  }

  private fun isCompletelyOffscreen(bounds: Bounds): Boolean =
    bounds.x2 <= 0 || bounds.x1 >= screenWidth ||
      bounds.y2 <= 0 || bounds.y1 >= screenHeight

  private fun isNearViewport(bounds: Bounds): Boolean =
    bounds.x2 > -screenWidth && bounds.x1 < screenWidth * 2 &&
      bounds.y2 > -screenHeight && bounds.y1 < screenHeight * 2

  private fun distanceToViewport(bounds: Bounds): Int {
    val dx = when {
      bounds.x2 <= 0 -> -bounds.x2
      bounds.x1 >= screenWidth -> bounds.x1 - screenWidth
      else -> 0
    }
    val dy = when {
      bounds.y2 <= 0 -> -bounds.y2
      bounds.y1 >= screenHeight -> bounds.y1 - screenHeight
      else -> 0
    }
    return dx + dy
  }


  private fun ViewHierarchyTreeNode.hasDescendant(target: ViewHierarchyTreeNode): Boolean =
    children.any { it === target || it.hasDescendant(target) }

  /**
   * Filter out elements that are ≥[occlusionThreshold] covered by a higher-z-order element
   * that is not an ancestor or descendant. Uses position in the flat [elements] list as a
   * proxy for z-order (later = drawn on top), which matches pre-order DFS / Android draw order.
   *
   * Only elements covering ≥[MIN_OCCLUDER_SCREEN_FRACTION] of the screen area are considered
   * as potential occluders, keeping the check O(N × M) where M is small.
   */
  protected fun filterOccludedElements(
    elements: List<ViewHierarchyTreeNode>,
    occlusionThreshold: Double = OCCLUSION_THRESHOLD,
  ): OcclusionResult {
    if (elements.size <= 1) return OcclusionResult(elements, emptyList())

    val minOccluderArea =
      (screenWidth.toLong() * screenHeight * MIN_OCCLUDER_SCREEN_FRACTION).toLong()

    // Identify large elements that could act as occluders.
    data class Occluder(val index: Int, val node: ViewHierarchyTreeNode, val bounds: Bounds)
    val occluders = elements.mapIndexedNotNull { index, elem ->
      val b = elem.bounds ?: return@mapIndexedNotNull null
      if (b.width.toLong() * b.height >= minOccluderArea) Occluder(index, elem, b)
      else null
    }

    if (occluders.isEmpty()) return OcclusionResult(elements, emptyList())

    val occludedIndices = mutableSetOf<Int>()
    // Track which occluder was responsible for filtering each element.
    val occluderHits = mutableMapOf<Long, Int>() // occluder nodeId → count

    for (i in elements.indices) {
      val element = elements[i]
      val bounds = element.bounds ?: continue
      if (bounds.width <= 0 || bounds.height <= 0) continue

      for (occluder in occluders) {
        // Occluder must be drawn on top (later in traversal order).
        if (occluder.index <= i) continue

        // Never filter ancestor/descendant pairs — children overlap their parents by design.
        if (occluder.node.hasDescendantRef(element) || element.hasDescendantRef(occluder.node)) {
          continue
        }

        if (bounds.occludedBy(occluder.bounds) >= occlusionThreshold) {
          occludedIndices.add(i)
          occluderHits[occluder.node.nodeId] =
            (occluderHits[occluder.node.nodeId] ?: 0) + 1
          break
        }
      }
    }

    val occluderByNodeId = occluders.associateBy { it.node.nodeId }
    val summaries = occluderHits.mapNotNull { (nodeId, count) ->
      val occluder = occluderByNodeId[nodeId] ?: return@mapNotNull null
      occluder.index to OcclusionSummary(
        occluderNodeId = nodeId,
        occluderClassName = occluder.node.className,
        occludedCount = count,
      )
    }
      .sortedBy { (index, _) -> index }
      .map { (_, summary) -> summary }

    return OcclusionResult(
      elements = elements.filterIndexed { index, _ -> index !in occludedIndices },
      occlusionSummaries = summaries,
    )
  }

  /** Reference-equality descendant check for use with flattened aggregate() lists. */
  private fun ViewHierarchyTreeNode.hasDescendantRef(target: ViewHierarchyTreeNode): Boolean =
    children.any { it === target || it.hasDescendantRef(target) }

  /**
   * Check if two bounds rectangles overlap.
   */
  protected fun boundsOverlap(
    left1: Int,
    top1: Int,
    right1: Int,
    bottom1: Int,
    left2: Int,
    top2: Int,
    right2: Int,
    bottom2: Int,
  ): Boolean {
    // No overlap if one rectangle is to the left of the other
    if (right1 <= left2 || right2 <= left1) return false

    // No overlap if one rectangle is above the other
    if (bottom1 <= top2 || bottom2 <= top1) return false

    return true
  }

  companion object {

    /**
     * Factory method to create platform-specific ViewHierarchyFilter instances.
     */
    fun create(
      screenWidth: Int,
      screenHeight: Int,
      platform: TrailblazeDevicePlatform,
    ): ViewHierarchyFilter = when (platform) {
      TrailblazeDevicePlatform.ANDROID -> AndroidViewHierarchyFilter(screenWidth, screenHeight)
      TrailblazeDevicePlatform.IOS -> IosViewHierarchyFilter(screenWidth, screenHeight)
      TrailblazeDevicePlatform.WEB -> WebViewHierarchyFilter(screenWidth, screenHeight)
    }

    /**
     * Find elements that can be interacted with.
     */
    private fun findInteractableViewHierarchyTreeNodes(elements: List<ViewHierarchyTreeNode>): List<ViewHierarchyTreeNode> {
      val interactable = elements.filter { elem ->
        elem.isInteractable()
      }.toMutableList()

      // Process all elements that could be interactive

      // Sort interactable elements by priority:
      // 1. ViewHierarchyTreeNodes with text
      // 2. ViewHierarchyTreeNodes with resource ID
      // 3. ViewHierarchyTreeNodes with hint text
      // 4. ViewHierarchyTreeNodes with accessibility text
      // 5. Everything else
      interactable.sortWith(
        compareBy(
          { it -> it.text?.isNotEmpty() != true }, // Text elements first
          { it -> it.resourceId?.isNotEmpty() != true }, // Resource ID elements second
          { it -> it.hintText?.isNotEmpty() != true }, // Hint text elements third
          { it -> it.accessibilityText?.isNotEmpty() != true }, // Accessibility text elements fourth
          { true }, // Everything else last
        ),
      )

      return interactable
    }

    /**
     * Check if a view hierarchy element is interactable.
     */
    fun ViewHierarchyTreeNode.isInteractable(): Boolean {
      val elem = this
      // Skip disabled elements
      if (elem.enabled) {
        // Include elements that are marked interactive
        if (elem.clickable || elem.selected || elem.focusable || elem.scrollable) {
          return true
        }
        // Include elements with any text content
        if (elem.text?.isNotEmpty() == true) {
          return true
        }
        // Include elements with hint text
        if (elem.hintText?.isNotEmpty() == true) {
          return true
        }
        // Include elements with accessibility text
        if (elem.accessibilityText?.isNotEmpty() == true) {
          return true
        }
      }

      return false
    }

    /**
     * Iterates through a view hierarchy from the root going down.
     * The root is always kept, but after that we only keep nodes that have
     * meaningful properties. Nodes without properties are removed and their
     * children are promoted up to take their place.
     */
    fun ViewHierarchyTreeNode.trimEmptyNodes(): ViewHierarchyTreeNode {
      val node = this
      fun shouldKeepNode(n: ViewHierarchyTreeNode): Boolean {
        return listOf(
          n.resolveMaestroText(),
          n.resourceId
        ).any { propValue ->
          !propValue.isNullOrBlank()
        }
      }

      fun processChildren(children: List<ViewHierarchyTreeNode>): List<ViewHierarchyTreeNode> {
        return children.flatMap { child ->
          // First, recursively process the child's children
          val processedChild = child.copy(children = processChildren(child.children))

          if (shouldKeepNode(child)) {
            // Keep this node with its processed children
            listOf(processedChild)
          } else {
            // Remove this node but promote its children up
            processedChild.children
          }
        }
      }

      // Root is always kept, only process its children
      return node.copy(children = processChildren(node.children))
    }

    /**
     * Check if two bounds rectangles overlap.
     */
    protected fun boundsOverlap(
      left1: Int,
      top1: Int,
      right1: Int,
      bottom1: Int,
      left2: Int,
      top2: Int,
      right2: Int,
      bottom2: Int,
    ): Boolean {
      // No overlap if one rectangle is to the left of the other
      if (right1 <= left2 || right2 <= left1) return false

      // No overlap if one rectangle is above the other
      if (bottom1 <= top2 || bottom2 <= top1) return false

      return true
    }

    /**
     * Compute the percentage of the bounds that is visible on the screen.
     */
    fun Bounds.getVisiblePercentage(screenWidth: Int, screenHeight: Int): Double {
      val bounds = this
      if (bounds.width == 0 && bounds.height == 0) {
        return 0.0
      }

      val overflow =
        (bounds.x1 <= 0) && (bounds.y1 <= 0) && (bounds.x1 + bounds.width >= screenWidth) && (bounds.y1 + bounds.height >= screenHeight)
      if (overflow) {
        return 1.0
      }

      val visibleX = maxOf(0, minOf(bounds.x1 + bounds.width, screenWidth) - maxOf(bounds.x1, 0))
      val visibleY = maxOf(0, minOf(bounds.y1 + bounds.height, screenHeight) - maxOf(bounds.y1, 0))
      val visibleArea = visibleX * visibleY
      val totalArea = bounds.width * bounds.height

      return visibleArea.toDouble() / totalArea.toDouble()
    }

    /**
     * Filter out elements that are outside the bounds of the screen.
     *
     * If ignoreBoundsFiltering is true, return the node as is.
     */
    fun ViewHierarchyTreeNode.filterOutOfBounds(width: Int, height: Int): ViewHierarchyTreeNode? {
      if (ignoreBoundsFiltering) {
        return this
      }

      val filtered = children.mapNotNull {
        it.filterOutOfBounds(width, height)
      }.toList()

      val visiblePercentage = this.bounds?.getVisiblePercentage(width, height) ?: 0.0

      return if (visiblePercentage < 0.1 && filtered.isEmpty()) {
        null
      } else {
        this
      }
    }

    data class OptimizationResult(
      val node: ViewHierarchyTreeNode?,
      val promotedChildren: List<ViewHierarchyTreeNode>,
    )

    private fun ViewHierarchyTreeNode.hasMeaningfulAttributes(): Boolean = with(this) {
      listOf(
        text,
        hintText,
        accessibilityText,
      ).any {
        it?.isNotBlank() == true
      }
    }

    /**
     * Check if a node should be included in the optimization process.
     * Exclude nodes that are part of the status bar or have zero size.
     */
    private fun ViewHierarchyTreeNode.shouldBeIncluded(): Boolean {
      val isOkResourceId = run {
        val resourceId = this.resourceId ?: return@run true
        val hasNotNeededId =
          resourceId.contains("status_bar_container") || resourceId.contains("status_bar_launch_animation_container")
        !hasNotNeededId
      }
      val isZeroSizeView = this.bounds?.let {
        it.width == 0 || it.height == 0
      } ?: false
      return isOkResourceId && !isZeroSizeView
    }

    /**
     * Check if a node has meaningful attributes or if any of its children do.
     * This is a depth-first search to determine if the node should be included
     * in the optimization process.
     */
    private fun isMeaningfulViewDfs(node: ViewHierarchyTreeNode): Boolean {
      if (node.hasMeaningfulAttributes() || node.clickable) {
        return true
      }
      return node.children.any { isMeaningfulViewDfs(it) }
    }

    /**
     * Optimize the view hierarchy tree by removing nodes that do not have meaningful attributes
     * and promoting their children up the tree.
     *
     * This is a depth-first search optimization that reduces the tree size while preserving
     * meaningful content.
     */
    private fun ViewHierarchyTreeNode.optimizeTree(
      isRoot: Boolean = false,
      viewHierarchy: ViewHierarchyTreeNode,
    ): OptimizationResult {
      val childResults = children
        .filter { it.shouldBeIncluded() && isMeaningfulViewDfs(it) }
        .map { it.optimizeTree(false, viewHierarchy) }
      val optimizedChildren: List<ViewHierarchyTreeNode> = childResults.flatMap {
        it.node?.let { node -> listOf(node) } ?: it.promotedChildren
      }
      if (isRoot) {
        return OptimizationResult(
          node = this.copy(children = optimizedChildren),
          promotedChildren = emptyList(),
        )
      }
      val hasContentInThisNode = this.hasMeaningfulAttributes()
      if (hasContentInThisNode) {
        return OptimizationResult(
          node = this.copy(children = optimizedChildren),
          promotedChildren = emptyList(),
        )
      }
      if (optimizedChildren.isEmpty()) {
        return OptimizationResult(
          node = null,
          promotedChildren = emptyList(),
        )
      }
      val isSingleChild = optimizedChildren.size == 1
      return if (isSingleChild) {
        OptimizationResult(
          node = optimizedChildren.single(),
          promotedChildren = emptyList(),
        )
      } else {
        OptimizationResult(
          node = null,
          promotedChildren = optimizedChildren,
        )
      }
    }

    /**
     * Collect all clickable and enabled elements in the view hierarchy.
     * This is a flat extraction, not an optimization.
     */
    fun ViewHierarchyTreeNode.collectAllClickableAndEnabledElements(): List<ViewHierarchyTreeNode> {
      val result = mutableListOf<ViewHierarchyTreeNode>()
      if (this.clickable && this.enabled) {
        result.add(this)
      }
      for (child in children) {
        result.addAll(child.collectAllClickableAndEnabledElements())
      }
      return result
    }

    /**
     * Collects all elements with meaningful content for iOS platform.
     * Note: We don't filter by `enabled` here because:
     * 1. The z-order filtering in IosViewHierarchyFilter already determines visibility
     * 2. Disabled labels (like prices/times) are still visible to users and should be annotated
     */
    fun ViewHierarchyTreeNode.collectIOSElements(): List<ViewHierarchyTreeNode> = this.aggregate().filter { node ->
      node.bounds != null &&
        (
          // Include nodes with text content
          !node.text.isNullOrBlank() ||
            // Include nodes with hint text
            !node.hintText.isNullOrBlank() ||
            // Include nodes with accessibility text
            !node.accessibilityText.isNullOrBlank() ||
            // Include clickable nodes (even if not many on iOS)
            node.clickable ||
            // Include nodes with resource IDs
            !node.resourceId.isNullOrBlank() ||
            // Include focusable elements
            node.focusable
          )
    }

    fun ViewHierarchyTreeNode.asTrailblazeElementSelector(): TrailblazeElementSelector? = TrailblazeElementSelector(
      textRegex = resolveMaestroText()?.takeIf { it.isNotBlank() },
      idRegex = resourceId?.takeIf { it.isNotBlank() },
      /** Enabled is NOT a good selector and causes VERY expensive view hierarchy matching, stripping it out */
      enabled = null,
      selected = selected.takeIf { it },
      checked = checked.takeIf { it },
      focused = focused.takeIf { it },
    ).takeIf { !it.isBlank() }

    fun ViewHierarchyTreeNode.pruneNodesWithoutMaestroProperties(): ViewHierarchyTreeNode {
      // First, recursively filter all children
      val filteredChildren = this.children.flatMap { child ->
        val filteredChild = child.pruneNodesWithoutMaestroProperties()
        // If the child should be kept, keep it as a single node
        // Otherwise, promote its children (flatten)
        if (filteredChild.hasMaestroProperties()) {
          listOf(filteredChild)
        } else {
          filteredChild.children
        }
      }

      return this.copy(children = filteredChildren)
    }

    /**
     * Converts the node to a [TrailblazeElementSelector] and sees if it has any non-default properties (not blank)
     */
    fun ViewHierarchyTreeNode.hasMaestroProperties(): Boolean = this.asTrailblazeElementSelector()?.isBlank() == false
  }
}
