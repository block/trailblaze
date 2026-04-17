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

  /** Checks if an element is outside the visible screen area (vertically or horizontally). */
  fun isOffscreen(node: TrailblazeNode, screenHeight: Int, screenWidth: Int = 0): Boolean {
    if (screenHeight <= 0) return false
    val b = node.bounds ?: return false
    if (b.top >= screenHeight || b.bottom <= 0) return true
    if (screenWidth > 0 && (b.left >= screenWidth || b.right <= 0)) return true
    return false
  }
}
