package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.random.Random
import kotlinx.serialization.Serializable
import maestro.orchestra.InputTextCommand
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * Generates a unique random value (prefix + random digits + suffix) and types it into the focused
 * field, optionally remembering it so a later step can confirm the entity THIS run created rather
 * than a leftover from a previous run. It generates and types in a single step with no LLM call, so
 * it replays deterministically and never leaves the field empty.
 */
@Serializable
@TrailblazeToolClass("inputTextRandom")
@LLMDescription(
  """
Type a unique random value (prefix + random digits + suffix, e.g. "TBZ-481732") into the focused
text field; does nothing if no field is focused. Optionally remembers it as {{variable}} /
${'$'}{variable} for later steps. Use when each run needs a distinct value. For a unique email, set
hex=true and suffix="@example.com".
""",
)
data class InputTextRandomTrailblazeTool(
  @param:LLMDescription("Text before the digits. Default \"TBZ-\".")
  val prefix: String = "TBZ-",
  @param:LLMDescription("Number of random digits. Default 6.")
  val digitCount: Int = 6,
  @param:LLMDescription("Text after the digits, e.g. \"@example.com\". Default empty.")
  val suffix: String = "",
  @param:LLMDescription("Use hex digits (0-9a-f) instead of decimal. Default false.")
  val hex: Boolean = false,
  @param:LLMDescription("Memory variable to store the value under. Omit to not remember it.")
  val variable: String = "",
  @param:LLMDescription("Close the soft keyboard after typing. Default true.")
  val hideKeyboardAfter: Boolean = true,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (digitCount <= 0) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "digitCount must be > 0 (was $digitCount).",
      )
    }
    val value = randomValue(prefix, digitCount, suffix, hex, Random.Default)
    // Remember on the SAME (device-side) execution that types it. Because this write happens inside
    // the tool's on-device execute, it rides back to the host in the RPC response's memory snapshot
    // (and is re-pushed on every later RPC), so ${variable} resolves in subsequent steps — unlike a
    // host-side memory tool whose write is dropped by the next RPC's snapshot replace.
    if (variable.isNotBlank()) {
      toolExecutionContext.memory.remember(variable, value)
    }

    val maestroCommands = if (hideKeyboardAfter) {
      listOf(InputTextCommand(value)) +
        HideKeyboardTrailblazeTool.hideKeyboardCommands(toolExecutionContext.trailblazeDeviceInfo)
    } else {
      listOf(InputTextCommand(value))
    }
    val result = toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
    if (result.isSuccess()) {
      val remembered = if (variable.isNotBlank()) " and remembered it as '$variable'" else ""
      return TrailblazeToolResult.Success(message = "Typed '$value'$remembered.")
    }
    return result
  }

  companion object {
    private const val DECIMAL_DIGITS = "0123456789"
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Pure value builder — [prefix], then [digitCount] random digits (decimal, or hexadecimal when
     * [hex] is true), then [suffix]. Kept separate from [execute] with [random] injected so the
     * generation is unit-tested deterministically with a seeded [Random]. Callers pass
     * [Random.Default] in production.
     */
    fun randomValue(
      prefix: String,
      digitCount: Int,
      suffix: String,
      hex: Boolean,
      random: Random,
    ): String = buildString {
      append(prefix)
      val pool = if (hex) HEX_DIGITS else DECIMAL_DIGITS
      repeat(digitCount) { append(pool[random.nextInt(pool.length)]) }
      append(suffix)
    }
  }
}
