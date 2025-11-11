package xyz.block.trailblaze.agent.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

@Serializable
sealed interface PromptRecordingResult {
  @Serializable
  data class Success(val tools: List<TrailblazeToolYamlWrapper>) : PromptRecordingResult

  @Serializable
  data class Failure(
    val successfulTools: List<TrailblazeToolYamlWrapper>,
    val failedTool: TrailblazeToolYamlWrapper,
    val failureResult: TrailblazeToolResult,
  ) : PromptRecordingResult
}
