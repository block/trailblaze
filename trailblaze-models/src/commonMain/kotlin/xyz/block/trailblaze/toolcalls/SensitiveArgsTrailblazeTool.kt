package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool

/**
 * Marker for [TrailblazeTool]s whose args carry secret material — credentials, session tokens,
 * fetched auth payloads — that must never land in persisted session logs (which ship as CI
 * artifacts).
 *
 * The log-encode boundary (`TrailblazeTool.toLogPayload()` in `trailblaze-common`, plus the
 * `TrailblazeToolLog` construction sites that encode an authored raw-args wrapper) replaces each
 * named arg's value with [REDACTED_TOOL_ARG_PLACEHOLDER] via [withSensitiveArgsRedacted].
 * Execution and wire encoding are deliberately untouched — the dispatch target still receives
 * the real values.
 *
 * Trade-off this marker accepts: a recording generated from a session log of such a tool carries
 * the placeholder, not the real value. That is intended — secret material must be re-supplied at
 * authoring time (e.g. via `{{memory}}` tokens), never round-tripped through logs.
 */
interface SensitiveArgsTrailblazeTool {
  /**
   * Serialized property names (top-level keys of the tool's arg object) whose values are masked
   * in persisted log payloads. Implement as a `get()`-only val (not a constructor param) so it
   * stays out of the tool's serialized shape and generated schemas.
   */
  val sensitiveArgNames: Set<String>
}

/** Value written in place of a sensitive arg's value in persisted log payloads. */
const val REDACTED_TOOL_ARG_PLACEHOLDER: String = "<redacted>"

/**
 * Returns a copy of this payload with every present top-level key in [sensitiveArgNames] replaced
 * by [REDACTED_TOOL_ARG_PLACEHOLDER]. Absent keys are ignored; when nothing matches, returns
 * `this` unchanged. Only top-level keys are inspected — that is the shape every tool's arg object
 * encodes to.
 */
fun OtherTrailblazeTool.withSensitiveArgsRedacted(sensitiveArgNames: Set<String>): OtherTrailblazeTool {
  if (sensitiveArgNames.none { raw.containsKey(it) }) return this
  return copy(
    raw = JsonObject(
      raw.mapValues { (key, value) ->
        if (key in sensitiveArgNames) JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER) else value
      },
    ),
  )
}
