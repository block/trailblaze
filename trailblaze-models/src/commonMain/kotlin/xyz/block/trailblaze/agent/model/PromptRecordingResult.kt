package xyz.block.trailblaze.agent.model

import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

sealed interface PromptRecordingResult {
  data class Success(val tools: List<TrailblazeToolYamlWrapper>) : PromptRecordingResult
  data class Failure(
    val successfulTools: List<TrailblazeToolYamlWrapper>,
    val failedTool: TrailblazeToolYamlWrapper,
    val failureResult: TrailblazeToolResult,
  ) : PromptRecordingResult
}
