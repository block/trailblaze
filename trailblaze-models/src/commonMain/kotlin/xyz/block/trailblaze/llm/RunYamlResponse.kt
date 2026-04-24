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
)
