package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why a tool call's [arguments] failed to decode, safe to log and return to the caller. The
 * undecoded call has no tool to say which values are secret, so every string it holds is masked,
 * as written and as JSON escapes it; the `JSON input:` excerpt the decoder appends is dropped,
 * since it can cut a value mid-way where no mask matches.
 */
fun toolDecodeFailureReason(e: Throwable, arguments: JsonElement?): String {
  val strings = buildList {
    fun collect(value: JsonElement) {
      when (value) {
        is JsonObject -> value.values.forEach(::collect)
        is JsonArray -> value.forEach(::collect)
        is JsonPrimitive -> if (value.isString) {
          add(value.content)
          add(value.toString().removeSurrounding("\""))
        }
      }
    }
    arguments?.let(::collect)
  }
  val message = e.message.orEmpty().substringBefore("\nJSON input:").trim()
  return redactSensitiveValuesIn("${e::class.simpleName}: $message", strings)
}

/** [toolDecodeFailureReason] for arguments still in their JSON text form. */
fun toolDecodeFailureReason(e: Throwable, argumentsJson: String): String =
  toolDecodeFailureReason(e, runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull())
