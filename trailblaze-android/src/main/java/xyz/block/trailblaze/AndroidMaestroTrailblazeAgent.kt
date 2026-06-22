package xyz.block.trailblaze

import maestro.orchestra.Command
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.maestro.MaestroUiAutomatorRunner
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Android on-device Maestro agent for executing commands via UiAutomator.
 * Uses stateless logger with explicit session management.
 */
class AndroidMaestroTrailblazeAgent(
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
  nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
  memory: AgentMemory = AgentMemory(),
  captureNetworkTraffic: Boolean = false,
  resolvedTarget: ResolvedTarget? = null,
  appId: String? = null,
  // Threaded to the base so an `OtherTrailblazeTool` (e.g. a toolset-delivered scripted tool like
  // `openUrl`) resolves through this repo's dynamic-tool registrations before driver dispatch, and
  // so framework tools composed by name via `invokeFrameworkTool` resolve at dispatch instead of
  // crashing with "toolRepo not wired".
  //
  // **Required and non-null on purpose.** This agent is duplicate-constructed by more than one
  // JUnit rule — [AndroidTrailblazeRule] here, plus a downstream subclass — which each
  // must hand-sync this param. A `= null` default let a downstream rule silently omit it (it wired
  // `resolvedTarget`/`appId` but missed the repo), a latent gap until a TypeScript launch step
  // composed a framework tool by name and crashed at runtime (#3920). These rules always build a
  // session repo, so the param is non-null with no default: omitting it — or passing `null` to
  // re-introduce that gap — is a compile error. A caller with no tools to register passes an empty
  // `TrailblazeToolRepo`, never `null`. (The base keeps a nullable default for the host/test agents
  // that legitimately run without a repo.)
  trailblazeToolRepo: xyz.block.trailblaze.toolcalls.TrailblazeToolRepo,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  nodeSelectorMode = nodeSelectorMode,
  memory = memory,
  captureNetworkTraffic = captureNetworkTraffic,
  resolvedTarget = resolvedTarget,
  appId = appId,
  trailblazeToolRepo = trailblazeToolRepo,
) {
  override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult = MaestroUiAutomatorRunner.runCommands(
    commands = commands,
    traceId = traceId,
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )
}
