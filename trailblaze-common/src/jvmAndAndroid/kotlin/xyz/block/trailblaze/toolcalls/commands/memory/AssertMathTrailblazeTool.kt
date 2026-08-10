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
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("assertMath", isVerification = true)
@LLMDescription(
  """
Calculate the result of an expression and compare it to the expected output value.
      """,
)
data class AssertMathTrailblazeTool(
  val expression: String,
  val expected: String,
  /**
   * Optional bound (milliseconds) that turns the assertion into a poll. When null (the default),
   * the expression is evaluated EXACTLY ONCE against the current screen — byte-for-byte the
   * pre-poll behavior, so every recorded assertMath keeps its single-shot semantics untouched.
   *
   * When set, the expression is re-evaluated on [pollIntervalMs] intervals until it passes OR this
   * timeout elapses; on timeout the same "Math assertion failed" exception the single-shot path
   * throws is surfaced. Reach for this only when a `[[prompt]]` read is subject to eventual
   * consistency (a value the app back-fills a beat after the action) — it bounds WHEN the read is
   * taken, never WHAT is asserted. Each retry re-reads through [ElementComparator.getElementValue],
   * which captures the screen afresh.
   *
   * LIMITATION: a fresh capture is not a fresh fetch. This re-reads the rendered screen, not the
   * underlying data, so it can only observe values the app itself updates in place. For a view the
   * app populates once and refreshes only on navigation, every attempt re-reads the same stale
   * number and no bound helps — polling cannot substitute for re-navigating. Verified on
   * `case_4839582`'s House Accounts balance: 15 fresh captures across 58.7s all returned the
   * pre-charge value, and shorter bounds fail identically; passing legs satisfied the assertion on
   * their first attempt.
   */
  val timeoutMs: Long? = null,
  /**
   * Interval (milliseconds) between re-evaluations while polling. Ignored when [timeoutMs] is null
   * (single-shot). Each attempt drives one fresh screen read per `[[prompt]]`, so keep it coarse
   * enough that the poll doesn't hammer the element-read path.
   */
  val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : MemoryTrailblazeTool {

  init {
    // Only constrain the poll knobs when the caller opts into polling; single-shot calls never
    // read pollIntervalMs, so it must not gate construction of the legacy single-shot form.
    if (timeoutMs != null) {
      require(timeoutMs > 0) { "assertMath.timeoutMs must be > 0 when set (got $timeoutMs)" }
      require(pollIntervalMs > 0) { "assertMath.pollIntervalMs must be > 0 (got $pollIntervalMs)" }
    }
  }

  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult = executeWithClock(
    elementComparator = elementComparator,
    now = { System.currentTimeMillis() },
    sleep = { Thread.sleep(it) },
  )

  /**
   * Clock/sleep-injected core so the poll's timeout boundary is unit-testable without real waits.
   * A null [timeoutMs] runs [evaluateOnce] exactly once (unchanged single-shot behavior); a set
   * [timeoutMs] re-runs [evaluateOnce] each attempt — and because that path re-reads via
   * [ElementComparator.getElementValue] (a fresh screen capture per call), every retry observes the
   * current screen instead of a cached snapshot. What the current screen *shows* is the app's
   * business; see the LIMITATION on [timeoutMs].
   */
  internal fun executeWithClock(
    elementComparator: ElementComparator,
    now: () -> Long,
    sleep: (Long) -> Unit,
  ): TrailblazeToolResult {
    val bound = timeoutMs ?: return evaluateOnce(elementComparator)

    val deadline = now() + bound
    while (true) {
      try {
        return evaluateOnce(elementComparator)
      } catch (e: TrailblazeException) {
        val remaining = deadline - now()
        // Deadline reached — surface the last attempt's failure so the "Math assertion failed"
        // message (and its negative-control marker) is identical to the single-shot path.
        if (remaining <= 0) throw e
        sleep(minOf(pollIntervalMs, remaining))
      }
    }
  }

  /**
   * One evaluation against the current screen: reads each `[[prompt]]` via
   * [ElementComparator.getElementValue] (fresh capture), evaluates, and throws
   * [TrailblazeToolExecutionException] on mismatch or read failure. The poll retries on that throw;
   * the single-shot path lets it propagate as before. ({{var}}/${var} tokens are already resolved
   * by the dispatch boundary before execute() runs, so `expression` carries only [[prompt]]s here.)
   */
  private fun evaluateOnce(elementComparator: ElementComparator): TrailblazeToolResult {
    val interpolatedExpression = processDynamicExtractions(expression, elementComparator)

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
    return TrailblazeToolResult.Success()
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
    elementComparator: ElementComparator,
  ): String {
    Console.log("Processing dynamic extractions in: $expression")

    var interpolatedExpression = expression

    // Define regex pattern for [[prompt]]
    val dynamicExtractPattern = Regex("\\[\\[([^\\]]+)\\]\\]")

    // Find all matches
    val matches = dynamicExtractPattern.findAll(interpolatedExpression)

    for (match in matches) {
      val fullMatch = match.value
      val prompt = match.groupValues[1]

      Console.log("Found dynamic extraction pattern: $fullMatch with prompt: $prompt")

      // Extract the value using the prompt
      val extractedValue = elementComparator.getElementValue(prompt)
      if (extractedValue != null) {
        // Try to extract a number from the value
        val numberValue = elementComparator.extractNumberFromString(extractedValue)

        if (numberValue != null) {
          Console.log("Extracted value $numberValue for prompt '$prompt'")

          // Replace the pattern with the extracted value
          interpolatedExpression = interpolatedExpression.replace(fullMatch, numberValue.toString())
        } else {
          Console.log("Could not extract a number from: $extractedValue for prompt: $prompt")
          throw TrailblazeToolExecutionException(
            message = "Could not extract a numeric value for prompt: $prompt",
            tool = this,
          )
        }
      } else {
        Console.log("Failed to find element for prompt: $prompt")
        throw TrailblazeToolExecutionException(
          message = "Failed to find element for prompt: $prompt",
          tool = this,
        )
      }
    }

    Console.log("Final interpolated expression: $interpolatedExpression")

    return interpolatedExpression
  }

  companion object {
    /** Default interval between re-evaluations once [timeoutMs] opts into polling. */
    const val DEFAULT_POLL_INTERVAL_MS: Long = 1_000L
  }
}
