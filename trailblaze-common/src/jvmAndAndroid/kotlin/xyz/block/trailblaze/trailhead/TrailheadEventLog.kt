package xyz.block.trailblaze.trailhead

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.events.FileEventSink
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.util.Console

/**
 * Writes the `trailhead` session event stream ([TrailheadEventStream]) and mirrors each record to
 * the console, so a reader of either sees the same trail of what the trailhead received and did.
 * Best effort: a runner with no session directory still gets the console line, and so does a
 * session whose directory was never created (`--no-logging`), which writes no files.
 *
 * In a multi-device trail every record names its device binding in [DEVICE_FIELD], so a query from
 * one device's `teardown` skips the others' records.
 */
internal object TrailheadEventLog {

  fun append(
    context: TrailblazeToolExecutionContext,
    type: String,
    timeMs: Long = System.currentTimeMillis(),
    fields: JsonObjectBuilder.() -> Unit,
  ) {
    val data = buildJsonObject {
      put("type", type)
      TrailheadRuns.deviceNameOf(context)?.let { put(DEVICE_FIELD, it) }
      fields()
    }
    Console.log("[Teardown] $data")
    val sessionDir = sessionDir(context)?.takeIf { it.isDirectory } ?: return
    runCatching {
      FileEventSink(sessionDir).use { it.append(TrailheadEventStream.NAME, timeMs, data) }
    }.onFailure { Console.error("[Teardown] could not write the trailhead event: ${it.message}") }
  }

  fun sessionDir(context: TrailblazeToolExecutionContext): File? {
    val provider = context.sessionDirProvider ?: return null
    val sessionId: SessionId = runCatching { context.sessionProvider.invoke().sessionId }.getOrNull() ?: return null
    return runCatching { provider(sessionId) }.getOrNull()
  }

  const val DEVICE_FIELD: String = "device"

  fun JsonObjectBuilder.putIfNotNull(key: String, value: String?) {
    if (value != null) put(key, value)
  }

  fun JsonObjectBuilder.putObject(key: String, value: JsonObject?) {
    if (value != null) put(key, value)
  }
}
