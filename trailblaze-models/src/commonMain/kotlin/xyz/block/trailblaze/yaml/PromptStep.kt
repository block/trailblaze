package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable

@Serializable
sealed interface PromptStep {
  val prompt: String
  val recordable: Boolean
  val recording: ToolRecording?

  /**
   * Per-step override for the AI-execution retry budget.
   *
   * When set, `TrailGoalPlanner.executeWithAi` uses `maxRetries + 1` as the
   * per-step attempt cap for THIS step only, instead of [TrailblazeConfig.maxRetries].
   * Use for steps that legitimately need many tool calls to satisfy — most commonly
   * multi-character keypad entry (e.g. a 6-digit passcode needs 6+ tool calls, which
   * exceeds the default `maxRetries = 3` → 4 attempts cap).
   *
   * Null means "use config-level default" (fully backward-compatible).
   */
  val maxRetries: Int?
}

fun PromptStep.toDetailedString() {
  buildString {
    appendLine("Type: ${this::class.simpleName}")
    appendLine("Prompt: $prompt")
    appendLine("Recordable: $recordable")
    appendLine("Recording: $recording")
    appendLine("MaxRetries: $maxRetries")
  }
}

@Serializable
data class DirectionStep(
  val step: String,
  override val recordable: Boolean = true,
  override val recording: ToolRecording? = null,
  override val maxRetries: Int? = null,
) : PromptStep {
  override val prompt: String = step
}

@Serializable
data class VerificationStep(
  val verify: String,
  override val recordable: Boolean = true,
  override val recording: ToolRecording? = null,
  override val maxRetries: Int? = null,
) : PromptStep {
  override val prompt: String = verify
}
