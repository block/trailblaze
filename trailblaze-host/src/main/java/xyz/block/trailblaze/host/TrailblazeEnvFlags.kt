package xyz.block.trailblaze.host

/**
 * Shared truthiness parser for Trailblaze's boolean env-var / config toggles: a value is "on" only
 * when it's exactly `"1"` or `"true"` (case-insensitive), the convention every `TRAILBLAZE_*` flag
 * follows. Extracted so the gate resolvers ([StreamScreenshotMode],
 * [xyz.block.trailblaze.host.capture.IosBaguetteVideoGate]) share one definition instead of each
 * re-deriving the truthy set.
 */
internal fun String?.isTrailblazeFlagEnabled(): Boolean =
  this != null && (this == "1" || this.equals("true", ignoreCase = true))
