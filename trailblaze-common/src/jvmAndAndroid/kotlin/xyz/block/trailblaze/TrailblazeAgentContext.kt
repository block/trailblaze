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
import xyz.block.trailblaze.toolcalls.SensitiveArgsTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.buildLogSafeResolvedPayload
import xyz.block.trailblaze.toolcalls.scrubSensitiveValues
import xyz.block.trailblaze.toolcalls.withSensitiveArgsRedacted
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
  /**
   * The tool AS AUTHORED, when the dispatch boundary's memory interpolation rewrote it before
   * execution ([tool] is then the resolved instance). Callers pass it only on an actual rewrite
   * (`rawTool.takeIf { it !== memoryResolvedTool }`), and it is always the same concrete class
   * as [tool]. Drives the raw/resolved split on the emitted log — see
   * [TrailblazeLog.TrailblazeToolLog.rawTrailblazeTool].
   */
  rawTool: TrailblazeTool? = null,
) {
  // If the tool stamped an override (e.g. TapOnPointTrailblazeTool upgrading to
  // TapOnTrailblazeTool), take the recorded instance AND toolName from the override together:
  // downstream recording code keys serialization by toolName while encoding the instance, so
  // mixing an override's instance with the original's toolName produces a type/serializer
  // mismatch. `isRecordable` is NOT read from the override here — it's computed separately just
  // below (from the override's annotation, unless a nested dispatch forces it false).
  val recordedToolOverride = context.recordedToolOverride
  val recordedTool: TrailblazeTool = recordedToolOverride ?: tool
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
  // Raw/resolved split. An override suppresses the raw payload: the override is a deliberate
  // whole-tool replacement (possibly a different class), and a log whose `toolName` comes from
  // the override but whose raw payload is the pre-override class would hand the recording
  // generator a name/args mismatch. Without an override, `trailblazeTool` is the log-safe
  // RESOLVED form (sensitive tokens kept literal — see [buildLogSafeResolvedPayload]) and
  // `rawTrailblazeTool` the authored form, elided when scrubbing left them identical.
  // The authored form may be a raw-args wrapper that doesn't itself implement
  // [SensitiveArgsTrailblazeTool], so its payload is additionally masked with the EXECUTED
  // instance's declared sensitive args (`toLogPayload()` only self-redacts).
  val rawPayload = if (recordedToolOverride == null) {
    rawTool?.toLogPayload()?.withSensitiveArgsRedacted(tool.sensitiveArgNamesOrEmpty())
  } else {
    null
  }
  val resolvedPayload = if (rawPayload != null) {
    buildLogSafeResolvedPayload(rawPayload, memory)
  } else {
    recordedTool.toLogPayload()
  }
  val toolLog =
    TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = resolvedPayload,
      rawTrailblazeTool = rawPayload?.takeIf { it != resolvedPayload },
      toolName = recordedTool.getToolNameFromAnnotation(),
      // Scrub here, not only on the returned result: the dispatch loop applies
      // `withAuthoredFailureContent` to the value it RETURNS toward the LLM, but the raw result is
      // logged before that (each driver's `executeTool` logs then returns), so a resolved
      // rememberSensitive value a tool spliced into its error message would otherwise land in the
      // persisted session log. The args payload is already scrubbed via `buildLogSafeResolvedPayload`.
      exceptionMessage = (result as? TrailblazeToolResult.Error)?.errorMessage?.let { scrubSensitiveValues(it, memory) },
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
 * The sensitive-arg names the EXECUTED tool declares, for masking the authored (`rawTool`)
 * payload. The authored form is often a raw-args wrapper (name + JSON) that doesn't implement
 * [SensitiveArgsTrailblazeTool] itself, so `toLogPayload()`'s self-redaction can't fire for it —
 * the executed class instance is the source of truth for which args are secret.
 */
private fun TrailblazeTool.sensitiveArgNamesOrEmpty(): Set<String> =
  (this as? SensitiveArgsTrailblazeTool)?.sensitiveArgNames ?: emptySet()

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
  /** Same contract as the context-carrying overload's `rawTool` (no override interplay here). */
  rawTool: TrailblazeTool? = null,
) {
  val session = sessionProvider.invoke()
  // Same masking rationale as the context-carrying overload: the authored wrapper doesn't
  // implement [SensitiveArgsTrailblazeTool], so apply the executed instance's declared args.
  val rawPayload = rawTool?.toLogPayload()?.withSensitiveArgsRedacted(tool.sensitiveArgNamesOrEmpty())
  val resolvedPayload = if (rawPayload != null) {
    buildLogSafeResolvedPayload(rawPayload, memory)
  } else {
    tool.toLogPayload()
  }
  val toolLog = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = resolvedPayload,
    rawTrailblazeTool = rawPayload?.takeIf { it != resolvedPayload },
    toolName = tool.getToolNameFromAnnotation(),
    // Same rationale as the context-carrying overload: scrub the logged message so a resolved
    // rememberSensitive value spliced into an error can't reach the persisted log.
    exceptionMessage = (result as? TrailblazeToolResult.Error)?.errorMessage?.let { scrubSensitiveValues(it, memory) },
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
