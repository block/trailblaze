package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Remember a literal value under a variable. The other `remember*` tools read their value off the
 * screen or generate it; this one stores exactly what the caller wrote.
 *
 * Its main caller is device setup (`--device-setup` / a lane's `deviceSetupTools`): a run that
 * needs different behavior, such as a game day, sets a flag here, and the target's tools read it
 * with `ctx.memory.get`. No target-specific tool has to exist just to write one memory key, and the
 * same key works from `--memory KEY=VAL` on the CLI.
 */
@Serializable
@TrailblazeToolClass("rememberValue", surfaceToLlm = false)
@LLMDescription(
  """
Remember a literal value under a variable name, without reading the screen or typing anything.
Recall it in later steps as {{variable}} / ${'$'}{variable}, and target tools read it from memory.
Use it in device setup to set a flag that changes how later tools behave for this run.
    """,
)
data class RememberValueTrailblazeTool(
  @param:LLMDescription("Memory variable to store the value under (recall via {{variable}} / \${variable}).")
  val variable: String,
  @param:LLMDescription("The value to remember, stored exactly as given.")
  val value: String,
) : HostLocalExecutableTrailblazeTool {

  override val advertisedToolName: String get() = "rememberValue"

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (variable.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "variable must be non-blank so the value can be recalled later.",
        command = this,
      )
    }
    // The dispatch boundary resolves `{{token}}`s before execute, so a value written as
    // '{{pin}}' arrives as the secret itself. Copying it under a new key must keep it redacted.
    val memory = toolExecutionContext.memory
    if (memory.containsSensitiveValue(value)) memory.rememberSensitive(variable, value) else memory.remember(variable, value)
    val renderedValue = renderCaptured(toolExecutionContext, variable, value)
    return TrailblazeToolResult.Success(message = "Remembered $renderedValue as '$variable'.")
  }
}
