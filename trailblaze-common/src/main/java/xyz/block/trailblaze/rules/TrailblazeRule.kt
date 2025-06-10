package xyz.block.trailblaze.rules

import maestro.orchestra.Command
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * This is mean to represent the primitive functionality of Trailblaze, accessible during test execution.
 */
interface TrailblazeRule {
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
   * Run a Trailblaze tool with the agent.
   *
   * @throws [xyz.block.trailblaze.exception.TrailblazeException] if the agent fails to complete the task.
   */
  fun maestro(maestroYaml: String): TrailblazeToolResult

  /**
   * Use Maestro [Command] Models Directly for Type Safety
   */
  fun maestroCommands(vararg maestroCommand: Command): TrailblazeToolResult
}
