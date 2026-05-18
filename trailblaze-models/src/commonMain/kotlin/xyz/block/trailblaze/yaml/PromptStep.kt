package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable

@Serializable
sealed interface PromptStep {
  val prompt: String
  val recordable: Boolean
  val recording: ToolRecording?
  val postcondition: StepPostcondition?
}

fun PromptStep.toDetailedString() {
  buildString {
    appendLine("Type: ${this::class.simpleName}")
    appendLine("Prompt: $prompt")
    appendLine("Recordable: $recordable")
    appendLine("Recording: $recording")
    appendLine("Postcondition: $postcondition")
  }
}

@Serializable
data class DirectionStep(
  val step: String,
  override val recordable: Boolean = true,
  override val recording: ToolRecording? = null,
  override val postcondition: StepPostcondition? = null,
) : PromptStep {
  override val prompt: String = step
}

@Serializable
data class VerificationStep(
  val verify: String,
  override val recordable: Boolean = true,
  override val recording: ToolRecording? = null,
  override val postcondition: StepPostcondition? = null,
) : PromptStep {
  override val prompt: String = verify
}
