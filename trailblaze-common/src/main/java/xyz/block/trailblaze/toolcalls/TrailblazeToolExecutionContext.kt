package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId

/**
 * Context for handling Trailblaze tools.
 * Provides access to session, agent, and device information needed for tool execution.
 */
class TrailblazeToolExecutionContext(
  val screenState: ScreenState?,
  val traceId: TraceId?,
  val trailblazeAgent: MaestroTrailblazeAgent,
  val trailblazeDeviceInfo: TrailblazeDeviceInfo,
  val sessionProvider: TrailblazeSessionProvider,
  /**
   * Optional provider to capture a fresh screen state on demand.
   * Used by tools like TakeSnapshotTool that need to capture the current device state
   * at the moment the tool is executed, rather than using the cached screenState.
   */
  val screenStateProvider: (() -> ScreenState)? = null,
)
