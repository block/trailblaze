package xyz.block.trailblaze.openai

import xyz.block.trailblaze.agent.model.TrailblazePromptStep

object TrailblazeAiRunnerMessages {

  fun getReminderMessage(step: TrailblazePromptStep, forceStepStatusUpdate: Boolean): String {
    // Add objectives information if available
    // Reminder message - depending on whether we need to force a task status update
    val reminderMessage = if (forceStepStatusUpdate) {
      """
        ## ACTION REQUIRED: REPORT TASK STATUS
        
        You just performed an action using a tool.
        You MUST now report the status of the current objective item by calling the objectiveStatus tool with one of the following statuses:
        - status="in_progress" if you're still working on this specific objective item and need to take more actions
        - status="completed" if you've fully accomplished all instructions in the current objective.
        - status="failed" if this specific objective item cannot be completed after multiple attempts.
        
        Include a detailed message explaining what you just did and your assessment of the situation.
        
        IMPORTANT: You CANNOT perform any other action until you report progress using objectiveStatus.
        Carefully assess if the action you just took completely fulfilled this specific objective item before deciding the status.
        
        ## CURRENT TASK
        
        Current objective item to focus on is:
        
        > Task #${step.taskIndex}: ${step.description}
      """.trimIndent()
    } else {
      """
        ## CURRENT TASK
        
        Your current objective item to focus on is:
        
        > Task #${step.taskIndex}: ${step.description}
        
        IMPORTANT: Focus ONLY on completing this specific objective item. 
        After you complete this objective item, call the objectiveStatus tool IMMEDIATELY.
        DO NOT proceed to the next objective item until this one is complete and you've called objectiveStatus.
      """.trimIndent()
    }
    return reminderMessage
  }
}
