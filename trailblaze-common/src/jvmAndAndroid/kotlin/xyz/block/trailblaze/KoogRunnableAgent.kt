package xyz.block.trailblaze

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * The full agent surface the Koog strategy-graph runner drives: tool dispatch
 * ([TrailblazeAgent.runTrailblazeTools]), session context ([TrailblazeAgentContext] — logger,
 * session, memory, device info), and execution-context construction for dynamic tools
 * ([buildKoogToolExecutionContext]).
 *
 * This is an interface (rather than the runner taking [BaseTrailblazeAgent] directly) so a
 * multi-device session can hand the runner a *routing* agent that resolves the ACTIVE device's
 * agent per call — every [BaseTrailblazeAgent] implements this, and the host runner's router
 * delegates each member to whichever bound agent `switchDevice` last selected.
 */
interface KoogRunnableAgent :
  TrailblazeAgent,
  TrailblazeAgentContext {

  /**
   * Build a [TrailblazeToolExecutionContext] for a Koog-dispatched dynamic (subprocess-MCP)
   * tool — the rare tools that execute against a context rather than through
   * [TrailblazeAgent.runTrailblazeTools]. See [BaseTrailblazeAgent.buildKoogToolExecutionContext]
   * for the canonical implementation.
   */
  fun buildKoogToolExecutionContext(
    traceId: TraceId?,
    screenStateProvider: () -> ScreenState,
  ): TrailblazeToolExecutionContext
}
