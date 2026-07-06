package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Response from a YAML test execution request.
 */
@Serializable
data class RunYamlResponse(
  /**
   * The session ID for this test execution.
   * Can be used to track the test progress or cancel it.
   */
  val sessionId: SessionId,

  /**
   * Terminal outcome of the execution when [RunYamlRequest.awaitCompletion] was `true`.
   *
   * - `true` — execution reached a successful terminal state on-device.
   * - `false` — execution failed or was cancelled (see [errorMessage]).
   * - `null` — fire-and-forget dispatch (the response was returned immediately after
   *   accepting the request; the caller should subscribe to progress events for the
   *   terminal state).
   */
  val success: Boolean? = null,

  /** Non-null when [success] is `false` and a specific error message is available. */
  val errorMessage: String? = null,

  /**
   * Snapshot of the on-device agent's `AgentMemory` at completion time. The host
   * replaces its `AgentMemory` with this map on RPC success so subsequent tools (host or
   * device) see writes made by on-device tools — including direct
   * `context.memory.remember(...)` writes from on-device runtime TypeScript handlers.
   *
   * Sent as the FULL post-execution memory state rather than a diff: deletes flow as
   * absences, the host can compute "what changed" by comparing what it sent vs. what came
   * back, and the device-side handler doesn't need to track which keys were written vs.
   * read-only.
   *
   * Must be `emptyMap()` for fire-and-forget responses (i.e. when the corresponding
   * request had `awaitCompletion = false`): memory sync requires a round-trip and a
   * fire-and-forget response is returned before any tool executes.
   */
  val memorySnapshot: Map<String, String> = emptyMap(),

  /**
   * Keys that on-device tools EXPLICITLY deleted (`ctx.memory.delete(...)`) during this RPC. The
   * host applies these as removals on top of the merged [memorySnapshot], so an explicit deletion
   * of a host-seeded key propagates back — whereas a key merely absent from [memorySnapshot] is
   * preserved (the merge that fixed a remembered value being wiped between steps).
   *
   * Defaults to `emptyList()` for back-compat: an older device omits the field and the host applies
   * no deletions. Can only be non-empty when [memorySnapshot] can be — see the init-block require.
   */
  val memoryDeletions: List<String> = emptyList(),

  /**
   * The last successfully-executed tool's [xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success.message]
   * payload, mirrored back to the host so scripted-tool authors composing dual-mode primitives
   * (`android_adbShell`, `android_sendBroadcast`, `mobile_listInstalledApps`) via
   * `client.callTool(...)` receive the same stdout / broadcast result / installed-app JSON they
   * would get from the direct host-side actual.
   *
   * ## "Last successful tool" semantics
   *
   * Mirrors the on-device dispatch loop's `lastSuccessResult`:
   *
   *  - **Single-tool RPC dispatches** (the host-driver case for
   *    `HostOnDeviceRpcTrailblazeAgent.executeToolViaRpc` and the scripted-tool
   *    `client.callTool(...)` path) pass the tool's `Success.message` through 1:1.
   *  - **Multi-tool YAML** (when a single [RunYamlRequest] carries a trail with multiple
   *    tools) carries ONLY the final successful tool's message — intermediate tools'
   *    messages are silently discarded. Authors who need per-tool payloads must dispatch
   *    one tool per request.
   *
   * Null when no tool produced a Success (fire-and-forget, run failed before any Success,
   * or every tool's `Success` was constructed without a message — action-style `Success()`
   * defaults).
   */
  val toolMessage: String? = null,

  /**
   * The last successfully-executed tool's [xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success.structuredContent]
   * payload. Paired with [toolMessage] — same "last success wins" semantics — but carries the
   * typed JSON return value produced by MCP scripted tools (subprocess or on-device QuickJS
   * bundle) whose handler returns a non-string typed result. The TS SDK's
   * `client.tools.<name>(args)` proxy unwraps this into the typed `result` declared in
   * `TrailblazeToolMap`; null means "no structured payload" and the caller falls back to
   * [toolMessage].
   */
  val toolStructuredContent: JsonElement? = null,

  /**
   * How many [xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog] entries the
   * on-device dispatch emitted while running this request's tool(s).
   *
   * Used by the host RPC agent (`HostOnDeviceRpcTrailblazeAgent`, in `:trailblaze-host`)
   * to avoid double-logging an on-device tool in the session report (#3818). The host serializes
   * one tool per [RunYamlRequest] and dispatches it over RPC; the device runs it and (in the
   * common path) emits its own `TrailblazeToolLog`, which is pulled back to the host and merged
   * into the same session. Without this signal the host *also* emits a catch-all
   * `logToolExecution` after the RPC returns, so the single execution renders twice in the
   * timeline.
   *
   *  - `> 0` — the device already logged the tool; the host skips its own emit so the tool
   *    appears exactly once.
   *  - `0` — the on-device path emitted no tool log (e.g. a tool whose dispatch bypasses the
   *    device emit site and only produces driver-action logs). The host still emits one so the
   *    dispatch stays visible to recording / reports.
   *
   * Always `0` for fire-and-forget dispatches (no tool has executed yet).
   */
  val onDeviceToolLogCount: Int = 0,

  /**
   * Set by the on-device server when this run's terminal failure (or its timeout-time liveness
   * probe) was classified as the NON-recoverable Android `UiAutomation` stale-handle wedge —
   * the state that only a server relaunch can clear (see
   * [xyz.block.trailblaze.util.UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature]).
   *
   * This is the structured at-the-source signal: the host reads this one field rather than
   * re-deriving the wedge from a string-matched error message downstream. It rides only the
   * `awaitCompletion = true` responses — the inline `success = false` / timeout shapes — so a
   * mid-trail or pre-action wedge surfaces typed instead of as plain text the host has to
   * recognize.
   *
   * A `Boolean` (not an enum) is deliberate: `RunYamlResponse` decodes with
   * `ignoreUnknownKeys = true` but `coerceInputValues` unset, so a future enum constant a newer
   * device emits would fail to decode on an older host. A boolean is wire-safe and
   * forward-compatible across host/device version skew.
   *
   * Always `false` for fire-and-forget dispatches (no terminal state is known when the response
   * is returned) and for ordinary failures (assertion, element-not-found, transport).
   */
  val nonRecoverableWedge: Boolean = false,
) {
  init {
    require(success != null || memorySnapshot.isEmpty()) {
      "RunYamlResponse for fire-and-forget dispatch (success=null) cannot carry a " +
        "memorySnapshot — memory sync requires a completion event."
    }
    require(success != null || memoryDeletions.isEmpty()) {
      "RunYamlResponse for fire-and-forget dispatch (success=null) cannot carry " +
        "memoryDeletions — memory sync requires a completion event."
    }
    require(success != null || (toolMessage == null && toolStructuredContent == null)) {
      "RunYamlResponse for fire-and-forget dispatch (success=null) cannot carry a tool " +
        "payload — no tool has executed yet."
    }
    require(success != null || onDeviceToolLogCount == 0) {
      "RunYamlResponse for fire-and-forget dispatch (success=null) cannot report a non-zero " +
        "onDeviceToolLogCount — no tool has executed yet."
    }
  }
}
