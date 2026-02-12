package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext

/** Base interface for selector search strategies. */
internal class TracingSelectorStrategy(
  private val delegate: SelectorStrategy,
) : SelectorStrategy by delegate {

  companion object {
    private const val SELECTOR_STRATEGY_TRACING_ENABLED = false
  }

  /** Wraps the execution of the block with tracing. */
  private inline fun <T> trace(name: String, block: () -> T): T = if (SELECTOR_STRATEGY_TRACING_ENABLED) {
    traceRecorder.trace(
      name = "${delegate::class.simpleName}::$name",
      cat = "SelectorStrategy",
      args = emptyMap(),
      block = block,
    )
  } else {
    block()
  }

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? = trace("findFirst") {
    delegate.findFirst(context)
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> = trace("findAll") {
    delegate.findAll(context)
  }

  /**
   * Finds all selectors with contextual descriptions about how they were found.
   * Default implementation wraps findAll() results with the static strategy name.
   * Strategies can override this to provide more detailed context.
   */
  override fun findAllWithContext(context: SelectorSearchContext): List<SelectorWithContext> = trace("findAllWithContext") {
    delegate.findAllWithContext(context)
  }
}
