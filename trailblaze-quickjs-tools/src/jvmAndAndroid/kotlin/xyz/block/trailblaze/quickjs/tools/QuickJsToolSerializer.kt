package xyz.block.trailblaze.quickjs.tools

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
 * KSerializer that decodes the LLM's tool-call `arguments` JSON into a
 * [QuickJsTrailblazeTool] already wired to its host. One serializer per registered tool.
 *
 * Mirror of the legacy module's `BundleToolSerializer`, but the constructed tool dispatches
 * via a [QuickJsToolHost] rather than an MCP `Client`.
 */
class QuickJsToolSerializer(
  private val advertisedName: ToolName,
  private val host: QuickJsToolHost,
) : KSerializer<QuickJsTrailblazeTool> {

  override val descriptor: SerialDescriptor =
    buildClassSerialDescriptor("quickjs:${advertisedName.toolName}")

  override fun deserialize(decoder: Decoder): QuickJsTrailblazeTool {
    val jsonDecoder = decoder as? JsonDecoder
      ?: error("QuickJsToolSerializer requires JSON decoding (got ${decoder::class.simpleName}).")
    val argsElement = jsonDecoder.decodeJsonElement()
    val args = argsElement as? JsonObject ?: JsonObject(emptyMap())
    return QuickJsTrailblazeTool(host = host, advertisedName = advertisedName, args = args)
  }

  override fun serialize(encoder: Encoder, value: QuickJsTrailblazeTool) {
    val jsonEncoder = encoder as? JsonEncoder
      ?: error("QuickJsToolSerializer requires JSON encoding (got ${encoder::class.simpleName}).")
    jsonEncoder.encodeJsonElement(value.args)
  }
}
