package xyz.block.trailblaze.compose.driver.rpc

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.compose.driver.ComposeViewHierarchyDetail
import xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
import xyz.block.trailblaze.compose.driver.tools.ComposeExecutableTool
import xyz.block.trailblaze.compose.driver.tools.ComposeRequestDetailsTool
import xyz.block.trailblaze.compose.driver.tools.ComposeScrollTool
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyElementVisibleTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyTextVisibleTool
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.utils.NoOpElementComparator
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * [BaseTrailblazeAgent] that delegates tool execution to a remote Compose app via RPC.
 *
 * Parallel to [xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent] but sends tools over
 * HTTP to a [ComposeRpcServer] running inside the Compose application, rather than executing them
 * in-process against a [ComposeUiTest].
 *
 * After each tool execution batch, captures a screenshot and view hierarchy via RPC and logs a
 * [TrailblazeLog.AgentDriverLog] so the HTML report can visualize what happened on screen.
 *
 * Extending [BaseTrailblazeAgent] (rather than implementing [TrailblazeAgent] directly) lets the
 * shared Koog strategy-graph seam drive the Compose RPC driver: the per-tool dispatch lives in
 * [executeTool] and the execution context in [buildExecutionContext], so `KoogTestAgentRunner`
 * can reach `runTrailblazeTools` / `buildKoogToolExecutionContext` like every other driver agent.
 */
class ComposeRpcTrailblazeAgent(
  private val rpcClient: ComposeRpcClient,
  override val trailblazeLogger: TrailblazeLogger,
  override val sessionProvider: TrailblazeSessionProvider,
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  override val trailblazeToolRepo: TrailblazeToolRepo? = null,
) : BaseTrailblazeAgent(),
  Closeable {

  private val pendingDetailRequests =
    AtomicReference<Set<ComposeViewHierarchyDetail>>(emptySet())

  val screenStateProvider: () -> ScreenState = {
    val details = pendingDetailRequests.getAndSet(emptySet())
    val screenStateResult = runBlocking { rpcClient.getScreenState(requestedDetails = details) }
    when (screenStateResult) {
      is RpcResult.Success -> ComposeRpcScreenState(screenStateResult.data)
      is RpcResult.Failure ->
        error("Failed to get screen state: ${screenStateResult.message}")
    }
  }

  override fun buildExecutionContext(
    traceId: TraceId,
    screenState: ScreenState?,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolExecutionContext {
    val effectiveScreenStateProvider = screenStateProvider ?: this.screenStateProvider
    return TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = traceId,
      trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
      sessionProvider = sessionProvider,
      screenStateProvider = effectiveScreenStateProvider,
      trailblazeLogger = trailblazeLogger,
      memory = memory,
      maestroTrailblazeAgent = null,
      // Route nested framework-tool calls — a custom/scripted tool composing an action via
      // `ctx.invokeFrameworkTool(...)` or a scripting callback — back through this agent so a nested
      // ComposeExecutableTool dispatches over RPC. Without it the bridge falls back to
      // `tool.execute(context)`, which every ComposeExecutableTool implements by throwing.
      // NoOpElementComparator is intentional: the nested tool reuses this batch's live
      // screen/`screenStateProvider`, and no nested tool here does LLM element matching.
      //
      // Deliberately NOT switched to `BaseTrailblazeAgent.nestedToolExecutorFor` (the fix
      // MaestroTrailblazeAgent / PlaywrightTrailblazeAgent adopted in #4519 for the identical
      // stale-context bug — see `docs/devlog/2026-07-03-batched-tool-execution-scope.md`).
      // Two reasons this stays on the old re-entrant-`runTrailblazeTools` shape: (1) unlike
      // Maestro's `AndroidDeviceCommandExecutor`, this context carries no context-scoped device
      // state — the mutable state here (`pendingDetailRequests`, `screenStateProvider`) lives on
      // the agent, so it already survives a context rebuild; there's no demonstrated bug to fix.
      // (2) this routes through the overridden `runTrailblazeTools` below, so a nested composition
      // emits its own post-batch AgentDriverLog screenshot — switching to `nestedToolExecutorFor`
      // would skip that override and silently drop the screenshot for nested compositions.
      // Revisit if a future Compose-side context-scoped cache creates the same bug class.
      nestedToolExecutor = { nestedTool ->
        runTrailblazeTools(
          tools = listOf(nestedTool),
          traceId = traceId,
          screenState = screenState,
          elementComparator = NoOpElementComparator,
          screenStateProvider = effectiveScreenStateProvider,
        ).result
      },
      // Threads the agent's tool repo through so Kotlin tools composing framework tools via
      // `ctx.invokeFrameworkTool(...)` resolve them by name — same wiring as the in-process
      // Compose / Playwright / Revyl agents. Without it the bridge throws "toolRepo not wired".
      toolRepo = trailblazeToolRepo,
    )
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    val resolvedTraceId = context.traceId ?: TraceId.generate(TraceOrigin.TOOL)
    // Memory-interpolation boundary for the Compose RPC driver, applied HOST-side before the
    // send: unlike the Android on-device RPC path, the remote ComposeRpcServer receives no
    // memory snapshot and runs no dispatch loop of its own, so tokens must resolve before the
    // tool crosses the wire. `toolsExecuted` keeps the authored instance (base-loop convention).
    val memoryResolvedTool = interpolateMemoryInTool(tool, memory)
    val rawTool = tool.takeIf { it !== memoryResolvedTool }
    return when (memoryResolvedTool) {
      // compose_request_details is applied CLIENT-SIDE — it is NOT sent over RPC. Instead it arms
      // pendingDetailRequests so the NEXT getScreenState() carries the requested detail. Checked
      // before ComposeExecutableTool because it is one (and would otherwise round-trip needlessly).
      is ComposeRequestDetailsTool -> {
        val toolStartTime = Clock.System.now()
        toolsExecuted.add(tool)
        pendingDetailRequests.set(memoryResolvedTool.include.toSet())
        val result = TrailblazeToolResult.Success(
          message = "Requested details: ${memoryResolvedTool.include.joinToString()}",
        )
        logToolExecution(memoryResolvedTool, toolStartTime, resolvedTraceId, result, rawTool = rawTool)
        result
      }

      // Checked before the generic ExecutableTrailblazeTool branch: ComposeExecutableTool IS an
      // ExecutableTrailblazeTool, but its execute() throws — it must run over RPC instead.
      is ComposeExecutableTool -> {
        val toolStartTime = Clock.System.now()
        toolsExecuted.add(tool)
        val rpcResult: RpcResult<ExecuteToolsResponse> =
          runBlocking { rpcClient.executeTools(ExecuteToolsRequest(tools = listOf(memoryResolvedTool))) }
        when (rpcResult) {
          is RpcResult.Success -> {
            val result = rpcResult.data.results.firstOrNull() ?: TrailblazeToolResult.Success()
            logToolExecution(memoryResolvedTool, toolStartTime, resolvedTraceId, result, rawTool = rawTool)
            result
          }

          is RpcResult.Failure -> {
            val errorMessage =
              "RPC call failed: ${rpcResult.message} (${rpcResult.errorType})" +
                (rpcResult.details?.let { " — $it" } ?: "")
            val result = TrailblazeToolResult.Error.ExceptionThrown(errorMessage)
            // Log the transport-failure dispatch too (parity with the success path and the
            // other driver agents) so KOOG/runner reports can correlate a failed RPC tool call.
            logToolExecution(memoryResolvedTool, toolStartTime, resolvedTraceId, result, rawTool = rawTool)
            result
          }
        }
      }

      // Generic host-JVM executables (e.g. TakeSnapshotTool). Host-local subprocess MCP tools and
      // MemoryTrailblazeTool never reach here — the base loop short-circuits them before executeTool.
      is ExecutableTrailblazeTool -> {
        val toolStartTime = Clock.System.now()
        toolsExecuted.add(tool)
        val result = runBlocking { memoryResolvedTool.execute(context) }
        logToolExecution(memoryResolvedTool, toolStartTime, resolvedTraceId, result, rawTool = rawTool)
        result
      }

      else -> {
        val errorMessage =
          "Unsupported tool type ${tool::class.simpleName} in ComposeRpcTrailblazeAgent."
        Console.log("Error: $errorMessage")
        toolsExecuted.add(tool)
        TrailblazeToolResult.Error.ExceptionThrown(errorMessage)
      }
    }
  }

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeAgent.RunTrailblazeToolsResult {
    // Resolve the trace id up front and hand the base loop a non-null id so this batch's per-tool
    // logs and the post-batch AgentDriverLog screenshot below share one id (report code joins LLM
    // and tool activity by traceId).
    val resolvedTraceId = traceId ?: TraceId.generate(TraceOrigin.TOOL)
    val overallStartTime = Clock.System.now()
    // Base loop: SnapshotCache frame, ThreadLocal context install, MemoryTrailblazeTool +
    // HostLocalExecutableTrailblazeTool short-circuits, dynamic-tool resolution, early-exit on
    // failure, then [executeTool] for each remaining tool.
    val result = super.runTrailblazeTools(
      tools = tools,
      traceId = resolvedTraceId,
      screenState = screenState,
      elementComparator = elementComparator,
      screenStateProvider = screenStateProvider,
    )
    // Capture ONE post-batch screenshot + view hierarchy for the HTML report, matching the
    // pre-BaseTrailblazeAgent shape: once per batch, not once per tool. The default TRAILBLAZE_RUNNER
    // path's recorded `tools:` blocks (a whole block dispatched as one batch) keep their single
    // screenshot, while the KOOG path — which dispatches one tool per call — naturally gets one
    // screenshot per tool. logScreenStateAfterExecution swallows RPC failures, so an errored batch
    // still returns cleanly.
    val totalTimeMs =
      Clock.System.now().toEpochMilliseconds() - overallStartTime.toEpochMilliseconds()
    logScreenStateAfterExecution(tools, overallStartTime, totalTimeMs, resolvedTraceId)
    return result
  }

  /**
   * Captures the current screen state via RPC and logs a [TrailblazeLog.AgentDriverLog] with the
   * screenshot and view hierarchy. This is what the HTML report uses to visualize each step.
   *
   * Follows the same pattern as [LoggingDriver.logActionWithScreenshot] for Maestro-based drivers.
   */
  private fun logScreenStateAfterExecution(
    tools: List<TrailblazeTool>,
    startTime: kotlinx.datetime.Instant,
    executionTimeMs: Long,
    traceId: TraceId? = null,
  ) {
    try {
      val screenStateResult = runBlocking { rpcClient.getScreenState() }
      if (screenStateResult is RpcResult.Success) {
        val screenState = ComposeRpcScreenState(screenStateResult.data)
        val session = sessionProvider.invoke()

        val screenshotFilename =
          if (screenState.screenshotBytes != null) {
            trailblazeLogger.logScreenState(session, screenState)
          } else {
            null
          }

        val action = mapToolsToDriverAction(tools)

        val log =
          TrailblazeLog.AgentDriverLog(
            viewHierarchy = screenState.viewHierarchy,
            trailblazeNodeTree = screenState.trailblazeNodeTree,
            screenshotFile = screenshotFilename,
            action = action,
            durationMs = executionTimeMs,
            timestamp = startTime,
            session = session.sessionId,
            deviceWidth = screenState.deviceWidth,
            deviceHeight = screenState.deviceHeight,
            traceId = traceId,
          )
        trailblazeLogger.log(session, log)
      }
    } catch (e: Exception) {
      // Don't fail the tool execution just because screenshot logging failed
      Console.log("Warning: Failed to log screen state after tool execution: ${e.message}")
    }
  }

  /**
   * Maps the first tool in a batch to a [AgentDriverAction] for logging. Uses specific action
   * types where possible (e.g., [AgentDriverAction.EnterText] for type tools) and falls back
   * to [AgentDriverAction.OtherAction] for compose-specific tools.
   */
  private fun mapToolsToDriverAction(tools: List<TrailblazeTool>): AgentDriverAction {
    val firstTool = tools.firstOrNull() ?: return AgentDriverAction.OtherAction(AgentActionType.TAP_POINT)
    return when (firstTool) {
      is ComposeTypeTool -> AgentDriverAction.EnterText(firstTool.text)
      is ComposeClickTool -> AgentDriverAction.OtherAction(AgentActionType.TAP_POINT)
      is ComposeScrollTool -> AgentDriverAction.OtherAction(AgentActionType.SWIPE)
      is ComposeVerifyTextVisibleTool ->
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify text visible: ${firstTool.text}",
          x = 0,
          y = 0,
          isVisible = true,
          textToDisplay = firstTool.text,
        )

      is ComposeVerifyElementVisibleTool ->
        AgentDriverAction.AssertCondition(
          conditionDescription = "Verify element visible: ${firstTool.testTag}",
          x = 0,
          y = 0,
          isVisible = true,
        )

      else -> AgentDriverAction.OtherAction(AgentActionType.TAP_POINT)
    }
  }

  override fun close() {
    rpcClient.close()
  }
}
