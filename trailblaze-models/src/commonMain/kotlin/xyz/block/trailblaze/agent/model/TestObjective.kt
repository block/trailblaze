package xyz.block.trailblaze.agent.model

import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.toolcalls.TrailblazeTool

interface TestObjective {
  data class RememberTextCommand(val promptWithVars: String, val variableName: String) : TestObjective
  data class RememberNumberCommand(val promptWithVars: String, val variableName: String) : TestObjective
  data class RememberWithAiCommand(val promptWithVars: String, val variableName: String) : TestObjective
  data class AssertEqualsCommand(val actual: String, val expected: String) : TestObjective
  data class AssertNotEqualsCommand(val actual: String, val expected: String) : TestObjective
  data class AssertWithAiCommand(val promptWithVars: String) : TestObjective
  data class AssertMathCommand(val expression: String, val expected: String) : TestObjective
  data class MaestroCommand(val maestroCommandWithVars: String) : TestObjective
  interface TrailblazeObjective : TestObjective {
    data class TrailblazeCommand(
      val tools: List<StaticObjective>,
    ) : TrailblazeObjective

    data class TrailblazePrompt(
      val fullPrompt: String,
      val steps: List<TrailblazePromptStep>,
    ) : TrailblazeObjective
  }
}

// Represents a static tool call we want to execute instead of a prompt for the LLM to handle
data class StaticObjective(
  val tools: List<TrailblazeTool>,
)

// Convenience function that turns a string into a TrailblazePrompt with a single step
fun String.toTrailblazePrompt(
  llmStatusChecks: Boolean = true,
) = TrailblazePrompt(
  fullPrompt = this,
  steps = listOf(
    TrailblazePromptStep(
      description = this,
      llmStatusChecks = llmStatusChecks,
    ),
  ),
)
