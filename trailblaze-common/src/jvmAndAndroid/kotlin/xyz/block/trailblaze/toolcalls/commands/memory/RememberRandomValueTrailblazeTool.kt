package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.random.Random
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.InputTextRandomTrailblazeTool

/**
 * The non-typing twin of `inputTextRandom`: generate a randomized value and remember it.
 *
 * `inputTextRandom` can only name a value entered through a focused text field, so it can't name a
 * file staged before any app UI exists (e.g. `android_writeFileToDownloads`). As a
 * [HostLocalExecutableTrailblazeTool], it runs in whichever JVM owns the agent loop. A trail can
 * generate the name first, remember it under [variable], and interpolate that variable into both
 * the file-write and every later assertion. This reduces collisions between concurrent replays
 * without requiring an LLM.
 *
 * When the agent loop runs on the host, the write survives subsequent device RPCs because
 * `HostOnDeviceRpcTrailblazeAgent` merges returned memory snapshots instead of replacing them.
 */
@Serializable
@TrailblazeToolClass("rememberRandomValue", surfaceToLlm = false)
@LLMDescription(
  """
Generate a randomized value — an optional prefix, then random digits, then an optional suffix
(e.g. "TeamUploadDoc-481732") — and remember it under a variable name, WITHOUT typing it anywhere.
Recall it in later steps as {{variable}} / ${'$'}{variable}. Unlike inputTextRandom this needs no
focused field, so use it to name a staged fixture (a file written before the app UI exists), then
pin the file-write and every assertion to the same variable. Randomized names reduce collisions
between concurrent replays; they do not guarantee global uniqueness.
    """,
)
data class RememberRandomValueTrailblazeTool(
  @param:LLMDescription("Text placed before the random digits. Defaults to empty.")
  val prefix: String = "",
  @param:LLMDescription("How many random digits to generate after the prefix. Defaults to 6.")
  val digitCount: Int = 6,
  @param:LLMDescription("Text placed after the random digits (e.g. an email domain like \"@example.com\"). Defaults to empty.")
  val suffix: String = "",
  @param:LLMDescription("Generate hexadecimal digits (0-9a-f) instead of decimal digits. Defaults to false.")
  val hex: Boolean = false,
  @param:LLMDescription("Memory variable to store the generated value under (recall via {{variable}} / \${variable}).")
  val variable: String,
) : HostLocalExecutableTrailblazeTool {

  override val advertisedToolName: String get() = "rememberRandomValue"

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (digitCount <= 0) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "digitCount must be > 0 (was $digitCount).",
        command = this,
      )
    }
    if (variable.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "variable must be non-blank so the generated value can be recalled later.",
        command = this,
      )
    }
    val value = InputTextRandomTrailblazeTool.randomValue(prefix, digitCount, suffix, hex, Random.Default)
    toolExecutionContext.memory.remember(variable, value)
    val renderedValue = renderCaptured(toolExecutionContext, variable, value)
    return TrailblazeToolResult.Success(message = "Generated $renderedValue and remembered it as '$variable'.")
  }
}
