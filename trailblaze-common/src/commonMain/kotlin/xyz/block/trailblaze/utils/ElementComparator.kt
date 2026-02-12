package xyz.block.trailblaze.utils

import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool

interface ElementComparator {
  /**
   * Gets the value of an element based on a prompt description.
   */
  fun getElementValue(prompt: String): String?

  /**
   * Evaluates a statement about the UI and returns a boolean result with explanation.
   */
  fun evaluateBoolean(statement: String): BooleanAssertionTrailblazeTool

  /**
   * Evaluates a prompt and returns a descriptive string response with explanation.
   */
  fun evaluateString(query: String): StringEvaluationTrailblazeTool

  /**
   * Extracts a number from a string using regex.
   */
  fun extractNumberFromString(input: String): Double?
}
