package xyz.block.trailblaze.utils

import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool

/**
 * Lightweight comparator for execution paths that need a comparator object but are not expected
 * to do semantic screen comparisons.
 */
object NoOpElementComparator : ElementComparator {
  override fun getElementValue(prompt: String): String? = null

  override fun evaluateBoolean(statement: String): BooleanAssertionTrailblazeTool =
    BooleanAssertionTrailblazeTool(
      reason = "Element comparison is unavailable in this execution path.",
      result = false,
    )

  override fun evaluateString(query: String): StringEvaluationTrailblazeTool =
    StringEvaluationTrailblazeTool(
      reason = "Element comparison is unavailable in this execution path.",
      result = "",
    )

  override fun extractNumberFromString(input: String): Double? = null
}
