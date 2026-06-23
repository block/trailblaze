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
) {
  init {
    require(success != null || memorySnapshot.isEmpty()) {
      "RunYamlResponse for fire-and-forget dispatch (success=null) cannot carry a " +
        "memorySnapshot — memory sync requires a completion event."
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
