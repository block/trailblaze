package xyz.block.trailblaze.events

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.util.Console

/**
 * Generic, producer-agnostic writer for the [SessionEvents] artifact format.
 *
 * Writes one NDJSON file per `(name, style)` stream under `<sessionDir>/events/<name>.<style>.ndjson`
 * (see [SessionEvents] for the contract). A writer is opened lazily the first time a given stream
 * emits, every line is flushed on write (so a crash mid-run still leaves a valid prefix), and write
 * failures are logged, never thrown, so a misbehaving capture can't break the run.
 *
 * This is the open, pluggable replacement for any integration-specific per-stream file sink: a
 * producer constructs one [FileEventSink] per session and calls [append] (for the default
 * `{ timeMs, data }` envelope) or [appendRaw] (to write a pre-serialized rich line such as a
 * `NetworkEvent`). Nothing here is named after, or aware of, any specific integration.
 */
class FileEventSink(
  private val sessionDir: File,
  private val logLabel: String = sessionDir.name,
) : AutoCloseable {

  private val eventsDir = File(sessionDir, SessionEvents.DIR_NAME)
  private val writeLock = Any()
  private val writers = mutableMapOf<String, BufferedWriter>()
  private var closed = false

  /**
   * Append one default-envelope record to the `(name, style)` stream. [data] is the style-specific
   * payload; [timeMs] is the host epoch-millis timestamp used to interleave the timeline.
   */
  fun append(name: String, style: String, timeMs: Long, data: JsonElement) {
    appendRaw(name, style, JSON.encodeToString(SessionEvent.serializer(), SessionEvent(timeMs, data)))
  }

  /**
   * Append a single, already-serialized NDJSON line to the `(name, style)` stream. Use this when the
   * producer already owns a rich line schema (e.g. `NetworkEvent`) and doesn't need the envelope.
   * The line must contain an ordering field ([SessionEvents.TIME_FIELD] or
   * [SessionEvents.LEGACY_TIME_FIELD]) for the reader to place it on the timeline.
   *
   * One record per call: a line carrying a raw newline (`\n`/`\r`) would split into multiple NDJSON
   * records — corrupting this stream — so such a line is rejected (logged, not written) rather than
   * silently fragmenting the file. Valid JSON never contains a raw newline (they're escaped inside
   * strings), so this only fires on malformed input.
   */
  fun appendRaw(name: String, style: String, jsonLine: String) {
    if (jsonLine.indexOf('\n') >= 0 || jsonLine.indexOf('\r') >= 0) {
      Console.error("[events] $logLabel: dropping line with embedded newline for $name.$style")
      return
    }
    synchronized(writeLock) {
      if (closed) return
      val fileName = SessionEvents.fileName(name, style)
      val writer =
        writers[fileName] ?: openWriter(fileName)?.also { writers[fileName] = it } ?: return
      runCatching {
          writer.append(jsonLine)
          writer.append('\n')
          writer.flush()
        }
        .onFailure { Console.error("[events] $logLabel: write failed for $fileName: ${it.message}") }
    }
  }

  private fun openWriter(fileName: String): BufferedWriter? =
    runCatching {
        if (!eventsDir.exists()) eventsDir.mkdirs()
        // Append mode so re-attaching to a session in a later stage doesn't clobber prior events.
        // Explicit UTF-8 (FileWriter would use the platform default) so the bytes written here match
        // the UTF-8 the reader assumes for both its byte caps and decoding.
        BufferedWriter(
          OutputStreamWriter(
            FileOutputStream(File(eventsDir, fileName), /* append= */ true),
            Charsets.UTF_8,
          )
        )
      }
      .getOrElse {
        Console.error("[events] $logLabel: cannot open $fileName: ${it.message}")
        null
      }

  override fun close() {
    synchronized(writeLock) {
      if (closed) return
      closed = true
      writers.values.forEach { w -> runCatching { w.flush() }.also { runCatching { w.close() } } }
      val count = writers.size
      writers.clear()
      if (count > 0) {
        Console.log("[events] $logLabel: wrote $count event stream file(s) under ${SessionEvents.DIR_NAME}/")
      }
    }
  }

  companion object {
    private val JSON: Json = Json {
      encodeDefaults = false
      explicitNulls = false
    }
  }
}
