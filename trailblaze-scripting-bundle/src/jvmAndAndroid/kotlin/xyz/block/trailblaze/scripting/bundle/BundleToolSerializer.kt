package xyz.block.trailblaze.scripting.bundle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * Decodes the LLM's tool-call `arguments` JSON into a [BundleTrailblazeTool] instance
 * already wired to the right session. One serializer per registered tool.
 *
 * Koog's tool-dispatch layer hands us a raw JSON blob for the args; this serializer
 * captures the tool name + session provider so the resulting instance knows which bundle
 * to dispatch through.
 */
class BundleToolSerializer(
  private val advertisedName: ToolName,
  private val sessionProvider: () -> McpBundleSession,
  private val callbackContext: BundleToolRegistration.JsScriptingCallbackContext? = null,
) : KSerializer<BundleTrailblazeTool> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("bundle:${advertisedName.toolName}")

  override fun deserialize(decoder: Decoder): BundleTrailblazeTool {
    val jsonDecoder = decoder as? JsonDecoder
      ?: error("BundleToolSerializer requires JSON decoding (got ${decoder::class.simpleName}).")
    val argsElement = jsonDecoder.decodeJsonElement()
    val args = argsElement as? JsonObject ?: JsonObject(emptyMap())
    return BundleTrailblazeTool(
      sessionProvider = sessionProvider,
      advertisedName = advertisedName,
      args = args,
      callbackContext = callbackContext,
    )
  }

  override fun serialize(encoder: Encoder, value: BundleTrailblazeTool) {
    val jsonEncoder = encoder as? JsonEncoder
      ?: error("BundleToolSerializer requires JSON encoding (got ${encoder::class.simpleName}).")
    jsonEncoder.encodeJsonElement(value.args)
  }
}
