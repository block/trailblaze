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

@Serializable
@TrailblazeToolClass("inputTextRandom")
@LLMDescription(
  """
Generate a unique random value — an optional prefix, then random digits, then an optional suffix
(e.g. "TBZ-481732" or "3f9a1c@example.com") — and type it into the currently focused text field.
Optionally remember it under a variable name so later steps can reference it as
{{variable}} / ${'$'}{variable} (a search field, an assertVisibleWithText, etc.).

Use this to enter a fresh unique value (a name, note, order/ticket label, email, phone digits) that
each run needs to be distinct, optionally recalling it later to confirm the entity THIS run created
rather than a leftover from a previous run. It generates and types in a single step with no LLM
call, so it replays deterministically and never leaves the field empty.
- NOTE: This does nothing unless an editable text field is focused. If the field isn't focused, tap it first.
- NOTE: After typing, the soft keyboard is dismissed by default (like inputText); pass hideKeyboardAfter=false to keep it.
- NOTE: For a unique email, set hex=true and suffix to the domain (e.g. suffix="@example.com").
""",
)
data class InputTextRandomTrailblazeTool(
  @param:LLMDescription("Text placed before the random digits. Defaults to \"TBZ-\".")
  val prefix: String = "TBZ-",
  @param:LLMDescription("How many random digits to generate after the prefix. Defaults to 6.")
  val digitCount: Int = 6,
  @param:LLMDescription("Text placed after the random digits (e.g. an email domain like \"@example.com\"). Defaults to empty.")
  val suffix: String = "",
  @param:LLMDescription("Generate hexadecimal digits (0-9a-f) instead of decimal digits. Defaults to false.")
  val hex: Boolean = false,
  @param:LLMDescription("Optional memory variable to store the generated value under (recall via {{variable}} / \${variable}). Omit to type without remembering.")
  val variable: String = "",
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
