package xyz.block.trailblaze.viewmatcher.matching

/**
 * Utilities for comparing center points with tolerance.
 *
 * This accounts for rounding differences in how center points are calculated
 * across different platforms and view hierarchies.
 */
object CenterPointMatcher {
  /**
   * Default tolerance (in pixels) for comparing center points.
   * This accounts for rounding differences in how center points are calculated.
   */
  const val DEFAULT_TOLERANCE_PX = 5

  /**
   * Compares two center point strings with tolerance.
   *
   * @param centerPoint1 First center point string in format "x,y" (e.g., "540,1602")
   * @param centerPoint2 Second center point string in format "x,y"
   * @param tolerancePx Tolerance in pixels (default: 1px)
   * @return true if the center points are within tolerance, false otherwise
   */
  fun centerPointsMatch(
    centerPoint1: String?,
    centerPoint2: String?,
    tolerancePx: Int = DEFAULT_TOLERANCE_PX,
  ): Boolean {
    if (centerPoint1 == null || centerPoint2 == null) {
      return centerPoint1 == centerPoint2
    }

    // Fast path: exact match
    if (centerPoint1 == centerPoint2) {
      return true
    }

    // Parse center points
    val parts1 = centerPoint1.split(",")
    val parts2 = centerPoint2.split(",")

    if (parts1.size != 2 || parts2.size != 2) {
      return false
    }

    val x1 = parts1[0].toIntOrNull() ?: return false
    val y1 = parts1[1].toIntOrNull() ?: return false
    val x2 = parts2[0].toIntOrNull() ?: return false
    val y2 = parts2[1].toIntOrNull() ?: return false

    // Check if within tolerance
    return kotlin.math.abs(x1 - x2) <= tolerancePx && kotlin.math.abs(y1 - y2) <= tolerancePx
  }
}
