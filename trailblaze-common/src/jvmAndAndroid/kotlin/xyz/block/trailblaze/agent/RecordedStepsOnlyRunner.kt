package xyz.block.trailblaze.agent

import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.yaml.PromptStep

/**
 * The [TestAgentRunner] a deterministic gate gets: every entry point refuses.
 *
 * The in-process ANDROID_TEST driver uses it wherever its trail runs: inside the device's own
 * instrumentation, and on the host when the host drives it over RPC. [TrailblazeRunnerUtil]
 * reaches for this only when a step has no recording to replay, or when a recorded step failed
 * and self-heal is on. Both are the same authoring mistake, and both are far more useful as a
 * named failure than as an LLM improvising against a merge gate.
 */
class RecordedStepsOnlyRunner(
  override val screenStateProvider: () -> ScreenState,
) : TestAgentRunner {

  override fun run(prompt: PromptStep, stepStatus: PromptStepStatus): AgentTaskStatus =
    throw TrailblazeException(
      "Step \"${prompt.prompt}\" has no recorded tools. The in-process ANDROID_TEST driver replays " +
        "recordings only — record this trail against a device first (`trailblaze record`), then " +
        "commit the recorded steps."
    )

  override fun recover(
    promptStep: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ): AgentTaskStatus =
    throw TrailblazeException(
      "Recorded step \"${promptStep.prompt}\" failed at ${recordingResult.failedTool.name} and " +
        "self-heal is not available on the in-process ANDROID_TEST driver."
    )

  override fun appendToSystemPrompt(context: String) = Unit
}
