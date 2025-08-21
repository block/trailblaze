package xyz.block.trailblaze.rules

import xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.PromptStep

class TrailblazeRunnerUtil(
  val runTrailblazeTool: (tools: List<TrailblazeTool>) -> TrailblazeToolResult,
  private val trailblazeRunner: TestAgentRunner,
) {
  fun runPrompt(
    prompts: List<PromptStep>,
    useRecordedSteps: Boolean,
  ): TrailblazeToolResult {
    for (prompt in prompts) {
      val promptResult: TrailblazeToolResult = if (useRecordedSteps && prompt.canPromptStepUseRecording()) {
        runTrailblazeTool(prompt.recording!!.tools.map { it.trailblazeTool })
      } else {
        val status = trailblazeRunner.run(prompt)
        when (status) {
          is ObjectiveComplete -> TrailblazeToolResult.Success
          else -> throw TrailblazeException("Failed to successfully run prompt with AI $prompt")
        }
      }
      if (promptResult is TrailblazeToolResult.Error) {
        throw TrailblazeException("Failed to successfully run prompt $prompt with error ${promptResult.errorMessage}")
      }
    }
    return TrailblazeToolResult.Success
  }

  private fun PromptStep.canPromptStepUseRecording() = recordable && recording != null
}
