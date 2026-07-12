package xyz.block.trailblaze.toolcalls

import maestro.orchestra.Command

/**
 * A [TrailblazeTool] that ends up executing Maestro [Command]s.
 */
abstract class MapsToMaestroCommands : ExecutableTrailblazeTool {
  abstract fun toMaestroCommands(): List<Command>

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val agent =
      toolExecutionContext.maestroTrailblazeAgent
        ?: error("MapsToMaestroCommands requires MaestroTrailblazeAgent")
    return agent.runMaestroCommands(
      maestroCommands = toMaestroCommands(),
      traceId = toolExecutionContext.traceId,
    )
  }
}
