package xyz.block.trailblaze.yaml

import kotlinx.serialization.Serializable

@Serializable
data class PromptStep(
  val step: String,
  val recordable: Boolean = true,
  val recording: ToolRecording? = null,
)
