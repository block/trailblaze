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
Generate a unique random value — a prefix followed by random digits (e.g. "TBZ-481732") — type it
into the currently focused text field, and remember it under the variable name so later steps can
reference it as {{variable}} / ${'$'}{variable} (a search field, an assertVisibleWithText, etc.).

Use this to enter a fresh unique value (a name, note, order/ticket label) that a later step must
recall to confirm the entity THIS run created rather than a leftover from a previous run. It
generates and types in a single step with no LLM call, so it replays deterministically and never
leaves the field empty.
- NOTE: This does nothing unless an editable text field is focused. If the field isn't focused, tap it first.
- NOTE: After typing, the soft keyboard is dismissed by default (like inputText); pass hideKeyboardAfter=false to keep it.
""",
)
data class InputTextRandomTrailblazeTool(
  @param:LLMDescription("The memory variable to store the generated value under (recall via {{variable}} / \${variable}).")
  val variable: String,
  @param:LLMDescription("Text placed before the random digits. Defaults to \"TBZ-\".")
  val prefix: String = "TBZ-",
  @param:LLMDescription("How many random decimal digits to append after the prefix. Defaults to 6.")
  val digitCount: Int = 6,
  val hideKeyboardAfter: Boolean = true,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (variable.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "variable must be a non-blank memory key.",
      )
    }
    if (digitCount <= 0) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "digitCount must be > 0 (was $digitCount).",
      )
    }
    val value = randomValue(prefix, digitCount, Random.Default)
    // Remember on the SAME (device-side) execution that types it. Because this write happens inside
    // the tool's on-device execute, it rides back to the host in the RPC response's memory snapshot
    // (and is re-pushed on every later RPC), so ${variable} resolves in subsequent steps — unlike a
    // host-side memory tool whose write is dropped by the next RPC's snapshot replace.
    toolExecutionContext.memory.remember(variable, value)

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
      return TrailblazeToolResult.Success(message = "Typed '$value' and remembered it as '$variable'.")
    }
    return result
  }

  companion object {
    /**
     * Pure value builder — [prefix] followed by [digitCount] random decimal digits. Kept separate
     * from [execute] with [random] injected so the generation is unit-tested deterministically with
     * a seeded [Random]. Callers pass [Random.Default] in production.
     */
    fun randomValue(prefix: String, digitCount: Int, random: Random): String = buildString {
      append(prefix)
      repeat(digitCount) { append(random.nextInt(10)) }
    }
  }
}
