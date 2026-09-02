package xyz.block.trailblaze.events

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A reference to a media/binary file that a session event embeds — the framework's generic
 * "attachment" primitive. A producer writing any session-event payload (see [SessionEvents]) can
 * nest one of these wherever it makes sense in its own schema (`"audio": { … }`,
 * `"attachments": [ … ]`, any depth, any field name); viewers detect the ref structurally and
 * render `label · mimeType · size · Open` without knowing anything producer-specific.
 *
 * ## Detection
 *
 * Renderers walking an arbitrary event payload recognize an attachment ref by the
 * [MARKER_FIELD] discriminator (`"$attachment": true`) that [marker] serializes — exact
 * dispatch, deliberately not duck-typed on `path`+`mimeType`, so unrelated payloads that happen
 * to carry those field names are never misrendered. Use [fromJsonObjectOrNull] for one object
 * and [findAll] to sweep a whole payload; the TypeScript viewers mirror the same rule, locked by
 * the session-events parity fixtures in `:trailblaze-report`.
 *
 * ## Bytes
 *
 * [path] is relative to the session directory — never absolute — so the entire session bundle
 * stays portable across the live daemon UI, the exported zip, and the single-file report (the
 * same rule as `NetworkEvent.BodyRef.blobPath`). By convention producers put attachment bytes
 * under the [DIR_NAME] subdirectory (`<sessionDir>/attachments/<file>`), mirroring
 * `events/`; the path is authoritative, the directory is only the convention.
 *
 * [sizeBytes] is the file's real size, present so a viewer can show magnitude — and decide
 * whether to embed — without touching the bytes.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttachmentRef(
  /** Session-relative path to the attachment bytes, e.g. `attachments/3f9c….wav`. */
  val path: String,
  /** MIME type, e.g. `audio/wav` — the viewer's entire rendering dispatch key. */
  val mimeType: String,
  /** Size of the file at [path] in bytes. */
  val sizeBytes: Long,
  /** Optional human label for the attachment row; viewers fall back to the file name. */
  val label: String? = null,
  /**
   * The wire discriminator (see class kdoc). Always encoded; carries no information beyond
   * "this object is an [AttachmentRef]" — leave it defaulted.
   */
  @SerialName(MARKER_FIELD)
  @EncodeDefault(EncodeDefault.Mode.ALWAYS)
  val marker: Boolean = true,
) {
  init {
    // The discriminator is a constructor property so it can be `@EncodeDefault`-serialized, which
    // also exposes it to `copy()` and to decoding. A `false` here would encode
    // `"$attachment": false` — an object every detector on both sides rejects — so the ref would
    // travel the wire and render nowhere. Refuse it at construction instead.
    require(marker) { "$MARKER_FIELD must be true; an AttachmentRef with a false marker is undetectable" }
  }

  companion object {
    /** In-object discriminator field name marking a JSON object as an [AttachmentRef]. */
    const val MARKER_FIELD: String = "\$attachment"

    /** Conventional session-dir subdirectory for attachment bytes, beside [SessionEvents.DIR_NAME]. */
    const val DIR_NAME: String = "attachments"

    /**
     * Exact-dispatch detection for one JSON object: an [AttachmentRef] iff [MARKER_FIELD] is
     * literally `true` (unquoted), `path` and `mimeType` are JSON strings, and `sizeBytes` is a
     * finite, non-negative JSON number (truncated to a whole byte count — see [sizeBytesOrNull]).
     * Anything else — marker absent, marker non-boolean, missing/mistyped required fields — is
     * `null`, never an error, so a renderer can probe arbitrary payload objects safely. Extra
     * fields are tolerated; a non-string `label` reads as absent. Field checks are spelled out
     * (not delegated to a deserializer) because the TypeScript mirror must apply the identical
     * rules.
     */
    fun fromJsonObjectOrNull(obj: JsonObject): AttachmentRef? {
      val marker = obj[MARKER_FIELD] as? JsonPrimitive ?: return null
      if (marker.isString || marker.content != "true") return null
      fun string(field: String): String? = (obj[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val path = string("path") ?: return null
      val mimeType = string("mimeType") ?: return null
      val sizeBytes = (obj["sizeBytes"] as? JsonPrimitive)?.takeIf { !it.isString }
        ?.content?.let(::sizeBytesOrNull) ?: return null
      return AttachmentRef(path = path, mimeType = mimeType, sizeBytes = sizeBytes, label = string("label"))
    }

    /**
     * A `sizeBytes` literal as a whole, non-negative byte count, or `null` if it is not one.
     *
     * Integer literals are read exactly rather than through a `Double`, so a size past 2^53 is not
     * silently moved to a neighbouring value; only fractional literals go through `Double`, where
     * they are truncated. Negative, non-finite and out-of-[Long]-range values are rejected instead
     * of being clamped to a plausible-looking bound — a size that cannot be represented is bad
     * input, not a very large file.
     *
     * The TypeScript mirror applies the same accept/reject rules. It cannot match the exactness
     * above 2^53, because `JSON.parse` has already rounded the literal to a double before any
     * detector sees it; that is a property of the wire in JS, not a difference in the contract, and
     * the parity fixtures stay inside the range both languages represent exactly.
     */
    private fun sizeBytesOrNull(raw: String): Long? {
      raw.toLongOrNull()?.let { return if (it >= 0L) it else null }
      val asDouble = raw.toDoubleOrNull() ?: return null
      if (!asDouble.isFinite() || asDouble < 0.0 || asDouble >= Long.MAX_VALUE.toDouble()) return null
      return asDouble.toLong()
    }

    /**
     * Depth-first sweep of an arbitrary event payload for embedded attachment refs, in document
     * order. Does not descend into a matched ref (a ref never nests another).
     */
    fun findAll(element: JsonElement): List<AttachmentRef> {
      val found = mutableListOf<AttachmentRef>()
      fun walk(el: JsonElement) {
        when (el) {
          is JsonObject -> {
            val ref = fromJsonObjectOrNull(el)
            if (ref != null) found += ref else el.values.forEach(::walk)
          }
          is JsonArray -> el.forEach(::walk)
          else -> Unit
        }
      }
      walk(element)
      return found
    }
  }
}
