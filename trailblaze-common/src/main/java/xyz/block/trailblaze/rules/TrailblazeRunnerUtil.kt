package xyz.block.trailblaze.rules

import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.session.TrailblazeSessionManager
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.PromptStep

class TrailblazeRunnerUtil(
  val runTrailblazeTool: (tools: List<TrailblazeTool>) -> TrailblazeToolResult,
  private val trailblazeRunner: TestAgentRunner,
  private val trailblazeLogger: TrailblazeLogger,
  private val sessionManager: TrailblazeSessionManager,
) {
  fun runPrompt(
    prompts: List<PromptStep>,
    useRecordedSteps: Boolean,
  ): TrailblazeToolResult {
    for (prompt in prompts) {
      if (sessionManager.isCurrentSessionCancelled()) {
        throw TrailblazeSessionCancelledException()
      }

      val promptResult: TrailblazeToolResult = if (useRecordedSteps && prompt.canPromptStepUseRecording()) {
        runTrailblazeTool(prompt.recording!!.tools.map { it.trailblazeTool })
      } else {
        when (val status = trailblazeRunner.run(prompt)) {
          is ObjectiveComplete -> TrailblazeToolResult.Success
          is AgentTaskStatus.Failure -> {
            throw TrailblazeException(
              buildString {
                appendLine("Failed to successfully run prompt with AI ${TrailblazeJsonInstance.encodeToString(prompt)}")
                appendLine("Status Type: ${status::class.java.name}")
                appendLine("Status: ${TrailblazeJsonInstance.encodeToString(status)}")
              },
            )
          }

          is AgentTaskStatus.InProgress -> {
            // Still in progress
            TrailblazeToolResult.Success
          }
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
