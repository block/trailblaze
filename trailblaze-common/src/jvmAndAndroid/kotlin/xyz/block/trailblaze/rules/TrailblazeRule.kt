package xyz.block.trailblaze.rules

import maestro.orchestra.Command
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * This is mean to represent the primitive functionality of Trailblaze, accessible during test execution.
 */
interface TrailblazeRule {
  /**
   * Run a full Trailblaze test using the YAML test format.
   *
   * @param useRecordedSteps replay a step's recorded actions when present. When a step has no
   *   recording, behavior depends on whether AI is enabled for the run: the agent fills the step in
   *   if AI is enabled, otherwise the step fails loudly instead of silently calling the LLM.
   */
  fun run(testYaml: String, trailFilePath: String? = null, useRecordedSteps: Boolean = true)

  /**
   * Run natural language instructions with the agent.
   *
   * @throws [xyz.block.trailblaze.exception.TrailblazeException] if the agent fails to complete the task.
   */
  fun prompt(objective: String): Boolean

  /**
   * Run a Trailblaze tool with the agent.
   *
   * @throws [xyz.block.trailblaze.exception.TrailblazeException] if the agent fails to complete the task.
   */
  fun tool(vararg trailblazeTool: TrailblazeTool): TrailblazeToolResult

  /**
   * Use Maestro [Command] Models Directly for Type Safety
   */
  suspend fun maestroCommands(vararg maestroCommand: Command): TrailblazeToolResult
}
