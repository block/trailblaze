package xyz.block.trailblaze.agent

import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep

object TrailblazeAiRunnerMessages {

  fun getReminderMessage(promptStep: PromptStep, forceStepStatusUpdate: Boolean): String {
    // Determine the prefix based on the type of prompt step
    val prefix = when (promptStep) {
      is VerificationStep -> "Verify"
      is DirectionStep -> "Task"
    }

    // Add verification-specific instructions
    val verificationNote = if (promptStep is VerificationStep) {
      """
        
        NOTE: This is a VERIFICATION task. Your job is to ONLY confirm what is currently 
        visible on the screen. DO NOT attempt to alter the screen state, click anything, 
        or make any changes. If you are asked to verify that something exists on the 
        screen but it doesn't, fail the objective immediately.
      """.trimIndent()
    } else {
      ""
    }

    // Add objectives information if available
    // Reminder message - depending on whether we need to force a task status update
    val reminderMessage = if (forceStepStatusUpdate) {
      """
        ## ACTION REQUIRED: REPORT TASK STATUS
        
        You just performed an action using a tool and the tool execution returned SUCCESS.
        You MUST now report the status of the current objective item by calling the objectiveStatus tool with one of the following statuses:
        - status="IN_PROGRESS" if you're still working on this specific objective item and need to take more actions
        - status="COMPLETED" if you've fully accomplished ALL instructions in the current objective item
        - status="FAILED" if this specific objective item cannot be completed after multiple attempts, or if clear error indicators are visible.
        
        IMPORTANT GUIDANCE FOR COMPLETION EVALUATION:
        - If the objective contains MULTIPLE STEPS (e.g., "do X, then Y"), you must complete ALL steps before marking as "COMPLETED"
        - A single successful tool call does NOT mean the objective is complete - carefully read the full objective to ensure all parts are done
        - Only mark as "COMPLETED" when you have performed all required actions described in the objective item
        - If you've only completed part of a multi-step objective, mark as "IN_PROGRESS" and continue with the remaining steps
        
        CRITICAL GUIDANCE FOR SINGLE-ACTION TASKS:
        - If the objective is a SINGLE ACTION (e.g., "Tap on X", "Click Y", "Enter text Z"), and the tool returned SUCCESS, then mark as "COMPLETED"
        - For single-action tasks, you do NOT need to verify that specific UI elements are still visible after the action - the screen naturally changes after interactions
        - The fact that the tool returned SUCCESS means the action was performed successfully
        - Only mark as "FAILED" if there are clear ERROR INDICATORS (error dialogs, error messages, crash screens, etc.)
        
        GUIDANCE FOR MULTI-STEP TASKS:
        - If the tool call returned SUCCESS, you should be permissive in evaluating whether that specific action worked
        - The screen state changing (even if different from what you expected) often indicates the action worked
        - Don't fail the task just because you can't find the exact element you expected - the UI may have changed in ways you didn't anticipate
        - Focus on whether the tool executed without errors, not on whether the resulting screen matches your exact expectations
        - Only mark as "FAILED" if there are clear error indicators (error dialogs, error messages, etc.) or if you've tried multiple different approaches
        
        Include a detailed message explaining what you just did and your assessment of the situation.
        
        IMPORTANT: You CANNOT perform any other action until you report progress using objectiveStatus.
        
        ## CURRENT TASK
        
        Current objective item to focus on is:
        
        > $prefix ${promptStep.prompt}$verificationNote
      """.trimIndent()
    } else {
      """
        ## CURRENT TASK
        
        Your current objective item to focus on is:
        
        > $prefix ${promptStep.prompt}$verificationNote
        
        IMPORTANT: Focus ONLY on completing this specific objective item. 
        After you complete this objective item, call the objectiveStatus tool IMMEDIATELY.
        DO NOT proceed to the next objective item until this one is complete and you've called objectiveStatus.
      """.trimIndent()
    }
    return reminderMessage
  }
}
