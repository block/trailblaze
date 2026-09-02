package xyz.block.trailblaze.report.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The one `trace.json` a session owns, written by more than one recorder.
 *
 * A run is traced in as many processes as it touches. The host records the agent, the LLM calls and
 * the tools it dispatches; a device records what the driver actually did — the accessibility
 * capture, the view-hierarchy walk — and ships that back over HTTP or the log socket. All of it
 * belongs on one timeline, and all of it already shares a trace id, so it can be assembled. But
 * every writer used to `writeText` the same path, so the last one to finish decided which half of
 * the run you got to see, and the device's half is the half nobody had.
 *
 * [merge] is that path's only writer.
 */
object SessionTraceFile {

  const val FILE_NAME: String = "trace.json"

  /**
   * Adds [incomingJson]'s events to [traceFile], keeping what the file already holds.
   *
   * Duplicates are dropped by whole-event equality. That is the only key available — an event from a
   * recording old enough to predate span ids has nothing else unique about it — and it is the right
   * one for the duplicate that actually happens: the same batch arriving twice, because a device
   * retried an upload or posted a trace the host had already received. Two genuinely distinct events
   * agreeing on name, category, thread, microsecond start AND duration would collapse into one; on a
   * single thread at microsecond resolution that does not occur.
   *
   * Set [onDeviceClock] for a batch recorded on a device: its timestamps are stamped by that
   * device's own wall clock, which drifts from the host's by whole seconds. The events are marked so
   * the profiler puts them on the Device lane and keeps their skew out of the session window,
   * instead of reading them as host spans and stretching the whole profile by the drift.
   *
   * Read-modify-write happens under a lock and publishes through a temp file, so two writers
   * finishing together cannot each read the same array and then overwrite one another — which would
   * recreate the loss this exists to prevent — and a reader can never catch a half-written array.
   *
   * Best-effort about damaged input, because refusing to record is worse than recording imperfectly.
   * An event that is not a JSON object is skipped rather than taking its whole batch down. A payload
   * that is not a JSON array at all is parked next to the trace as `trace.json.raw` when a real
   * trace is already there — one malformed upload must not erase the half that parsed — and written
   * through as-is only when there is nothing to lose.
   */
  fun merge(traceFile: File, incomingJson: String, onDeviceClock: Boolean = false) {
    synchronized(WRITE_LOCK) {
      val incoming = parseEvents(incomingJson)?.map { if (onDeviceClock) it.markedDeviceClock() else it }
      if (incoming == null) {
        parkUnreadable(traceFile, incomingJson)
        return
      }

      val existing = if (traceFile.exists()) parseEvents(traceFile.readText()).orEmpty() else emptyList()

      val merged = LinkedHashSet<JsonObject>(existing.size + incoming.size)
      merged.addAll(existing)
      merged.addAll(incoming)

      // Stable, so events sharing a start time keep the order they were recorded in — the parent of a
      // zero-duration child starts at the same microsecond as the child.
      val ordered = merged.sortedBy { it.startMicros() }

      publish(traceFile, JSON.encodeToString(JsonArray(ordered)))
    }
  }

  /** Every event in [traceFile], or empty when it is absent or unreadable. */
  fun read(traceFile: File): List<JsonObject> =
    if (traceFile.exists()) parseEvents(traceFile.readText()).orEmpty() else emptyList()

  /**
   * A payload that could not be read as an event array.
   *
   * Kept rather than dropped, because it is the only copy of whatever that process recorded, and it
   * is kept BESIDE an existing trace rather than over it: a device that uploads something malformed
   * must not be able to delete the host's half. With no trace there yet, writing it through is what
   * every caller did before this existed, and leaves the payload somewhere a person will find it.
   */
  private fun parkUnreadable(traceFile: File, incomingJson: String) {
    val existingParses = traceFile.exists() && parseEvents(traceFile.readText()) != null
    val target = if (existingParses) File(traceFile.parentFile, "${traceFile.name}.raw") else traceFile
    publish(target, incomingJson)
  }

  /**
   * Writes [contents] to [target] through a temp file in the same directory.
   *
   * A reader — the profiler, a `trailblaze otel` export — can open this file at any moment, and a
   * direct write leaves a window where it holds a truncated array. Renaming an already-complete file
   * has no such window. Same directory so the rename stays within one filesystem, which is what
   * lets it be atomic.
   */
  private fun publish(target: File, contents: String) {
    val dir = target.parentFile
    dir?.mkdirs()
    val temp = File.createTempFile(target.name, ".tmp", dir)
    try {
      temp.writeText(contents)
      try {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: AtomicMoveNotSupportedException) {
        // Some filesystems (and some CI volume mounts) refuse an atomic rename. A replacing move is
        // still a single rename call and still better than truncating the destination first.
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      temp.delete()
    }
  }

  /**
   * Null when [json] is not a JSON array.
   *
   * A non-object element is skipped, not fatal: one bad entry in a batch is not a reason to lose the
   * rest of the batch, or — since an unreadable payload cannot be merged — the file it was going to
   * be merged into.
   */
  private fun parseEvents(json: String): List<JsonObject>? = runCatching {
    (JSON.parseToJsonElement(json) as JsonArray).filterIsInstance<JsonObject>()
  }.getOrNull()

  /** Records that this event's `ts` is on a device clock. Leaves an event that already says so. */
  private fun JsonObject.markedDeviceClock(): JsonObject =
    if (containsKey(CLOCK_FIELD)) this else JsonObject(this + (CLOCK_FIELD to JsonPrimitive(DEVICE_CLOCK)))

  /**
   * Sorts unrecognized events last rather than first: an event with no `ts` cannot be placed, and
   * putting it at the front would make it look like the run started there.
   */
  private fun JsonObject.startMicros(): Double =
    (this["ts"] as? JsonPrimitive)?.doubleOrNull ?: Double.MAX_VALUE

  /**
   * One lock for every trace file this process writes, rather than one per path.
   *
   * A merge is a few hundred kilobytes of JSON a handful of times per session, so serializing
   * across sessions costs nothing measurable — and it removes both the per-path lock table and the
   * question of when its entries are ever released.
   */
  private val WRITE_LOCK = Any()

  /**
   * The field that says which clock an event's `ts` came from, read by the profiler's extractor
   * (`perf-extract.ts`). Absent means the host clock, which is the overwhelming majority.
   */
  internal const val CLOCK_FIELD: String = "clock"
  internal const val DEVICE_CLOCK: String = "device"

  /**
   * Deliberately not `TrailblazeJsonInstance`: reading that seals the polymorphic tool registry, and
   * writing a trace file must not decide when that happens. Trace events are plain JSON objects
   * anyway — nothing here needs a serializer.
   */
  private val JSON = Json
}
