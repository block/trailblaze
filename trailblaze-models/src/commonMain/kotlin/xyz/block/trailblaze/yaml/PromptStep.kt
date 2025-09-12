package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable

@Serializable
sealed interface PromptStep {
  val prompt: String
  val recordable: Boolean
  val recording: ToolRecording?
}

@Serializable
data class DirectionStep(
  val step: String,
  override val recordable: Boolean = true,
  override val recording: ToolRecording? = null,
) : PromptStep {
  override val prompt: String = step
}

@Serializable
data class VerificationStep(
  val verify: String,
  override val recordable: Boolean = true,
  override val recording: ToolRecording? = null,
) : PromptStep {
  override val prompt: String = verify
}
