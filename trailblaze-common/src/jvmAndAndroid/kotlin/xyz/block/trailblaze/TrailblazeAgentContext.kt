package xyz.block.trailblaze

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.toolcalls.isVerificationToolInstance
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.toolcalls.toLogPayloads
import xyz.block.trailblaze.util.Console

/**
 * Common context properties shared by all [TrailblazeAgent] implementations.
 *
 * Both [MaestroTrailblazeAgent] and PlaywrightTrailblazeAgent implement this
 * interface, allowing [TrailblazeKoogLlmClientHelper] to work with any agent
 * without a hard cast to a specific implementation.
 */
interface TrailblazeAgentContext {
  val trailblazeLogger: TrailblazeLogger
  val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo
  val sessionProvider: TrailblazeSessionProvider
  val memory: AgentMemory
}

fun TrailblazeAgentContext.logToolExecution(
  tool: ExecutableTrailblazeTool,
  timeBeforeExecution: Instant,
  context: TrailblazeToolExecutionContext,
  result: TrailblazeToolResult,
  dispatchedHostSide: Boolean = false,
) {
  // If the tool stamped an override (e.g. TapOnPointTrailblazeTool upgrading to
  // TapOnTrailblazeTool), take the recorded instance AND toolName from the override together:
  // downstream recording code keys serialization by toolName while encoding the instance, so
  // mixing an override's instance with the original's toolName produces a type/serializer
  // mismatch. `isRecordable` is NOT read from the override here — it's computed separately just
  // below (from the override's annotation, unless a nested dispatch forces it false).
  val recordedTool: TrailblazeTool = context.recordedToolOverride ?: tool
  // A nested `ctx.tools.X()` sub-call is never recordable on its own — only the outermost parent
  // call is the replayable step. See `TrailblazeToolExecutionContext.nestedDispatchDepth` for the
  // full rationale (scope, N-deep counter, parallel-callback safety). The log is still emitted; only
  // its recordability changes. `TRAILBLAZE_DISABLE_NESTED_DISPATCH_RECORDING_FILTER` reverts to
  // annotation-only recordability (the generator's `dropNestedToolCalls` heuristic still applies).
  val isRecordable = if (context.nestedDispatchDepth.get() > 0 && !isNestedDispatchRecordingFilterDisabled()) {
    false
  } else {
    recordedTool.getIsRecordableFromAnnotation()
  }
  val toolLog =
    TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = recordedTool.toLogPayload(),
      toolName = recordedTool.getToolNameFromAnnotation(),
      exceptionMessage = (result as? TrailblazeToolResult.Error)?.errorMessage,
      successful = result.isSuccess(),
      durationMs =
        Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds(),
      timestamp = timeBeforeExecution,
      traceId = context.traceId,
      session = sessionProvider.invoke().sessionId,
      isRecordable = isRecordable,
      isVerification = recordedTool.isVerificationToolInstance(),
      dispatchedHostSide = dispatchedHostSide,
    )
  // Clear the override after consuming it. The execution context is reused across every
  // tool in a single runTrailblazeTools(...) batch, so a stale override would bleed into
  // the next tool's log and mis-record it.
  context.recordedToolOverride = null

  val toolLogJson = TrailblazeJsonInstance.encodeToString(toolLog)
  Console.log("toolLogJson: $toolLogJson")

  val session = sessionProvider.invoke()
  trailblazeLogger.log(session, toolLog)
}

/**
 * Kill-switch for the nested-dispatch recording filter: forces `isRecordable` back to the tool's
 * annotation value even inside a nested `ctx.tools.*` dispatch. Read per call so it flips on a
 * running daemon. Degrades gracefully — the generator's `dropNestedToolCalls` span heuristic still
 * drops most nested internals — so it's a safe one-line revert if the depth filter ever mis-marks a
 * tool in the field. `1` or `true` (case-insensitive) disables. Mirrors the env-read style of
 * `TRAILBLAZE_DISABLE_BATCHED_TOOL_EXECUTION`.
 */
private fun isNestedDispatchRecordingFilterDisabled(): Boolean {
  val raw = System.getenv("TRAILBLAZE_DISABLE_NESTED_DISPATCH_RECORDING_FILTER") ?: return false
  return raw == "1" || raw.equals("true", ignoreCase = true)
}

fun TrailblazeAgentContext.logDelegatingTool(
  tool: DelegatingTrailblazeTool,
  traceId: TraceId?,
  executableTools: List<ExecutableTrailblazeTool>,
) {
  val session = sessionProvider.invoke()
  trailblazeLogger.log(
    session,
    TrailblazeLog.DelegatingTrailblazeToolLog(
      trailblazeTool = tool.toLogPayload(),
      toolName = tool.getToolNameFromAnnotation(),
      executableTools = executableTools.toLogPayloads(),
      session = session.sessionId,
      traceId = traceId,
      timestamp = Clock.System.now(),
    ),
  )
}

/**
 * Variant of [logToolExecution] for agents that already hold a [TraceId] (rather than a full
 * [TrailblazeToolExecutionContext]) and dispatch arbitrary [TrailblazeTool]s — Compose's RPC
 * driver fits both shapes. Encapsulates the [toLogPayload] conversion so call sites work with
 * the original tool instance and don't have to know the persisted-log shape.
 *
 * NOTE: with no context, this overload cannot read [TrailblazeToolExecutionContext.nestedDispatchDepth],
 * so it does NOT stamp nested `ctx.tools.*` internals non-recordable. Its callers — Compose RPC
 * (which also wires a `nestedToolExecutor` that deliberately doesn't bump depth) and the
 * `HostOnDeviceRpcTrailblazeAgent` catch-all — therefore still rely on the generator's
 * `dropNestedToolCalls` span heuristic to keep composite internals out of recordings. The
 * deterministic depth filter covers the Maestro/Playwright drivers that route through the
 * context-carrying overload above.
 */
fun TrailblazeAgentContext.logToolExecution(
  tool: TrailblazeTool,
  timeBeforeExecution: Instant,
  traceId: TraceId,
  result: TrailblazeToolResult,
  dispatchedHostSide: Boolean = false,
) {
  val session = sessionProvider.invoke()
  val toolLog = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = tool.toLogPayload(),
    toolName = tool.getToolNameFromAnnotation(),
    exceptionMessage = (result as? TrailblazeToolResult.Error)?.errorMessage,
    successful = result.isSuccess(),
    durationMs =
      Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds(),
    timestamp = timeBeforeExecution,
    traceId = traceId,
    session = session.sessionId,
    isRecordable = tool.getIsRecordableFromAnnotation(),
    isVerification = tool.isVerificationToolInstance(),
    dispatchedHostSide = dispatchedHostSide,
  )
  trailblazeLogger.log(session, toolLog)
}
