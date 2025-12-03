package xyz.block.trailblaze.viewmatcher.models

import xyz.block.trailblaze.api.TrailblazeElementSelector

/**
 * Represents a selector option with its strategy.
 *
 * @property selector The element selector that uniquely identifies the target
 * @property strategy Human-readable description of the strategy used
 * @property isSimplified True if this is a simplified variant with fewer properties
 */
data class ElementSelectorOption(
  val selector: TrailblazeElementSelector,
  val strategy: String,
  val isSimplified: Boolean = false,
)
