package xyz.block.trailblaze.agent

import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep

object TrailblazeAiRunnerMessages {

  private val CONDITIONAL_PATTERNS = listOf("if there is", "if there's", "if the", "if a ", "if an ")

  private val MULTI_STEP_PATTERNS = listOf(" and then ", " then ", " followed by ", " after that ")

  private fun isConditionalObjective(prompt: String): Boolean =
    CONDITIONAL_PATTERNS.any { prompt.lowercase().startsWith(it) }

  private fun isMultiStepObjective(prompt: String): Boolean =
    MULTI_STEP_PATTERNS.any { prompt.lowercase().contains(it) }

  fun getReminderMessage(
    promptStep: PromptStep,
    completedObjectiveDescriptions: List<String> = emptyList(),
    latestObjectiveStatus: String? = null,
  ): String {
    val prompt = promptStep.prompt
    val isVerification = promptStep is VerificationStep
    val isConditional = isConditionalObjective(prompt)
    val isMultiStep = isMultiStepObjective(prompt)

    val prefix = when (promptStep) {
      is VerificationStep -> "Verify"
      is DirectionStep -> "Task"
    }

    return buildString {
      // Prior objective context — only show the last one for awareness
      val completedObjectives = completedObjectiveDescriptions.distinct()
      if (completedObjectives.isNotEmpty()) {
        val lastCompleted = completedObjectives.last()
        val totalCompleted = completedObjectives.size
        appendLine("## PRIOR OBJECTIVE (COMPLETED)")
        appendLine("- [x] $lastCompleted")
        if (totalCompleted > 1) {
          appendLine("- (${totalCompleted - 1} earlier objective(s) also completed in this flow)")
        }
        appendLine()
      }

      // Show in-progress status from within the current step
      latestObjectiveStatus
        ?.takeIf { it != "COMPLETED" }
        ?.let { status ->
          appendLine("## LAST STATUS UPDATE")
          appendLine("Your last status: $status")
          appendLine()
        }

      // Current objective
      appendLine("## CURRENT OBJECTIVE")
      appendLine()
      appendLine("> $prefix $prompt")

      // Verification-specific note
      if (isVerification) {
        appendLine()
        appendLine("NOTE: This is a VERIFICATION objective. Your job is to ONLY confirm what is currently visible on the screen. DO NOT attempt to alter the screen state, click anything, or make any changes. If you are asked to verify that something exists on the screen but it doesn't, fail the objective immediately.")
      }

      appendLine()
      appendLine("Focus ONLY on completing this specific objective. Do NOT perform actions beyond its scope.")

      // objectiveStatus instructions
      appendLine()
      appendLine("You may chain multiple tool calls to complete this objective. Once the objective is fully done (or if you need to report progress), call the objectiveStatus tool:")
      appendLine("- status=\"IN_PROGRESS\" if you need to take more actions to complete this objective")
      appendLine("- status=\"COMPLETED\" if you have fully accomplished ALL parts of this objective")
      appendLine("- status=\"FAILED\" if the objective cannot be completed after multiple attempts")

      // Dynamic completion guidance — only include what's relevant
      appendLine()
      appendLine("COMPLETION GUIDANCE:")
      if (isConditional) {
        appendLine("- This is a CONDITIONAL objective. If the condition is not met (e.g., the element is not present), mark COMPLETED — the absence means the condition was correctly evaluated, not a failure.")
      }
      if (isMultiStep) {
        appendLine("- This is a MULTI-STEP objective. Complete ALL steps before marking COMPLETED.")
      }
      if (!isMultiStep && !isVerification) {
        appendLine("- For single-action objectives, mark COMPLETED after the action succeeds — do NOT require the tapped element to still be visible (screens naturally change after taps).")
      }
      appendLine("- If a tool returned SUCCESS, be permissive — a changed screen usually means the action worked.")
      if (isConditional) {
        appendLine("- Only mark FAILED if there are clear error indicators (error dialogs, crash screens) or you have tried multiple different approaches and none succeeded.")
      } else {
        appendLine("- Mark FAILED if the required element or target cannot be found on the current screen, if there are clear error indicators (error dialogs, crash screens), or if you have tried multiple different approaches and none succeeded.")
      }
    }.trim()
  }
}
