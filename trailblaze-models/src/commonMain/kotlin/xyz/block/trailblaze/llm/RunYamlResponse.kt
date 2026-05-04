package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
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
) {
  init {
    require(success != null || memorySnapshot.isEmpty()) {
      "RunYamlResponse for fire-and-forget dispatch (success=null) cannot carry a " +
        "memorySnapshot — memory sync requires a completion event."
    }
  }
}
