package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool

/**
 * JSON-only contextual serializer for the abstract [TrailblazeTool] type. Registered as
 * `contextual(TrailblazeTool::class, ...)` on `TrailblazeJson`'s serializers module so any
 * `@Contextual TrailblazeTool` field across the codebase encodes/decodes uniformly through
 * [OtherTrailblazeTool]'s `{toolName, raw}` shape — without going through a polymorphic
 * dispatcher.
 *
 * Decode always produces an [OtherTrailblazeTool]. Receivers that need a concrete executable
 * type (e.g. `ComposeRpcServer.ExecuteToolsHandler`) resolve the wrapped tool back through
 * `TrailblazeToolRepo.toolCallToTrailblazeTool` before dispatching.
 *
 * Encode delegates to [toOtherTrailblazeToolPayload] so the canonical encoding lives in one
 * place — shared with the log-emit path's `TrailblazeTool.toLogPayload()`.
 */
object TrailblazeToolJsonSerializer : KSerializer<TrailblazeTool> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("TrailblazeTool")

  override fun serialize(encoder: Encoder, value: TrailblazeTool) {
    val jsonEncoder = encoder as? JsonEncoder
      ?: throw SerializationException("Only JsonEncoder is supported")
    val payload = value.toOtherTrailblazeToolPayload()
    jsonEncoder.encodeJsonElement(
      // Use the same `put`-with-raw-string idiom as `OtherTrailblazeToolFlatSerializer` so
      // both serializers produce stylistically identical wire output.
      buildJsonObject {
        put(OTHER_TRAILBLAZE_TOOL_NAME_FIELD, payload.toolName)
        put(OTHER_TRAILBLAZE_TOOL_RAW_FIELD, payload.raw)
      },
    )
  }

  override fun deserialize(decoder: Decoder): TrailblazeTool {
    val jsonDecoder = decoder as? JsonDecoder
      ?: throw SerializationException("Only JsonDecoder is supported")
    val obj = jsonDecoder.decodeJsonElement().jsonObject
    // Type-check the field's element rather than `?.jsonPrimitive`-chaining: the latter throws
    // `IllegalArgumentException` from kotlinx-serialization when the element isn't a
    // primitive (e.g. an array or object at `toolName`), which would surface to callers as a
    // hard error instead of the carefully-shaped SerializationException below.
    val nameElement = obj[OTHER_TRAILBLAZE_TOOL_NAME_FIELD]
      ?: throw SerializationException(
        "TrailblazeTool payload missing required '$OTHER_TRAILBLAZE_TOOL_NAME_FIELD'",
      )
    val toolName = (nameElement as? JsonPrimitive)?.contentOrNull
      ?: throw SerializationException(
        "TrailblazeTool payload '$OTHER_TRAILBLAZE_TOOL_NAME_FIELD' must be a JSON string, " +
          "got ${nameElement::class.simpleName}",
      )
    if (toolName.isBlank()) {
      // A blank toolName cannot be routed by `TrailblazeToolRepo.toolCallToTrailblazeTool`
      // and would silently break replay / dispatch downstream — fail loud at the decode
      // boundary so the malformed payload surfaces immediately.
      throw SerializationException(
        "TrailblazeTool payload '$OTHER_TRAILBLAZE_TOOL_NAME_FIELD' must not be blank",
      )
    }
    val rawElement = obj[OTHER_TRAILBLAZE_TOOL_RAW_FIELD]
    val raw = when {
      rawElement == null -> JsonObject(emptyMap())
      rawElement is JsonObject -> rawElement
      else -> throw SerializationException(
        "TrailblazeTool payload '$OTHER_TRAILBLAZE_TOOL_RAW_FIELD' must be a JSON object, " +
          "got ${rawElement::class.simpleName}",
      )
    }
    return OtherTrailblazeTool(toolName, raw)
  }
}
