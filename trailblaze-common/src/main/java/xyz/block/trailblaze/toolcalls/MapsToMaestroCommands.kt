package xyz.block.trailblaze.toolcalls

import maestro.orchestra.Command
import xyz.block.trailblaze.AgentMemory

/**
 * A [TrailblazeTool] that ends up executing Maestro [Command]s.
 */
abstract class MapsToMaestroCommands : ExecutableTrailblazeTool {
  abstract fun toMaestroCommands(memory: AgentMemory): List<Command>

  override fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult = toolExecutionContext.trailblazeAgent.runMaestroCommands(
    maestroCommands = toMaestroCommands(toolExecutionContext.trailblazeAgent.memory),
    llmResponseId = toolExecutionContext.llmResponseId,
  )
}
