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

  /**
   * Include all visible elements, bypassing the "meaningful" filter.
   *
   * By default, snapshots only show interactive or content-bearing elements
   * (clickable, editable, focused, labeled, etc.). This option disables that
   * filter so every visible node in the accessibility tree is shown, even if
   * it has no text, no accessibility label, or isn't marked interactive.
   *
   * Useful for debugging missing elements — e.g., text fields that the OS
   * accessibility framework doesn't mark as editable.
   */
  ALL_ELEMENTS,
}
