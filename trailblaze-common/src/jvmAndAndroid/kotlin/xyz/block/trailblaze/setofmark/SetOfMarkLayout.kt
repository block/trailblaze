package xyz.block.trailblaze.setofmark

import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter

/**
 * Geometry helpers shared between [HostCanvasSetOfMark] (AWT) and
 * [xyz.block.trailblaze.setofmark.android.AndroidCanvasSetOfMark]. The formulas here are
 * trivially small individually — but each had two identical copies before, and a future
 * aesthetic tweak on one side (e.g. switching to single-sided padding, or top-right
 * placement) would silently diverge from the other.
 *
 * What's NOT here, intentionally:
 * - Drawing primitives (`Canvas.drawRect`, `Graphics2D.fillRect`, font rendering, stroke
 *   setup) — these are platform-API-specific and can't be shared.
 * - Host's non-default placement candidates (top-right / bottom-left / top-left for
 *   collision-avoidance in compact mode). Those are Host-only and stay in the Host file.
 * - Coordinate scaling for iOS/Web logical→physical points. Host-only concern; lives in
 *   `HostCanvasSetOfMark.scaleBoundsForPlatform`.
 */
object SetOfMarkLayout {

  /**
   * The default label rect: anchor the label box to the **bottom-right** corner of the
   * element's bounds. This is the historical default for both canvases — Host only
   * deviates in compact mode (which tries top-right / bottom-left / top-left for
   * collision avoidance), Android always uses bottom-right.
   *
   * Returned as [ViewHierarchyFilter.Bounds] (the shared bounds type). Android callers
   * wrap into `android.graphics.Rect` at the draw site; Host callers consume directly.
   *
   * No clamping to the canvas — the element's bounds are the caller's responsibility
   * to validate. (Host's compact-mode path applies its own `clampToCanvas` after the
   * collision-avoidance pick; Android's bounds are bitmap-clipped by the caller upstream
   * before reaching here.)
   */
  fun bottomRightLabelRect(
    elementBounds: ViewHierarchyFilter.Bounds,
    boxWidth: Int,
    boxHeight: Int,
  ): ViewHierarchyFilter.Bounds = ViewHierarchyFilter.Bounds(
    x1 = elementBounds.x2 - boxWidth,
    y1 = elementBounds.y2 - boxHeight,
    x2 = elementBounds.x2,
    y2 = elementBounds.y2,
  )
}
