package xyz.block.trailblaze.toolcalls

/**
 * A [TrailblazeTool] that modifies the agent's available tool configuration rather than
 * interacting with the device. These tools are handled by the agent orchestrator
 * (e.g. [DirectMcpAgent][xyz.block.trailblaze.agent.DirectMcpAgent],
 * [TrailblazeKoogLlmClientHelper][xyz.block.trailblaze.agent.TrailblazeKoogLlmClientHelper])
 * and must be intercepted before reaching the device driver (e.g. MaestroTrailblazeAgent),
 * which does not have access to the [TrailblazeToolRepo].
 */
interface ConfigTrailblazeTool : TrailblazeTool {
  fun execute(toolRepo: TrailblazeToolRepo): TrailblazeToolResult
}
