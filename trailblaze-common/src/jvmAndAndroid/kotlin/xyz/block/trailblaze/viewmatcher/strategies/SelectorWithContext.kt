package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector

/**
 * Result from a strategy search that includes contextual information.
 * @property selector The element selector
 * @property description Contextual description (e.g., "Target properties (via ancestor 2 levels up)")
 */
internal data class SelectorWithContext(
  val selector: TrailblazeElementSelector,
  val description: String,
)
