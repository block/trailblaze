package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext

/** Base interface for selector search strategies. */
internal interface SelectorStrategy {
  val name: String

  /**
   * Whether this strategy is performant enough for UI/interactive use.
   * Performant strategies complete quickly (typically <50ms) even on large hierarchies.
   * Non-performant strategies may require expensive multi-level tree traversals.
   */
  val isPerformant: Boolean get() = false

  fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector?
  fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector>

  /**
   * Finds all selectors with contextual descriptions about how they were found.
   * Default implementation wraps findAll() results with the static strategy name.
   * Strategies can override this to provide more detailed context.
   */
  fun findAllWithContext(context: SelectorSearchContext): List<SelectorWithContext> = findAll(context).map { SelectorWithContext(it, name) }
}
