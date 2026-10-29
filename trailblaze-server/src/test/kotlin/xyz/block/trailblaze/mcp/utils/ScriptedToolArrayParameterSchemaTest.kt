package xyz.block.trailblaze.mcp.utils

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.ollama.tools.json.OllamaToolDescriptorSchemaGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toMcpJsonSchemaObject
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire schema a model receives for a scripted tool's array parameters.
 *
 * Renders one scripted tool through the schema writers the LLM paths use (MCP, OpenAI-compatible,
 * Ollama) and asserts the array / array-of-objects shape is in the JSON, so a model is told to send
 * a real list instead of text. Anthropic and Gemini build their schema inside their client and
 * can't be rendered here; they lower the same Koog list / object types.
 */
class ScriptedToolArrayParameterSchemaTest {

  private val toolSchema = Json.parseToJsonElement(
    """
    {"type":"object","required":["itemPrices"],"properties":{
      "requiredItems":{"type":"array","items":{"type":"string"},"description":"Items the order must contain."},
      "itemPrices":{"type":"array","items":{"type":"object","required":["item","priceCents"],
        "properties":{"item":{"type":"string"},"priceCents":{"type":"number"}}}},
      "grid":{"type":"array","items":{"type":"array","items":{"type":"integer"}}}}}
    """,
  ) as JsonObject

  private val koog: ToolDescriptor = TrailblazeToolDescriptor(
    name = "seedOrder",
    description = "Seed an order",
    requiredParameters = listOf(TrailblazeToolParameterDescriptor(name = "itemPrices", type = "array")),
    optionalParameters = listOf(
      TrailblazeToolParameterDescriptor(name = "requiredItems", type = "array", description = "Items the order must contain."),
      TrailblazeToolParameterDescriptor(name = "grid", type = "array"),
    ),
  ).apply { inputSchema = toolSchema }.toKoogToolDescriptor(strict = false)

  private fun JsonObject.obj(key: String) = getValue(key).jsonObject

  private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

  /** Asserts the three array parameters in a `properties` object carry their declared element shapes. */
  private fun assertArrayShapes(properties: JsonObject) {
    val requiredItems = properties.obj("requiredItems")
    assertEquals("array", requiredItems.text("type"))
    assertEquals("string", requiredItems.obj("items").text("type"))

    val itemPrices = properties.obj("itemPrices")
    assertEquals("array", itemPrices.text("type"))
    val priceItem = itemPrices.obj("items")
    assertEquals("object", priceItem.text("type"))
    assertEquals("string", priceItem.obj("properties").obj("item").text("type"))
    assertEquals("number", priceItem.obj("properties").obj("priceCents").text("type"))

    val grid = properties.obj("grid")
    assertEquals("array", grid.text("type"))
    assertEquals("array", grid.obj("items").text("type"))
    assertEquals("integer", grid.obj("items").obj("items").text("type"))
  }

  @Test
  fun `MCP schema offers arrays of the declared element type and marks nested fields required`() {
    val properties = JsonObject(
      (koog.requiredParameters + koog.optionalParameters).associate { it.name to it.toMcpJsonSchemaObject() },
    )

    assertArrayShapes(properties)
    val priceItem = properties.obj("itemPrices").obj("items")
    assertEquals(listOf("item", "priceCents"), priceItem.getValue("required").jsonArray.map { it.jsonPrimitive.content })
  }

  @Test
  fun `OpenAI-compatible schema offers arrays of the declared element type`() {
    val schema = OpenAICompatibleToolDescriptorSchemaGenerator().generate(koog)

    assertArrayShapes(schema.obj("properties"))
  }

  @Test
  fun `Ollama schema offers arrays of the declared element type`() {
    val schema = OllamaToolDescriptorSchemaGenerator().generate(koog)

    assertArrayShapes(schema.obj("properties"))
  }

  @Test
  fun `the array parameter keeps its description`() {
    val properties = JsonObject(
      (koog.requiredParameters + koog.optionalParameters).associate { it.name to it.toMcpJsonSchemaObject() },
    )

    assertEquals(JsonPrimitive("Items the order must contain."), properties.obj("requiredItems")["description"])
  }
}
