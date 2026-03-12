package xyz.block.trailblaze.tracing

import io.ktor.http.HttpStatusCode
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
   */
  suspend fun exportAndSave(
    sessionId: SessionId,
    client: TrailblazeLogServerClient,
    isServerAvailable: Boolean,
    writeToDisk: ((traceJson: String) -> Unit)? = null,
  ) {
    val traceJson = TrailblazeTracer.exportJson()
    try {
      if (isServerAvailable) {
        val response = client.postTrace(sessionId, traceJson)
        if (response.status == HttpStatusCode.OK) {
          Console.info("Trace posted to server for session ${sessionId.value}")
        } else {
          Console.log("Trace POST returned ${response.status} for session ${sessionId.value}, falling back to disk")
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
    } finally {
      TrailblazeTracer.clear()
    }
  }
}
