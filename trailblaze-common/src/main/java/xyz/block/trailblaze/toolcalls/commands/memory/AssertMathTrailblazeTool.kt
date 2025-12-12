package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import net.objecthunter.exp4j.ExpressionBuilder
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.math.abs

@Serializable
@TrailblazeToolClass("assertMath")
@LLMDescription(
  """
This will calculate the result of an expression and compare it to the expected output value.
      """,
)
data class AssertMathTrailblazeTool(
  val expression: String,
  val expected: String,
) : MemoryTrailblazeTool {
  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult {
    // Process any dynamic extraction patterns like [[prompt]] in the expression
    val interpolatedExpression = processDynamicExtractions(expression, memory, elementComparator)

    try {
      val result = ExpressionBuilder(interpolatedExpression).build().evaluate()
      val expectedValue = expected.toDouble()

      if (abs(result - expectedValue) > 0.0001) {
        throw TrailblazeToolExecutionException(
          message = "Math assertion failed: Expression '$interpolatedExpression' evaluated to $result, expected $expectedValue",
          tool = this,
        )
      }
    } catch (e: TrailblazeException) { // Make sure to include "Math assertion failed" in all error cases
      throw e // Rethrow existing TrailblazeExceptions
    } catch (e: Exception) {
      throw TrailblazeToolExecutionException(
        message = "Math assertion failed: Error evaluating expression - ${e.message}",
        tool = this,
      )
    }
    return TrailblazeToolResult.Success
  }

  /**
   * Process expression string to extract values from UI using [[prompt]] syntax
   * Extracts the value for each prompt and replaces it in the expression
   *
   * @param expression The expression containing [[prompt]] patterns
   * @return The interpolated expression with actual values from UI
   */
  private fun processDynamicExtractions(
    expression: String,
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): String {
    println("Processing dynamic extractions in: $expression")

    var interpolatedExpression = expression

    // Define regex pattern for [[prompt]]
    val dynamicExtractPattern = Regex("\\[\\[([^\\]]+)\\]\\]")

    // Find all matches
    val matches = dynamicExtractPattern.findAll(interpolatedExpression)

    for (match in matches) {
      val fullMatch = match.value
      val prompt = match.groupValues[1]

      println("Found dynamic extraction pattern: $fullMatch with prompt: $prompt")

      // Extract the value using the prompt
      val extractedValue = elementComparator.getElementValue(prompt)
      if (extractedValue != null) {
        // Try to extract a number from the value
        val numberValue = elementComparator.extractNumberFromString(extractedValue)

        if (numberValue != null) {
          println("Extracted value $numberValue for prompt '$prompt'")

          // Replace the pattern with the extracted value
          interpolatedExpression = interpolatedExpression.replace(fullMatch, numberValue.toString())
        } else {
          println("Could not extract a number from: $extractedValue for prompt: $prompt")
          throw TrailblazeToolExecutionException(
            message = "Could not extract a numeric value for prompt: $prompt",
            tool = this,
          )
        }
      } else {
        println("Failed to find element for prompt: $prompt")
        throw TrailblazeToolExecutionException(
          message = "Failed to find element for prompt: $prompt",
          tool = this,
        )
      }
    }

    // Also process regular variable interpolation after dynamic extractions
    val finalExpression = memory.interpolateVariables(interpolatedExpression)
    println("Final interpolated expression: $finalExpression")

    return finalExpression
  }
}
