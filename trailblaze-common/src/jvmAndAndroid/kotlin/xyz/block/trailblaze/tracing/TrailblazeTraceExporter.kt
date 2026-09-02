package xyz.block.trailblaze.tracing

import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.util.Console

/**
 * Centralizes trace export logic: exports trace JSON from [TrailblazeTracer], posts it to the
 * server, and optionally falls back to a disk write if the server is unavailable.
 */
object TrailblazeTraceExporter {

  /**
   * Exports the current trace data and sends it to the server. Falls back to [writeToDisk] if the
   * server post fails or returns a non-OK status. Always clears the tracer when done.
   *
   * @param sessionId The session to associate the trace with.
   * @param client The log server client to post the trace to.
   * @param isServerAvailable Whether the server is known to be reachable.
   * @param writeToDisk Optional fallback that writes the trace JSON to disk.
   * @param onDeviceClock Whether these timestamps came from a device's own clock rather than the
   *   host's. Only the producer knows: every process that records part of a run uploads through the
   *   same route, so the receiver would otherwise have to guess, and guessing wrong takes a whole
   *   process's spans off the timeline.
   */
  suspend fun exportAndSave(
    sessionId: SessionId,
    client: TrailblazeLogServerClient,
    isServerAvailable: Boolean,
    writeToDisk: ((traceJson: String) -> Unit)? = null,
    onDeviceClock: Boolean = false,
  ) {
    // Drains rather than exporting-then-clearing: a flush keeps the recording's trace id, so a
    // session that exports more than once files both halves under one trace instead of two
    // unrelated ones.
    val traceJson = TrailblazeTracer.traceRecorder.drain()
    try {
      if (isServerAvailable) {
        val sent = client.sendTrace(sessionId, traceJson, onDeviceClock)
        if (sent) {
          Console.info("Trace posted to server for session ${sessionId.value}")
        } else {
          Console.log("Trace upload failed for session ${sessionId.value}, falling back to disk")
          writeToDisk?.invoke(traceJson)
        }
      } else {
        writeToDisk?.invoke(traceJson)
      }
    } catch (e: Exception) {
      Console.log("Failed to post trace to server: ${e.message}")
      try {
        writeToDisk?.invoke(traceJson)
      } catch (diskError: Exception) {
        Console.log("Failed to write trace to disk: ${diskError.message}")
      }
    }
  }
}
