package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.SensitiveArgsTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/** Stores generated setup inputs with the same redaction contract as CLI --secret seeds. */
@Serializable
@TrailblazeToolClass("rememberSecretValue", surfaceToLlm = false, isRecordable = false)
@LLMDescription("Remember a sensitive value without writing it to logs or replay recordings.")
data class RememberSecretValueTrailblazeTool(
  @param:LLMDescription("Memory variable for the sensitive value.") val variable: String,
  @param:LLMDescription("Sensitive value to remember; redacted from persisted logs.")
  val value: String,
) : HostLocalExecutableTrailblazeTool, SensitiveArgsTrailblazeTool {
  override val advertisedToolName: String
    get() = "rememberSecretValue"

  override val sensitiveArgNames: Set<String>
    get() = setOf("value")

  override suspend fun execute(context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    if (variable.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "variable must be non-blank so the value can be recalled later.",
        command = this,
      )
    }
    context.memory.rememberSensitive(variable, value)
    return TrailblazeToolResult.Success(message = "Remembered a sensitive value as '$variable'.")
  }
}
