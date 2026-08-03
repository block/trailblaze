package xyz.block.trailblaze.toolcalls.commands.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Pins the observable contract of `assertMath`'s opt-in timeout/retry: the single-shot default is
 * unchanged (exactly one screen read), and an opted-in `timeoutMs` re-reads the screen each attempt
 * — through the same [ElementComparator.getElementValue] seam that captures a fresh screen — until
 * the value reflects the change or the timeout elapses.
 *
 * `{{var}}` memory tokens are resolved at the dispatch boundary before `execute()`, so these tests
 * pass expressions whose non-`[[prompt]]` operand is already a literal (as it arrives at execute).
 */
class AssertMathTrailblazeToolTest {

  /**
   * Injected screen-read seam. Serves one value per successive [getElementValue] call from
   * [values], sticking on the last once exhausted. `getElementValue` is assertMath's only screen
   * read and re-invokes the live capture per call, so counting reads observes re-capture-vs-cache.
   */
  private class SequencedElementComparator(
    private val values: List<String?>,
  ) : ElementComparator {
    var readCount: Int = 0
      private set

    override fun getElementValue(prompt: String): String? {
      val value = values.getOrElse(readCount) { values.last() }
      readCount++
      return value
    }

    override fun extractNumberFromString(input: String): Double? =
      input.trim().replace(",", "").toDoubleOrNull()

    override fun evaluateBoolean(statement: String): BooleanAssertionTrailblazeTool =
      BooleanAssertionTrailblazeTool(reason = "unused in assertMath tests", result = false)

    override fun evaluateString(query: String): StringEvaluationTrailblazeTool =
      StringEvaluationTrailblazeTool(reason = "unused in assertMath tests", result = "")
  }

  // "10.0" makes the delta 0.0 (stale, fails); "10.01" makes it 0.01 (reflects the $0.01 charge).
  private val expression = "[[balance owed]] - 10.0"
  private val expected = "0.01"

  @Test
  fun `default single-shot evaluates exactly once and passes when the read already reflects`() {
    val comparator = SequencedElementComparator(listOf("10.01"))
    val result = AssertMathTrailblazeTool(expression = expression, expected = expected)
      .execute(AgentMemory(), comparator)

    assertTrue(result is TrailblazeToolResult.Success, "expected Success, got $result")
    assertEquals(1, comparator.readCount, "single-shot default must read the screen exactly once")
  }

  @Test
  fun `default single-shot does not retry - one evaluation then fail`() {
    val comparator = SequencedElementComparator(listOf("10.0"))
    val error = assertFailsWith<TrailblazeException> {
      AssertMathTrailblazeTool(expression = expression, expected = expected)
        .execute(AgentMemory(), comparator)
    }

    assertTrue(
      error.message?.contains("Math assertion failed") == true,
      "expected the unchanged failure message shape, got: ${error.message}",
    )
    assertEquals(1, comparator.readCount, "no timeoutMs must mean a single evaluation, no poll")
  }

  @Test
  fun `with timeoutMs, retries until the read reflects the charge then passes`() {
    // Stale on the first two reads, fresh on the third — a pass is only possible if each attempt
    // re-reads the screen rather than reusing the first (stale) extraction.
    val comparator = SequencedElementComparator(listOf("10.0", "10.0", "10.01"))
    val tool = AssertMathTrailblazeTool(
      expression = expression,
      expected = expected,
      timeoutMs = 10_000L,
      pollIntervalMs = 1_000L,
    )
    var clock = 0L
    val result = tool.executeWithClock(comparator, now = { clock }, sleep = { clock += it })

    assertTrue(result is TrailblazeToolResult.Success, "expected Success once fresh, got $result")
    assertTrue(
      comparator.readCount >= 3,
      "expected the poll to re-read the screen each attempt until it reflected the charge; " +
        "reads=${comparator.readCount}",
    )
  }

  @Test
  fun `each retry re-captures the screen rather than reusing the first read`() {
    // Stale first read, fresh second read. If the loop cached the first extraction instead of
    // re-reading, getElementValue would be called only once and the assertion could never pass.
    val comparator = SequencedElementComparator(listOf("10.0", "10.01"))
    val tool = AssertMathTrailblazeTool(
      expression = expression,
      expected = expected,
      timeoutMs = 10_000L,
      pollIntervalMs = 1_000L,
    )
    var clock = 0L
    val result = tool.executeWithClock(comparator, now = { clock }, sleep = { clock += it })

    assertTrue(result is TrailblazeToolResult.Success, "expected Success on the fresh read, got $result")
    assertEquals(
      2,
      comparator.readCount,
      "the second attempt must take a FRESH screen read (a cached first read would never pass)",
    )
  }

  @Test
  fun `with timeoutMs, fails with the unchanged message when the read never reflects`() {
    val comparator = SequencedElementComparator(listOf("10.0"))
    val tool = AssertMathTrailblazeTool(
      expression = expression,
      expected = expected,
      timeoutMs = 5_000L,
      pollIntervalMs = 1_000L,
    )
    var clock = 0L
    val error = assertFailsWith<TrailblazeException> {
      tool.executeWithClock(comparator, now = { clock }, sleep = { clock += it })
    }

    assertTrue(
      error.message?.contains("Math assertion failed") == true,
      "timeout must surface the same failure message shape, got: ${error.message}",
    )
    assertTrue(
      comparator.readCount > 1,
      "expected several poll attempts before the timeout, reads=${comparator.readCount}",
    )
  }

  @Test
  fun `non-positive timeoutMs fails construction`() {
    assertFailsWith<IllegalArgumentException> {
      AssertMathTrailblazeTool(expression = expression, expected = expected, timeoutMs = 0L)
    }
  }

  /**
   * Pins the default-unchanged guarantee DIRECTLY, by failing if the poll's sleep is ever reached
   * with no `timeoutMs` set. Not redundant with `default single-shot does not retry`: that test
   * infers the guarantee from a read count, and the read count cannot see this regression when the
   * first read already passes — the poll loop returns on its first attempt, so `readCount == 1`
   * whether polling is enabled or not. That sibling is structurally blind here, not weak. Since the
   * guarantee covers every recorded `assertMath` in the repo, it should not rest on one assertion.
   */
  @Test
  fun `single-shot never sleeps so a null timeoutMs cannot silently poll`() {
    // Stale read: the single-shot path must fail outright rather than wait for a better value.
    val comparator = SequencedElementComparator(listOf("10.0"))
    val tool = AssertMathTrailblazeTool(expression = expression, expected = expected)

    val error = assertFailsWith<TrailblazeException> {
      tool.executeWithClock(
        comparator,
        now = { 0L },
        sleep = { fail("single-shot (timeoutMs = null) must never sleep; poll entered with ${it}ms") },
      )
    }

    assertTrue(
      error.message?.contains("Math assertion failed") == true,
      "expected the unchanged single-shot failure, got: ${error.message}",
    )
    assertEquals(1, comparator.readCount, "single-shot must evaluate exactly once")
  }

  @Test
  fun `single-shot ignores pollIntervalMs so a stray value does not gate construction`() {
    // pollIntervalMs is only read when timeoutMs is set; a legacy single-shot call must construct
    // regardless of it.
    val tool = AssertMathTrailblazeTool(expression = expression, expected = expected, pollIntervalMs = -1L)
    assertEquals(null, tool.timeoutMs)
  }
}
