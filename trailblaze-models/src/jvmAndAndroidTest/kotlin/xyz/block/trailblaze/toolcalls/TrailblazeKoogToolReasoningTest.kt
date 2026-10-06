package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * The system prompt asks the LLM to fill `reasoning` on every tool call. A tool that doesn't
 * declare it must still run, not bounce the call back as a parse error.
 */
class TrailblazeKoogToolReasoningTest {

  @Serializable
  private data object NoArgsTool : TrailblazeTool

  @Serializable
  private data class TextTool(val text: String) : TrailblazeTool

  @Serializable
  private data class ReasonedTool(val text: String, val reasoning: String? = null) : TrailblazeTool

  /** Shaped like a scripted tool: no fields of its own, the args object passed through whole. */
  private data class RawArgsTool(val args: JsonObject) : TrailblazeTool

  private object RawArgsSerializer : KSerializer<RawArgsTool> {
    override val descriptor = buildClassSerialDescriptor("scripted:t")
    override fun deserialize(decoder: Decoder) = RawArgsTool((decoder as JsonDecoder).decodeJsonElement().jsonObject)
    override fun serialize(encoder: Encoder, value: RawArgsTool) = (encoder as JsonEncoder).encodeJsonElement(value.args)
  }

  // Koog's default: strict, so any undeclared key fails the decode.
  private val strict = KotlinxSerializer(Json)

  private fun <T : TrailblazeTool> tool(serializer: KSerializer<T>) =
    TrailblazeKoogTool(serializer, ToolDescriptor(name = "t", description = "d")) { "" }

  @Test fun `a no-argument tool runs when the LLM adds reasoning`() {
    val args = buildJsonObject { put("reasoning", "Listing apps first.") }.toKoogJSONObject()

    assertThat(tool(NoArgsTool.serializer()).decodeArgs(args, strict)).isEqualTo(NoArgsTool)
  }

  @Test fun `a tool with arguments keeps them and loses only the undeclared reasoning`() {
    val args = buildJsonObject {
      put("text", "Juniper")
      put("reasoning", "Typing the first name.")
    }.toKoogJSONObject()

    assertThat(tool(TextTool.serializer()).decodeArgs(args, strict)).isEqualTo(TextTool("Juniper"))
  }

  @Test fun `a tool that declares reasoning still receives it`() {
    val args = buildJsonObject {
      put("text", "Juniper")
      put("reasoning", "Typing the first name.")
    }.toKoogJSONObject()

    assertThat(tool(ReasonedTool.serializer()).decodeArgs(args, strict))
      .isEqualTo(ReasonedTool("Juniper", "Typing the first name."))
  }

  @Test fun `any other undeclared key is still rejected`() {
    val args = buildJsonObject {
      put("text", "Juniper")
      put("txet", "typo")
    }.toKoogJSONObject()

    assertFailure { tool(TextTool.serializer()).decodeArgs(args, strict) }.messageContains("txet")
  }

  @Test fun `a scripted tool whose schema declares reasoning still receives it`() {
    val descriptor = ToolDescriptor(
      name = "t",
      description = "d",
      optionalParameters = listOf(ToolParameterDescriptor("reasoning", "", ToolParameterType.String)),
    )
    val scripted = TrailblazeKoogTool(RawArgsSerializer, descriptor) { "" }
    val args = buildJsonObject { put("reasoning", "Typing the first name.") }

    assertThat(scripted.decodeArgs(args.toKoogJSONObject(), strict)).isEqualTo(RawArgsTool(args))
  }
}
