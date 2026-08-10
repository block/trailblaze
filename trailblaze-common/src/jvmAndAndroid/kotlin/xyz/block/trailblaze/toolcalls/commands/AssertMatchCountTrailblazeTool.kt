package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass(
  name = "assertMatchCount",
  surfaceToLlm = false,
  isVerification = true,
)
@LLMDescription(
  "Asserts that the NUMBER of elements matching a selector satisfies a cardinality bound " +
    "(at least `min`, exactly `exact`, and/or at most `max`).",
)
/**
 * ----- DO NOT GIVE THIS TOOL TO THE LLM -----
 *
 * The counting counterpart to [AssertVisibleBySelectorTrailblazeTool] (which is presence-only —
 * it passes on any number of matches ≥ 1). Where `assertVisibleBySelector` answers "is at least
 * one X on screen?", this answers "are there at least / exactly / at most N of them?" — e.g. "the
 * report lists ≥1 item row". It resolves the same [TrailblazeNodeSelector] through the same
 * [TrailblazeNodeSelectorResolver] the visible-assert uses, counts the matches, and compares that
 * count to the cardinality bound. Fully deterministic and 0-LLM, so it replays on the
 * recording-only device leg where the AI-backed tools can't.
 */
data class AssertMatchCountTrailblazeTool(
  val reason: String? = null,
  /** Selector whose live matches are counted. Required — [execute] enforces non-null. */
  val nodeSelector: TrailblazeNodeSelector? = null,
  /** Lower bound: the count must be ≥ this. */
  val min: Int? = null,
  /** Exact bound: the count must equal this. Overrides [min] / [max] when set. */
  val exact: Int? = null,
  /** Upper bound: the count must be ≤ this. */
  val max: Int? = null,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val selector = nodeSelector
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "assertMatchCount requires `nodeSelector` to be non-null.",
      )
    if (min == null && exact == null && max == null) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "assertMatchCount requires at least one of `min`, `exact`, or `max`.",
      )
    }
    validateBounds(min, exact, max)?.let { boundsError ->
      return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = boundsError)
    }

    val screenState = toolExecutionContext.screenStateProvider?.invoke()
      ?: toolExecutionContext.screenState
    val tree = screenState?.trailblazeNodeTree
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "assertMatchCount: current driver does not produce a TrailblazeNode tree " +
          "(platform=${screenState?.trailblazeDevicePlatform?.name ?: "unknown"}). " +
          "The selector cannot be resolved.",
      )

    val templateContext = toolExecutionContext.resolvedTarget?.let { resolved ->
      TargetTemplateContext(appId = toolExecutionContext.appId, appIds = resolved.appIds)
    }
    val count = countMatches(tree, selector, templateContext)
    val expectation = describeExpectation(min, exact, max)
    val desc = selector.description()
    return if (cardinalitySatisfied(count, min, exact, max)) {
      TrailblazeToolResult.Success(message = "Verified $expectation matching '$desc' (found $count)")
    } else {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "assertMatchCount: expected $expectation matching '$desc', found $count",
      )
    }
  }

  companion object {
    /**
     * Resolves [selector] against [tree] and returns how many nodes matched. [target] expands
     * `{{target.appId}}` placeholders in the selector before resolving (via the same
     * [TrailblazeNodeSelectorResolver.resolve] template path the other selector tools use); a
     * null [target] leaves any placeholder literal, which is why a target-scoped assertion must
     * thread one through or it counts 0.
     */
    fun countMatches(
      tree: TrailblazeNode,
      selector: TrailblazeNodeSelector,
      target: TargetTemplateContext? = null,
    ): Int =
      when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector, target)) {
        is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> 0
        is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> 1
        is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes.size
      }

    /**
     * Validates the cardinality bounds up front, returning an error message for malformed input or
     * null when the bounds are coherent. `exact` overrides `min`/`max` (see [cardinalitySatisfied]),
     * so when it is present only `exact` is validated and any min/max are ignored dead values that
     * never reject the assertion. With no `exact`, a negative `min`/`max` (which would make the
     * check vacuously true) and an inverted `max < min` range are rejected.
     */
    fun validateBounds(min: Int?, exact: Int?, max: Int?): String? {
      if (exact != null) {
        return if (exact < 0) {
          "assertMatchCount: `exact` must be >= 0 (match counts are never negative), got $exact."
        } else {
          null
        }
      }
      val negative = when {
        min != null && min < 0 -> "min" to min
        max != null && max < 0 -> "max" to max
        else -> null
      }
      if (negative != null) {
        return "assertMatchCount: `${negative.first}` must be >= 0 (match counts are never " +
          "negative), got ${negative.second}."
      }
      if (min != null && max != null && max < min) {
        return "assertMatchCount: incoherent bounds: `max` ($max) is less than `min` ($min)."
      }
      return null
    }

    /**
     * True iff [count] satisfies the bounds. [exact] pins both ends; otherwise [min]/[max] are an
     * independent lower/upper bound and a null bound is unconstrained.
     */
    fun cardinalitySatisfied(count: Int, min: Int?, exact: Int?, max: Int?): Boolean {
      val lower = exact ?: min
      val upper = exact ?: max
      return (lower == null || count >= lower) && (upper == null || count <= upper)
    }

    /** Human-readable rendering of the bound, for the pass/fail message. */
    fun describeExpectation(min: Int?, exact: Int?, max: Int?): String = when {
      exact != null -> "exactly $exact"
      min != null && max != null -> "between $min and $max"
      min != null -> "at least $min"
      max != null -> "at most $max"
      else -> "any number of"
    }
  }
}
