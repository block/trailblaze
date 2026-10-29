package xyz.block.trailblaze.events

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import xyz.block.trailblaze.util.Console

/**
 * Whether a capture writing into a session's streams has recorded losing data, so a reader can tell
 * "nothing matched" apart from "something may have been dropped". A capture that drops records (a
 * full queue, a write that failed) calls [markIncomplete]: the loss is held in memory for readers in
 * this process and written to a marker file for readers in another one.
 *
 * A loss belongs to the stream that lost it, so a gap in one capture doesn't make every other
 * stream's result incomplete. A null stream is a loss that can't be pinned to one stream; it counts
 * against every stream of the session.
 */
object SessionCaptureHealth {

  /** One line naming what was lost, for a loss not tied to one stream, written next to the session's streams. */
  const val MARKER_FILE: String = "capture-incomplete.txt"

  private const val MARKER_PREFIX = "capture-incomplete."
  private const val MAX_RETAINED_LOSSES = 4_096
  private val failures = ConcurrentHashMap<String, String>()

  /** The marker file for [stream]'s losses, [MARKER_FILE] when [stream] is null. */
  fun markerFileName(stream: String?): String =
    if (stream == null) MARKER_FILE else "$MARKER_PREFIX${SessionEvents.sanitizeName(stream)}.txt"

  /**
   * Records that [sessionDir]'s capture of [stream] lost data. The first loss per stream wins, so a
   * capture may call this on every drop and only the first one touches the disk. Held in memory
   * before the write, so a full or read-only disk still leaves this process's readers seeing the loss.
   */
  fun markIncomplete(sessionDir: File, reason: String, stream: String? = null) {
    if (markIncompleteInMemory(sessionDir, reason, stream)) writeMarker(sessionDir, reason, stream)
  }

  /**
   * [markIncomplete] without the disk write, for a caller on a thread that must not block on the
   * storage that just lost its data. True when this is the stream's first loss: the caller then
   * persists it with [writeMarker] from a thread it owns.
   */
  fun markIncompleteInMemory(sessionDir: File, reason: String, stream: String? = null): Boolean {
    if (failures.putIfAbsent(keyOf(sessionDir, stream), reason) != null) return false
    while (failures.size > MAX_RETAINED_LOSSES) {
      failures.keys.firstOrNull()?.let(failures::remove) ?: break
    }
    return true
  }

  /** Writes [stream]'s marker file for readers in another process; a failure is logged, not thrown. */
  fun writeMarker(sessionDir: File, reason: String, stream: String? = null) {
    val name = markerFileName(stream)
    runCatching {
      sessionDir.mkdirs()
      File(sessionDir, name).writeText(reason + "\n", Charsets.UTF_8)
    }.onFailure {
      Console.log("[SessionCaptureHealth] could not write $name in $sessionDir: ${it.message}")
    }
  }

  /**
   * Forgets [sessionDir]'s loss for [stream], or with a null [stream] every loss recorded there, for
   * a capture starting over in a directory it reuses.
   */
  fun clear(sessionDir: File, stream: String? = null) {
    if (stream != null) {
      failures.remove(keyOf(sessionDir, stream))
      File(sessionDir, markerFileName(stream)).delete()
      return
    }
    val dir = keyOf(sessionDir, null)
    failures.keys.removeIf { it == dir || it.startsWith("$dir\u0000") }
    sessionDir.listFiles { file -> file.name == MARKER_FILE || isStreamMarker(file.name) }?.forEach(File::delete)
  }

  private fun isStreamMarker(name: String): Boolean =
    name.startsWith(MARKER_PREFIX) && name.endsWith(".txt") && name.length > MARKER_PREFIX.length + ".txt".length

  /**
   * Why a read of [stream] in [sessionDir] may be missing records: its own loss and any loss not
   * tied to one stream. Null when neither was recorded. With a null [stream], only the latter.
   */
  fun failure(sessionDir: File, stream: String? = null): String? =
    listOfNotNull(stream?.let { lossOf(sessionDir, it) }, lossOf(sessionDir, null))
      .distinct()
      .joinToString("; ")
      .ifEmpty { null }

  private fun lossOf(sessionDir: File, stream: String?): String? {
    failures[keyOf(sessionDir, stream)]?.let { return it }
    val marker = File(sessionDir, markerFileName(stream))
    if (!marker.isFile) return null
    return runCatching { marker.readText().trim() }.getOrNull()?.ifEmpty { null } ?: "a capture recorded data loss"
  }

  private fun keyOf(sessionDir: File, stream: String?): String {
    val dir = sessionDir.absoluteFile.normalize().path
    return if (stream == null) dir else "$dir\u0000${SessionEvents.sanitizeName(stream)}"
  }
}
