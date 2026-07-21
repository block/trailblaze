package xyz.block.trailblaze.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The generic, pluggable session-events artifact format.
 *
 * Any producer (a network capturer, an analytics tap, an on-device event bridge, …) can drop one or
 * more NDJSON files into `<sessionDir>/[SessionEvents.DIR_NAME]/` and have them show up on the run
 * timeline without the reader, the report, or the UI knowing anything producer-specific. There is no
 * registry to update and no folder named after one integration — the contract is entirely the file
 * layout below.
 *
 * ## File layout
 *
 * ```
 * <sessionDir>/events/<name>.ndjson
 * ```
 *
 * - **`name`** — the stream's identity (e.g. `network`, `analytics`, a sanitized plugin id). Used as
 *   the timeline group/filter label and as the key formatters/renderers dispatch on. Producer-chosen,
 *   sanitized to a filename-safe token; may contain dots (a dotted plugin id survives round-trip).
 * - Each line is one NDJSON record. Producers using the default envelope write an [SessionEvent]
 *   (`{ "timeMs", "data" }`); producers reusing an existing rich schema (e.g. `NetworkEvent`) may
 *   write that schema directly — see "Ordering" below.
 *
 * An earlier revision of this format carried a `<name>.<style>.ndjson` rendering-hint segment. No
 * renderer ever dispatched on it (rendering is derived from the stream name and the line content),
 * and in practice every producer wrote the generic `json` style — so the segment is gone.
 * [parseFileName] still strips a legacy trailing `.json` segment so existing session dirs keep
 * loading under their original stream names.
 *
 * ## Ordering
 *
 * The reader interleaves every stream on a single epoch-millis clock. To stay schema-agnostic it
 * pulls the order key from the first present of [TIME_FIELD] (`timeMs`, the envelope field) then
 * [LEGACY_TIME_FIELD] (`timestampMs`, what `NetworkEvent` already carries), falling back to `0`. So
 * an envelope stream and a bare-`NetworkEvent` stream both order correctly with no per-shape branch.
 */
object SessionEvents {

  /** Subdirectory of the session dir that holds the per-stream NDJSON files. */
  const val DIR_NAME: String = "events"

  /** File extension for every events stream. NDJSON: one JSON record per line. */
  const val EXTENSION: String = "ndjson"

  /** Envelope ordering field, preferred when present. */
  const val TIME_FIELD: String = "timeMs"

  /** Fallback ordering field for rich schemas that predate the envelope (e.g. `NetworkEvent`). */
  const val LEGACY_TIME_FIELD: String = "timestampMs"

  /** The style token every legacy `<name>.<style>.ndjson` producer actually wrote. */
  private const val LEGACY_STYLE_SUFFIX = ".json"

  // Names keep `.` (so a dotted plugin id like `com.x.plugin.network` survives round-trip); only
  // truly path-unsafe characters are replaced.
  private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

  /** Map a stream name to a filename-safe segment, preserving dots. */
  fun sanitizeName(name: String): String =
    name.replace(UNSAFE_NAME_CHARS, "_").ifEmpty { "unknown" }

  /** Build the file name (not path) for a stream: `<name>.ndjson`. */
  fun fileName(name: String): String = "${sanitizeName(name)}.$EXTENSION"

  /**
   * Parse a stream file name back into its stream name, or `null` if it isn't a well-formed events
   * file. Everything before the `.ndjson` extension is the name. A legacy trailing `.json` style
   * segment (the only style production ever wrote) is stripped so old session dirs keep resolving
   * to the same stream names. Locked against the TS `parseStreamFileName` by
   * `session-events-parity-fixtures.json` (in :trailblaze-report).
   */
  fun parseFileName(fileName: String): String? {
    val withoutExt = fileName.removeSuffix(".$EXTENSION")
    if (withoutExt == fileName) return null // no .ndjson suffix
    val name = withoutExt.removeSuffix(LEGACY_STYLE_SUFFIX)
    return name.ifEmpty { null }
  }
}

/**
 * The default per-line envelope: a host-stamped epoch-millis timestamp plus an arbitrary payload.
 *
 * Producers that don't already have a rich on-disk schema should write one [SessionEvent] per line.
 * [data] is whatever the renderer understands (a key/value map, a log string, a nested object, …).
 * Keeping the wrapper uniform lets the reader order every envelope stream the same way, while the
 * renderer decides how [data] is displayed.
 */
@Serializable
data class SessionEvent(val timeMs: Long, val data: JsonElement)
