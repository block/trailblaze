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
 *
 * [binding] should be supplied whenever this serializer is used on the koog dispatch path
 * (i.e. from [xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration.buildKoogTool]).
 * The binding is forwarded to [QuickJsTrailblazeTool] so [QuickJsTrailblazeTool.execute]
 * can set [SessionScopedHostBinding.activeContext] before the QuickJS evaluation starts —
 * see [QuickJsTrailblazeTool.binding] for the full rationale.
 */
class QuickJsToolSerializer(
  private val advertisedName: ToolName,
  private val host: QuickJsToolHost,
  private val binding: SessionScopedHostBinding? = null,
  /**
   * Forwarded onto the decoded [QuickJsTrailblazeTool] so a `isRecordable = false` scripted tool
   * surfaces the per-instance metadata override that keeps it out of the recording. Defaults
   * `true`; threading it here (rather than wrapping the decoded tool) keeps the instance a
   * [QuickJsTrailblazeTool] for `SessionScopedHostBinding`'s same-host re-entry guard.
   */
  private val isRecordable: Boolean = true,
) : KSerializer<QuickJsTrailblazeTool> {

  constructor(advertisedName: ToolName, host: QuickJsToolHost) : this(advertisedName, host, null)

  override val descriptor: SerialDescriptor =
    buildClassSerialDescriptor("quickjs:${advertisedName.toolName}")

  override fun deserialize(decoder: Decoder): QuickJsTrailblazeTool {
    val jsonDecoder = decoder as? JsonDecoder
      ?: error("QuickJsToolSerializer requires JSON decoding (got ${decoder::class.simpleName}).")
    val argsElement = jsonDecoder.decodeJsonElement()
    val args = argsElement as? JsonObject ?: JsonObject(emptyMap())
    return QuickJsTrailblazeTool(
      host = host,
      advertisedName = advertisedName,
      args = args,
      binding = binding,
      isRecordable = isRecordable,
    )
  }

  override fun serialize(encoder: Encoder, value: QuickJsTrailblazeTool) {
    val jsonEncoder = encoder as? JsonEncoder
      ?: error("QuickJsToolSerializer requires JSON encoding (got ${encoder::class.simpleName}).")
    jsonEncoder.encodeJsonElement(value.args)
  }
}
