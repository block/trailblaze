package xyz.block.trailblaze.openai

import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.agent.model.TrailblazePromptStep

object LogHelper {
  fun logPromptStart(prompt: TrailblazePrompt) {
    println("\n\u001B[1;35m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\u001B[0m")
    println("\u001B[1;35m🚀 Starting new agent objective: ${prompt.fullPrompt}\u001B[0m")
    println("\u001B[1;35m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\u001B[0m")
  }

  fun logStepStatus(step: TrailblazePromptStep) = with(step) {
    println("[END_ITERATION] Status: ${currentStatus.value.javaClass.simpleName} | Finished: ${isFinished()} | Continue: ${!isFinished()}")
  }
}
