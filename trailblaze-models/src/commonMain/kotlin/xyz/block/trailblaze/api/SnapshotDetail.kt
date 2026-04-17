package xyz.block.trailblaze.api

/**
 * Detail levels for screen snapshots, applicable across all platforms.
 *
 * By default, snapshots use a compact format optimized for token efficiency.
 * These options progressively enrich the output when more information is needed.
 *
 * Mirrors the progressive disclosure pattern from web/Playwright but works
 * across Android, iOS, and web.
 */
enum class SnapshotDetail {
  /**
   * Include bounding box coordinates for each element.
   * Adds `{x,y,w,h}` after each element's descriptor.
   *
   * Useful for spatial reasoning, determining element positions, checking
   * viewport visibility, or disambiguating visually similar elements by location.
   */
  BOUNDS,

  /**
   * Include all elements regardless of viewport position.
   * Elements outside the visible area are annotated with `(offscreen)`.
   *
   * Useful for finding elements that require scrolling to reach.
   */
  OFFSCREEN,
}
