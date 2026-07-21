package xyz.block.trailblaze.events

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.util.Console

/** Producer-neutral [SessionEvents] persistence shared by optional integrations. */
class FileEventSink(
  private val sessionDir: File,
  private val logLabel: String = sessionDir.name,
) : AutoCloseable {

  private val eventsDir = File(sessionDir, SessionEvents.DIR_NAME)
  private val writeLock = Any()
  private val writers = mutableMapOf<String, BufferedWriter>()
  private var closed = false
  private var closeFailure: Throwable? = null

  fun append(name: String, timeMs: Long, data: JsonElement) {
    appendRaw(name, JSON.encodeToString(SessionEvent.serializer(), SessionEvent(timeMs, data)))
  }

  /** Propagates persistence failures when partial evidence would be misleading. */
  fun appendChecked(name: String, timeMs: Long, data: JsonElement) {
    val line = JSON.encodeToString(SessionEvent.serializer(), SessionEvent(timeMs, data))
    require(line.indexOf('\n') < 0 && line.indexOf('\r') < 0)
    synchronized(writeLock) {
      check(!closed) { "Event sink for '$logLabel' is closed" }
      val fileName = SessionEvents.fileName(name)
      val writer = writers[fileName] ?: openWriterOrThrow(fileName).also { writers[fileName] = it }
      writer.append(line)
      writer.append('\n')
      writer.flush()
    }
  }

  /** Rejects embedded newlines because they would silently create extra NDJSON records. */
  fun appendRaw(name: String, jsonLine: String) {
    if (jsonLine.indexOf('\n') >= 0 || jsonLine.indexOf('\r') >= 0) {
      Console.error("[events] $logLabel: dropping line with embedded newline for $name")
      return
    }
    synchronized(writeLock) {
      if (closed) return
      val fileName = SessionEvents.fileName(name)
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
        // Reattachment must preserve prior stages; explicit UTF-8 keeps byte caps deterministic.
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

  private fun openWriterOrThrow(fileName: String): BufferedWriter {
    if (!eventsDir.exists() && !eventsDir.mkdirs()) {
      error("Could not create events directory: ${eventsDir.absolutePath}")
    }
    return BufferedWriter(
      OutputStreamWriter(
        FileOutputStream(File(eventsDir, fileName), /* append= */ true),
        Charsets.UTF_8,
      )
    )
  }

  override fun close() {
    runCatching { closeChecked() }
      .onFailure { Console.error("[events] $logLabel: close failed: ${it.message}") }
  }

  /** Retains aggregate close failures so later finalization cannot mask incomplete evidence. */
  fun closeChecked() {
    synchronized(writeLock) {
      if (closed) {
        closeFailure?.let { throw it }
        return
      }
      closed = true
      val failures = mutableListOf<Throwable>()
      writers.values.forEach { writer ->
        runCatching { writer.flush() }.onFailure(failures::add)
        runCatching { writer.close() }.onFailure(failures::add)
      }
      val count = writers.size
      writers.clear()
      if (count > 0) {
        Console.log(
          "[events] $logLabel: wrote $count event stream file(s) under ${SessionEvents.DIR_NAME}/"
        )
      }
      if (failures.isNotEmpty()) {
        val failure =
          IllegalStateException(
            "Could not finalize $count event stream file(s) for '$logLabel': " +
              failures.joinToString { it.message ?: it::class.java.simpleName }
          )
        failures.forEach(failure::addSuppressed)
        closeFailure = failure
        throw failure
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
