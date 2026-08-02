package xyz.block.trailblaze.android.accessibility

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Pre-tap occlusion detection: "is something drawn on top of the point we are about to tap?"
 *
 * Kept as pure functions over already-captured data (a [TrailblazeNode] tree, the resolved
 * target, and the tap point) so the whole decision is unit-testable with no device, no live
 * `AccessibilityNodeInfo`, and no platform API — the same seam as [planActionClickRoute].
 */

/** A node that covers the tap point and paints after the intended target. */
internal data class OcclusionVerdict(
  val occluder: TrailblazeNode,
  val description: String,
)

/**
 * Returns the topmost content-bearing node covering ([x], [y]) that paints after [target], or
 * null when the tap point is clear.
 *
 * Why a tree hit-test rather than a window-level check: the motivating failure (a Material
 * snackbar absorbing a tap on the item row beneath it) is a `View` inside the *same* window as
 * its victim, so window enumeration has nothing to report. Capture also drops non-application
 * windows before we ever see them (`orderCaptureWindows`), so a window-level predicate and the
 * captured tree would be answering questions over different data. Geometry over the merged tree
 * subsumes both same-window and cross-window occlusion.
 *
 * Paint order is approximated by pre-order DFS index: the merged root orders windows base-first
 * by ascending layer, and within a window child order follows draw order, so a later index means
 * "drawn later" = on top. This is an **unvalidated heuristic with no available corrective
 * signal** — `AccessibilityNodeInfo.getDrawingOrder()` would be the platform's own z-signal, but
 * it reads 0 for every sibling in 99.7% of captured sibling groups, so there is nothing to
 * tie-break with. That imprecision is a deliberate part of why this reports rather than fails.
 *
 * [target] is located by `nodeId`, not identity, so [root] may be the unfiltered tree while the
 * target was resolved against the `filterImportantForAccessibility` view of it. Everything below
 * therefore works from `flat[targetIndex]` — the target's *unfiltered* incarnation — rather than
 * from [target] itself: the filtered node's subtree omits whatever the filter dropped, and a
 * missing descendant would be reported as occluding its own ancestor (see [relatedNodeIds]).
 */
internal fun assessTapOcclusion(
  root: TrailblazeNode,
  target: TrailblazeNode,
  x: Int,
  y: Int,
): OcclusionVerdict? {
  val flat = root.aggregate()
  val targetIndex = flat.indexOfFirst { it.nodeId == target.nodeId }
  if (targetIndex < 0) return null
  val unfilteredTarget = flat[targetIndex]
  val targetBounds = unfilteredTarget.bounds ?: return null
  val related = relatedNodeIds(root, unfilteredTarget)

  return flat.asSequence()
    .drop(targetIndex + 1)
    .filter { it.nodeId !in related }
    .filter { it.bounds?.containsPoint(x, y) == true }
    .filter { isContentBearing(it) }
    // A node that fully contains the target is a container/backdrop, not an overlay.
    .filterNot { it.bounds!!.contains(targetBounds) }
    .lastOrNull()
    ?.let { OcclusionVerdict(it, describeOccluder(it)) }
}

/**
 * Returns a description of the IME occlusion signal when ([x], [y]) is covered by the soft
 * keyboard, or null when the point is clear.
 *
 * [imeBounds] is the IME window's screen bounds when accessibility window enumeration is
 * healthy. When it is null the enumeration is degraded (some accessibility-service flag
 * combinations leak null window lists) and [imeShownAuthoritative] — the dumpsys answer — is all
 * we have: if the IME is up but unmeasurable, conservatively treat the point as occluded.
 */
internal fun imeOcclusionSignal(
  imeBounds: TrailblazeNode.Bounds?,
  imeShownAuthoritative: Boolean,
  x: Int,
  y: Int,
): String? {
  if (imeBounds != null) {
    // Half-open on right/bottom, and an empty rect contains nothing — matching
    // `android.graphics.Rect.contains`, the predicate this replaced. Deliberately NOT
    // `Bounds.containsPoint`, which is inclusive on all four edges and would newly report a tap
    // exactly on the IME's right or bottom edge as occluded.
    val contains = imeBounds.left < imeBounds.right && imeBounds.top < imeBounds.bottom &&
      x >= imeBounds.left && x < imeBounds.right && y >= imeBounds.top && y < imeBounds.bottom
    return if (contains) "window bounds $imeBounds" else null
  }
  return if (imeShownAuthoritative) {
    "dumpsys reports IME shown but window bounds unavailable (windows enumeration degraded)"
  } else {
    null
  }
}

/**
 * A node counts as an overlay only if it carries something a user could see or touch. Dividers,
 * spacers and layout chrome overlap constantly and never absorb a tap.
 *
 * Deliberately does NOT consult `isVisibleToUser`: the motivating snackbar reports
 * `isVisibleToUser=false` while on-screen and absorbing the tap, so filtering on it would skip
 * the exact class of occluder this check exists to catch.
 */
private fun isContentBearing(node: TrailblazeNode): Boolean {
  val detail = node.driverDetail as? DriverNodeDetail.AndroidAccessibility ?: return false
  return !detail.text.isNullOrBlank() ||
    !detail.contentDescription.isNullOrBlank() ||
    detail.isClickable
}

/**
 * The target's own ancestors and descendants can overlap it by construction; never occluders.
 *
 * [target] must be the node as it appears in [root]. Passing the `filterImportantForAccessibility`
 * incarnation instead omits every descendant the filter dropped — a label-less clickable
 * click-catcher is exactly that shape, since the filter deliberately keeps only nodes carrying a
 * readable label — and that descendant then reads as an overlay covering its own parent.
 */
private fun relatedNodeIds(root: TrailblazeNode, target: TrailblazeNode): Set<Long> {
  val ids = target.aggregate().map { it.nodeId }.toMutableSet()
  fun walk(node: TrailblazeNode): Boolean {
    if (node.nodeId == target.nodeId) return true
    if (node.children.any { walk(it) }) {
      ids.add(node.nodeId)
      return true
    }
    return false
  }
  walk(root)
  return ids
}

private fun describeOccluder(node: TrailblazeNode): String {
  val detail = node.driverDetail as? DriverNodeDetail.AndroidAccessibility
  val label = detail?.text?.takeIf { it.isNotBlank() }
    ?: detail?.contentDescription?.takeIf { it.isNotBlank() }
  return buildString {
    append(detail?.className ?: "<no-class>")
    if (label != null) append(" \"$label\"")
    append(" at ${node.bounds}")
    // Surfaced because it is counter-intuitive and load-bearing: this node is reported as an
    // occluder *despite* claiming to be invisible, which is exactly the snackbar shape.
    if (detail?.isVisibleToUser == false) append(" [isVisibleToUser=false]")
  }
}
