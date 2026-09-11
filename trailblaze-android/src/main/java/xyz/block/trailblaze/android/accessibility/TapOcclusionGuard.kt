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
 * The tallest share of the screen a soft IME is assumed to be able to occupy, used only when the
 * keyboard is known to be up but cannot be measured, and only on a taller-than-wide screen (see
 * [highestPossibleImeTop]).
 *
 * A soft input window is bottom-docked by construction (`gr=BOTTOM` on its `WindowManager
 * .LayoutParams`), so a point far enough above the bottom edge cannot be under it no matter how
 * large the keyboard is. This is a deliberately loose upper bound rather than a measurement: the
 * tallest keyboard observed while writing this was 47% of screen height, and 60 leaves headroom.
 *
 * A percentage rather than a fraction so the cutoff is integer pixel arithmetic; the boundary it
 * produces is pinned by test rather than left to how a `Double` rounds.
 *
 * Two known ways this bound can still be wrong, both accepted:
 * - A **floating or split** IME is not bottom-docked and can sit well above the cutoff, so a tap
 *   under it would be cleared. Same direction as the trade below, and far rarer than the
 *   false positive this exists to fix.
 * - It reads the screen, not the window. In split-screen the app's window is smaller than what
 *   [highestPossibleImeTop] measures, which makes the cutoff too low — the conservative direction.
 *
 * Do NOT try to tighten this from `dumpsys window windows`. That output does report the IME
 * window, but while the keyboard is down its `mFrame` covers the whole screen and its
 * `mGivenContentInsets` collapse to near-zero — measured [0,2340] on a 2400px-tall phone — so a
 * parse of either would be less accurate than this bound, not more.
 */
private const val MAX_PLAUSIBLE_IME_HEIGHT_PERCENT = 60

/**
 * The highest `y` a soft IME could plausibly reach, or null when the bound cannot be applied at
 * all — the size is unknown, non-positive, or the screen is not taller than it is wide.
 *
 * Returning the cutoff rather than a boolean is deliberate: it keeps the "we have a usable size"
 * invariant and the arithmetic that depends on it in one place, so no caller needs a `!!`.
 *
 * **Taller-than-wide, not "portrait".** This is a geometric claim about where a bottom-docked
 * window can reach, and it is deliberately NOT
 * `AndroidTrailblazeDeviceInfoUtil.getDeviceOrientation()` — that classifier reports `PORTRAIT`
 * for a *tablet* whose width exceeds its height, which is the opposite of the shape this needs.
 *
 * A wider-than-tall screen gets no relaxation because an IME there may enter **fullscreen extract
 * mode** and cover the whole screen instead of docking to the bottom:
 * `InputMethodService.onEvaluateFullscreenMode` returns true by default when the display is
 * short. A fullscreen IME occludes every coordinate, so relaxing the bound there would clear taps
 * that really are under the keyboard — on landscape-only hardware, every one of them.
 *
 * Whether a given IME is *actually* fullscreen is not observable from this process (an app can
 * suppress it with `IME_FLAG_NO_FULLSCREEN`, and `isFullscreenMode` lives in the IME's own
 * process), so this keeps the fully conservative answer rather than guessing. That leaves the
 * false positive the bound exists to fix unaddressed on those screens — deliberately, because the
 * conservative answer is the pre-existing behavior, and refusing a reachable tap is a much
 * cheaper failure than dispatching one into the keyboard.
 */
private fun highestPossibleImeTop(screenWidth: Int?, screenHeight: Int?): Int? {
  if (screenWidth == null || screenHeight == null) return null
  if (screenWidth <= 0 || screenHeight <= 0) return null
  if (screenHeight <= screenWidth) return null
  return screenHeight - (screenHeight * MAX_PLAUSIBLE_IME_HEIGHT_PERCENT / 100)
}

/**
 * Returns a description of the IME occlusion signal when ([x], [y]) is covered by the soft
 * keyboard, or null when the point is clear.
 *
 * [imeBounds] is the IME window's screen bounds when accessibility window enumeration is
 * healthy. When it is null the enumeration is degraded (some accessibility-service flag
 * combinations leak null window lists) and [imeShownAuthoritative] — the dumpsys answer — is all
 * we have: the IME is up but unmeasurable.
 *
 * In that unmeasurable case the point is cleared only when it sits above
 * [highestPossibleImeTop] — and only on a taller-than-wide screen, which is where a bottom-docked
 * keyboard is the only shape it can take. Reporting occlusion at *every* coordinate instead —
 * which is what this did before the screen size was passed in — fails taps the keyboard provably
 * cannot reach: it rejected a tap at y=200 on a 2400px-tall phone as "under the keyboard".
 *
 * An off-screen `y` (negative) is never cleared. It cannot be reasoned about geometrically, and
 * the conservative answer is the pre-existing one.
 *
 * [screenWidth] and [screenHeight] are null when the display size could not be read; that keeps
 * the original unconditional behavior. See [highestPossibleImeTop] for the shapes it declines.
 */
internal fun imeOcclusionSignal(
  imeBounds: TrailblazeNode.Bounds?,
  imeShownAuthoritative: Boolean,
  x: Int,
  y: Int,
  screenWidth: Int? = null,
  screenHeight: Int? = null,
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
  if (!imeShownAuthoritative) return null
  val imeTop = highestPossibleImeTop(screenWidth, screenHeight)
  if (imeTop != null && y in 0 until imeTop) return null
  return "dumpsys reports IME shown but window bounds unavailable (windows enumeration degraded)"
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
