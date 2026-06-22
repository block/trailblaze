package xyz.block.trailblaze.api

/**
 * Shared utility functions used by both [AndroidCompactElementList] and [IosCompactElementList].
 *
 * These are platform-agnostic functions that operate on [TrailblazeNode] and its bounds,
 * extracted to avoid duplication across the per-platform compact list builders.
 */
internal object CompactElementListUtils {

  /** Formats bounds as `{x,y,w,h}` annotation. */
  fun boundsAnnotation(node: TrailblazeNode): String {
    val b = node.bounds ?: return ""
    return " {x:${b.left},y:${b.top},w:${b.width},h:${b.height}}"
  }

  /** Inverted bounds (right<left or bottom<top) signal a Compose `graphicsLayer` transform that
   * `boundsInRoot` excludes by design; keep the node visible but treat coords as unreliable. */
  fun hasInvertedBounds(node: TrailblazeNode): Boolean {
    val b = node.bounds ?: return false
    return b.right < b.left || b.bottom < b.top
  }

  /** Checks if an element is outside the visible screen area (vertically or horizontally). */
  fun isOffscreen(node: TrailblazeNode, screenHeight: Int, screenWidth: Int = 0): Boolean {
    if (screenHeight <= 0) return false
    val b = node.bounds ?: return false
    // graphicsLayer transforms can invert one axis (right<left / bottom<top) and boundsInRoot omits
    // them, so check offscreen only on each non-inverted axis — a genuinely offscreen node still trips.
    if (b.bottom >= b.top && (b.top >= screenHeight || b.bottom <= 0)) return true
    if (screenWidth > 0 && b.right >= b.left && (b.left >= screenWidth || b.right <= 0)) return true
    return false
  }
}
