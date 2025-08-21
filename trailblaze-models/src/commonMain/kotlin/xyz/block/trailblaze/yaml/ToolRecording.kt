package xyz.block.trailblaze.yaml

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ToolRecording(
  val tools: List<@Contextual TrailblazeToolYamlWrapper>,
)
