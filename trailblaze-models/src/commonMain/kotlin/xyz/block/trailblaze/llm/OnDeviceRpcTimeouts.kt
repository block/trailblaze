package xyz.block.trailblaze.llm

/**
 * Timeout budget for on-device RPC dispatches where the caller blocks on completion.
 *
 * A synchronous [RunYamlRequest] (one with `awaitCompletion = true`, the default) has two
 * layered timeouts:
 * - [HANDLER_AWAIT_CAP_MS] — on-device upper bound on how long the handler will wait for the
 *   launched job to finish before cancelling it and returning a structured timeout response.
 * - [HTTP_REQUEST_CAP_MS] — host-side per-request HTTP read timeout for the same call.
 *
 * The HTTP timeout must always exceed the handler timeout so the handler gets to emit a
 * proper timeout response (instead of the socket closing first and the host seeing a bare
 * network error). That invariant is encoded here by definition — [HTTP_REQUEST_CAP_MS] is
 * [HANDLER_AWAIT_CAP_MS] plus a fixed buffer, so changing one automatically changes the
 * other and no comment-level coupling can drift.
 */
object OnDeviceRpcTimeouts {
  /**
   * Cap on how long the on-device handler will await a sync [RunYamlRequest].
   *
   * Sized to cover whole-trail dispatches (CLI, desktop app) that can legitimately run for
   * several minutes — agentic runs with AI reflection and cold app launches are the slow
   * end. Past this cap the handler cancels the launched job and returns a structured
   * timeout response.
   */
  const val HANDLER_AWAIT_CAP_MS = 900_000L // 15 min

  /**
   * Buffer between the handler cap and the HTTP read timeout. Gives the handler room to
   * finish its cancel-and-cleanup work and serialize a response before the socket closes.
   */
  const val HTTP_BUFFER_MS = 300_000L // 5 min

  /**
   * Per-request HTTP read timeout for sync [RunYamlRequest] dispatches. Strictly greater
   * than [HANDLER_AWAIT_CAP_MS] by construction.
   */
  const val HTTP_REQUEST_CAP_MS = HANDLER_AWAIT_CAP_MS + HTTP_BUFFER_MS // 20 min
}
