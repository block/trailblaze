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
 * <sessionDir>/events/<name>.<style>.ndjson
 * ```
 *
 * - **`name`** — the stream's identity (e.g. `network`, `analytics`, a sanitized plugin id). Used as
 *   the timeline group/filter label. Producer-chosen, sanitized to a filename-safe token.
 * - **`style`** — *how to format/render* the stream's lines (see [EventStyle]). Declared **per file,
 *   in the filename**, so it survives a crash (the name exists the instant the file is created — no
 *   sidecar manifest that can go missing) and so one file is exactly one style.
 * - Each line is one NDJSON record. Producers using the default envelope write an [SessionEvent]
 *   (`{ "timeMs", "data" }`); producers reusing an existing rich schema (e.g. `NetworkEvent`) may
 *   write that schema directly — see "Ordering" below.
 *
 * ## Ordering
 *
 * The reader interleaves every stream on a single epoch-millis clock. To stay schema-agnostic it
 * pulls the order key from the first present of [TIME_FIELD] (`timeMs`, the envelope field) then
 * [LEGACY_TIME_FIELD] (`timestampMs`, what `NetworkEvent` already carries), falling back to `0`. So
 * an envelope stream and a bare-`NetworkEvent` stream both order correctly with no per-style branch.
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

  // Names keep `.` (so a dotted plugin id like `com.x.plugin.network` survives round-trip); only
  // truly path-unsafe characters are replaced. Styles additionally strip `.` so the style segment
  // can never contain a dot — that's what keeps [parseFileName] unambiguous (see below).
  private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")
  private val UNSAFE_STYLE_CHARS = Regex("[^A-Za-z0-9_-]")

  /** Map a stream name to a filename-safe segment, preserving dots. */
  fun sanitizeName(name: String): String =
    name.replace(UNSAFE_NAME_CHARS, "_").ifEmpty { "unknown" }

  /** Map a style to a filename-safe, dot-free segment. */
  fun sanitizeStyle(style: String): String =
    style.replace(UNSAFE_STYLE_CHARS, "_").ifEmpty { "unknown" }

  /** Build the file name (not path) for a stream: `<name>.<style>.ndjson`. */
  fun fileName(name: String, style: String): String =
    "${sanitizeName(name)}.${sanitizeStyle(style)}.$EXTENSION"

  /**
   * Parse a stream file name back into its [EventStreamId], or `null` if it isn't a well-formed
   * events file. The trailing `.ndjson` is the extension; the dot-segment before it is the style
   * (guaranteed dot-free by [sanitizeStyle]); everything before that is the name (which may itself
   * contain dots). So splitting on the last `.` before the extension is unambiguous.
   */
  fun parseFileName(fileName: String): EventStreamId? {
    val withoutExt = fileName.removeSuffix(".$EXTENSION")
    if (withoutExt == fileName) return null // no .ndjson suffix
    val lastDot = withoutExt.lastIndexOf('.')
    if (lastDot <= 0 || lastDot == withoutExt.length - 1) return null
    val name = withoutExt.substring(0, lastDot)
    val style = withoutExt.substring(lastDot + 1)
    if (name.isEmpty() || style.isEmpty()) return null
    return EventStreamId(name = name, style = style)
  }
}

/** The identity of one events stream, derived from its file name. */
data class EventStreamId(val name: String, val style: String)

/**
 * The default per-line envelope: a host-stamped epoch-millis timestamp plus an arbitrary payload.
 *
 * Producers that don't already have a rich on-disk schema should write one [SessionEvent] per line.
 * [data] is whatever the [EventStyle] renderer understands (a key/value map, a log string, a nested
 * object, …). Keeping the wrapper uniform lets the reader order every envelope stream without
 * knowing the style, while the style still drives how [data] is displayed.
 */
@Serializable
data class SessionEvent(val timeMs: Long, val data: JsonElement)

/**
 * Well-known rendering styles. `style` is an open string (the filename can carry any token, and an
 * unknown style falls back to a raw-JSON renderer), but these are the ones the bundled UI renders
 * specially. New integrations should reuse one of these before inventing a new token.
 */
object EventStyle {
  /** A network exchange. Lines are `NetworkEvent` (rich schema, ordered via `timestampMs`). */
  const val NETWORK: String = "network"

  /** A product-analytics event: a name plus flat properties. */
  const val ANALYTICS: String = "analytics"

  /** A flat key/value bag — rendered as a small table. */
  const val KEY_VALUE: String = "keyValue"

  /** A single human-readable log line per record. */
  const val LOG: String = "log"

  /** Arbitrary JSON, pretty-printed and collapsible. The fallback for unknown styles. */
  const val JSON: String = "json"
}
