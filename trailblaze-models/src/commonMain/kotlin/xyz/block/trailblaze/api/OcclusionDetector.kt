package xyz.block.trailblaze.api

/**
 * Platform-agnostic occlusion + viewport-visibility detector.
 *
 * Decides per candidate whether it is offscreen, occluded by an unrelated opaque
 * paint region, or visible — given a paint stack the supplier produces by
 * hit-testing at the candidate's center.
 *
 * ## What lives here (general)
 *
 * Everything in this file is platform-neutral: zero-area + viewport checks,
 * "topmost-opaque-at-center" pick, and the self/ancestor/descendant ancestry
 * rule that decides whether a candidate's own paint is on top vs. an unrelated
 * occluder. **Changes to this file affect every platform that uses
 * [OcclusionDetector] — any modification MUST keep `OcclusionDetectorTest`
 * (the algorithm contract) and every platform's end-to-end integration test
 * green.**
 *
 * ## What lives per-platform (supplier-side)
 *
 * Each platform owns its own "supplier" — the code that converts native
 * primitives into [VisibilityInput]s and calls [detect]. Specifically, the
 * supplier must produce a paint stack at each candidate's center: the list
 * of opaque elements painted at that point, **topmost first**.
 *
 * Every modern UI toolkit exposes hit-testing as a primitive — let it do the
 * stacking-context math for you rather than re-deriving it. Concretely:
 *
 * | Concept                  | Web (Playwright)                                                      | Android                                      | iOS                                          | Compose                                      |
 * |--------------------------|-----------------------------------------------------------------------|----------------------------------------------|----------------------------------------------|----------------------------------------------|
 * | Bounds source            | `Element.getBoundingClientRect()`                                     | `AccessibilityNodeInfo.getBoundsInScreen()`  | `XCUIElement.frame`                          | `SemanticsNode.boundsInWindow`               |
 * | Paint stack at point     | `document.elementsFromPoint(cx, cy)` (browser-native, paint-ordered)  | `View.dispatchHoverEvent` / hit-test walk    | `UIView.hitTest:withEvent:` + superview walk | `SemanticsOwner.hitTest` + composition walk  |
 * | Opacity predicate (filter the stack)        | non-transparent bg / bg-image / border / replaced content tag  | `View.isOpaque` + non-null background drawable | `UIView.isOpaque` + non-clear backgroundColor | `Modifier.background` chain inspection       |
 * | [VisibilityCandidate.ancestorIds] | DOM `parentElement` chain                                    | `View.getParent()` chain                     | `UIView.superview` chain                     | layout-parent chain                          |
 *
 * **Web-specific note**: `document.elementsFromPoint` skips elements with
 * `pointer-events: none`. To catch popups that use `pointer-events: none` on
 * a wrapper for click-through (e.g., Square Dashboard's Managerbot toast),
 * suppliers should inject a temporary stylesheet rule
 * (`* { pointer-events: auto !important }`) for the duration of the
 * hit-tests, then remove it in a finally block within the same synchronous
 * evaluation. The other platforms don't need this dance.
 *
 * ## Algorithm
 *
 * For each [VisibilityInput]:
 * 1. If candidate's `bounds.width <= 0` or `bounds.height <= 0` → **offscreen**
 *    (zero-area, no paintable surface).
 * 2. Else if `bounds.center` is outside `viewport` → **offscreen**.
 * 3. Else if [VisibilityInput.paintStackAtCenter] is empty → **visible (unknown)**
 *    — the supplier had no signal (e.g., canvas-rendered page where the
 *    browser's hit-test returns only `<canvas>`). Conservative default is to
 *    NOT filter.
 * 4. Else take the topmost entry in the paint stack. If it's the candidate
 *    itself, an ancestor, or a descendant of the candidate → **visible**
 *    (the candidate's own paint or its background card is what shows; not an
 *    occluder). Otherwise → **occluded**.
 *
 * ## Known cross-platform limitation
 *
 * Popups in a **separate window outside the host app's accessibility tree** —
 * Android system toasts, iOS alert dialogs over the app, web cross-origin
 * iframes — never surface via the supplier's hit-test. The algorithm cannot
 * catch those occluders; a pixel-diff fallback is the only known workaround.
 * Out of scope here; tracked as future work.
 */
object OcclusionDetector {

  /**
   * Classifies each [VisibilityInput] as offscreen, occluded, or visible/unknown.
   *
   * Candidates whose verdict is "visible" or "unknown" are omitted from both
   * result sets — callers should treat absence as the conservative default
   * (don't filter the element).
   */
  fun detect(
    inputs: List<VisibilityInput>,
    viewport: TrailblazeNode.Bounds,
  ): VisibilityResult {
    if (inputs.isEmpty()) {
      return VisibilityResult(emptySet(), emptySet())
    }

    val offscreen = mutableSetOf<String>()
    val occluded = mutableSetOf<String>()

    for (input in inputs) {
      val el = input.candidate

      // Zero/negative-area candidates have no paintable surface and aren't
      // user-visible regardless of viewport position. Without this short-circuit
      // a `(x=100, y=100, w=0, h=0)` candidate's center is still (100, 100), so
      // the viewport check would say "in viewport" and downstream consumers
      // would keep the element in the LLM-facing text.
      if (el.bounds.width <= 0 || el.bounds.height <= 0) {
        offscreen.add(el.id)
        continue
      }

      val cx = el.bounds.centerX
      val cy = el.bounds.centerY

      if (!viewport.containsPoint(cx, cy)) {
        offscreen.add(el.id)
        continue
      }

      // Empty paint stack = unknown verdict (supplier couldn't determine).
      // Fail open — leave the candidate unfiltered.
      val topmost = input.paintStackAtCenter.firstOrNull() ?: continue

      val isSelf = topmost.id == el.id
      val isAncestorOfEl = topmost.id in el.ancestorIds
      val isDescendantOfEl = el.id in topmost.ancestorIds
      if (!(isSelf || isAncestorOfEl || isDescendantOfEl)) {
        occluded.add(el.id)
      }
    }

    return VisibilityResult(offscreen = offscreen, occluded = occluded)
  }
}

/**
 * One element under test for visibility.
 *
 * The `id` must be stable for this snapshot and unique among candidates; it's
 * what shows up in [VisibilityResult] and what platforms map back to their
 * native element handles.
 *
 * [ancestorIds] should include the IDs of every region — candidate OR
 * non-candidate opaque region — that visually encloses this candidate in the
 * paint tree. The algorithm uses it to recognize "the candidate is painted
 * inside an opaque ancestor" (e.g., link sitting on an opaque card background)
 * as a visible case, not an occluded one. Platform suppliers populate this by
 * walking their native parent chain (DOM `parentElement`, Android `View.getParent`,
 * iOS `UIView.superview`, Compose layout-parent).
 */
data class VisibilityCandidate(
  val id: String,
  val bounds: TrailblazeNode.Bounds,
  val ancestorIds: List<String> = emptyList(),
)

/**
 * One opaque entry in a paint stack — an element that actually paints visible
 * pixels at the candidate's center.
 *
 * If this region's underlying element is also a [VisibilityCandidate], use the
 * candidate's `id` here so the algorithm's "topmost is candidate itself" check
 * matches. Otherwise use any stable string distinct from candidate IDs (web
 * supplier uses `__d<N>` for non-candidate paint elements; candidate IDs are
 * `e<N>`, so the namespaces are statically disjoint).
 *
 * [ancestorIds] should include the IDs of every region that visually encloses
 * this one — so the algorithm can recognize the case where an opaque region is
 * a **descendant** of the candidate (e.g., the candidate is a button, the opaque
 * region is the button's own icon `<span>` / `ImageView` / `UIImageView`).
 */
data class OpaqueRegion(
  val id: String,
  val ancestorIds: List<String> = emptyList(),
)

/**
 * Per-candidate input to [OcclusionDetector.detect].
 *
 * The supplier produces one of these per candidate. [paintStackAtCenter] is
 * the list of opaque elements painted at `candidate.bounds.center`, **topmost
 * first** (i.e., the first entry is what the user actually sees at that point).
 * The supplier sources it from a native hit-test (`elementsFromPoint`,
 * `UIView.hitTest:`, `SemanticsOwner.hitTest`, etc.) filtered to entries with
 * a non-transparent paint.
 *
 * Pass an empty stack to signal "unknown" — the algorithm fail-opens and
 * leaves the candidate unfiltered. Use this for canvas-rendered pages and
 * any case where the supplier's hit-test isn't a reliable signal.
 */
data class VisibilityInput(
  val candidate: VisibilityCandidate,
  val paintStackAtCenter: List<OpaqueRegion> = emptyList(),
)

/**
 * Output of [OcclusionDetector.detect].
 *
 * Candidates not present in either set are visible (or unknown — the algorithm
 * couldn't determine, and the conservative call is not to filter them).
 */
data class VisibilityResult(
  val offscreen: Set<String>,
  val occluded: Set<String>,
) {
  companion object {
    val EMPTY = VisibilityResult(emptySet(), emptySet())
  }
}
