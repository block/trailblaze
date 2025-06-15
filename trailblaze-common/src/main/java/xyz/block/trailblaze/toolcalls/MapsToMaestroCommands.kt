package xyz.block.trailblaze.toolcalls

import maestro.orchestra.Command

/**
 * A [TrailblazeTool] that ends up executing Maestro [Command]s.
 */
abstract class MapsToMaestroCommands : ExecutableTrailblazeTool {
  abstract fun toMaestroCommands(): List<Command>

  override fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult = toolExecutionContext.trailblazeAgent.runMaestroCommands(
    maestroCommands = toMaestroCommands(),
    llmResponseId = toolExecutionContext.llmResponseId,
  )
}
