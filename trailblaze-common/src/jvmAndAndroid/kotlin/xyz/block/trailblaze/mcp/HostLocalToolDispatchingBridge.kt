package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

/**
 * JVM-only extension seam for bridges that can execute host-local tools directly against the
 * active driver context, bypassing YAML serialization.
 *
 * Scripted tools need the current [TrailblazeToolRepo] so nested `client.callTool(...)`
 * callbacks can resolve built-in and dynamic tools during the same session.
 */
interface HostLocalToolDispatchingBridge {
  suspend fun executeHostLocalTool(
    tool: TrailblazeTool,
    toolRepo: TrailblazeToolRepo,
    traceId: TraceId? = null,
  ): String?
}
