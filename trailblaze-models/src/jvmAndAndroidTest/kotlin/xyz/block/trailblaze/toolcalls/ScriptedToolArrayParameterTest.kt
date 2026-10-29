package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogParameterTypePreservingComposites
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import kotlin.test.Test

/**
 * What the model is told about a scripted tool's array / object parameters.
 *
 * A TypeScript tool declaring `requiredItems?: string[]` used to reach the LLM as a string
 * parameter, so the model sent `"Espresso, Hot Chocolate"` and the recording then failed
 * `trailblaze check` against the tool's own `string[]`. These pin that the full schema survives the
 * projection onto the LLM-facing Koog descriptor: the model is offered a real list / object, with
 * the declared element shape.
 */
class ScriptedToolArrayParameterTest {

  /** A scripted tool's descriptor the way `LazyYamlScriptedToolRegistration` builds it: flat view + full schema. */
  private fun scriptedTool(schemaJson: String): TrailblazeToolDescriptor {
    val schema = Json.parseToJsonElement(schemaJson) as JsonObject
    val required = (schema["required"] as? JsonArray)
      ?.map { (it as JsonPrimitive).content }
      .orEmpty()
    val parameters = (schema["properties"] as JsonObject).map { (name, property) ->
      TrailblazeToolParameterDescriptor(
        name = name,
        type = ((property as JsonObject)["type"] as? JsonPrimitive)?.content ?: "string",
      )
    }
    return TrailblazeToolDescriptor(
      name = "scripted_tool",
      requiredParameters = parameters.filter { it.name in required },
      optionalParameters = parameters.filter { it.name !in required },
    ).apply { inputSchema = schema }
  }

  private fun ToolDescriptor.parameter(name: String) =
    (requiredParameters + optionalParameters).single { it.name == name }

  @Test
  fun `a string array parameter is offered as a list of strings`() {
    val koog = scriptedTool(
      """{"type":"object","properties":{"requiredItems":{"type":"array","items":{"type":"string"}}}}""",
    ).toKoogToolDescriptor(strict = false)

    assertThat(koog.parameter("requiredItems").type).isEqualTo(ToolParameterType.List(ToolParameterType.String))
  }

  @Test
  fun `an array of objects keeps each field's type and which fields are required`() {
    val koog = scriptedTool(
      """
      {"type":"object","required":["itemPrices"],"properties":{"itemPrices":{"type":"array","items":{
        "type":"object","required":["item","priceCents"],"additionalProperties":false,
        "properties":{"item":{"type":"string","description":"Catalog item name"},"priceCents":{"type":"number"}}}}}}
      """,
    ).toKoogToolDescriptor(strict = false)

    val list = koog.requiredParameters.single().type as ToolParameterType.List
    val item = list.itemsType as ToolParameterType.Object
    assertThat(item.properties.map { it.name to it.type }).containsExactly(
      "item" to ToolParameterType.String,
      "priceCents" to ToolParameterType.Float,
    )
    assertThat(item.properties.first { it.name == "item" }.description).isEqualTo("Catalog item name")
    assertThat(item.requiredProperties).containsExactly("item", "priceCents")
    assertThat(item.additionalProperties).isEqualTo(false)
  }

  @Test
  fun `nested arrays and an array inside an object keep their depth`() {
    val koog = scriptedTool(
      """
      {"type":"object","properties":{
        "grid":{"type":"array","items":{"type":"array","items":{"type":"integer"}}},
        "order":{"type":"object","properties":{"lines":{"type":"array","items":{"type":"string"}}}}}}
      """,
    ).toKoogToolDescriptor(strict = false)

    assertThat(koog.parameter("grid").type)
      .isEqualTo(ToolParameterType.List(ToolParameterType.List(ToolParameterType.Integer)))
    val order = koog.parameter("order").type as ToolParameterType.Object
    assertThat(order.properties.single().type).isEqualTo(ToolParameterType.List(ToolParameterType.String))
  }

  @Test
  fun `a named element type behind a ref is expanded, and a self-referencing one stops`() {
    val koog = scriptedTool(
      """
      {"type":"object","properties":{"tree":{"type":"array","items":{"${'$'}ref":"#/definitions/Node"}}},
       "definitions":{"Node":{"type":"object","properties":{
         "name":{"type":"string"},"children":{"type":"array","items":{"${'$'}ref":"#/definitions/Node"}}}}}}
      """,
    ).toKoogToolDescriptor(strict = false)

    val node = (koog.parameter("tree").type as ToolParameterType.List).itemsType as ToolParameterType.Object
    assertThat(node.properties.map { it.name }).containsExactly("name", "children")
    // The recursive occurrence is "some object", not an unbounded expansion.
    val children = node.properties.single { it.name == "children" }.type as ToolParameterType.List
    assertThat(children.itemsType).isEqualTo(ToolParameterType.Object(emptyList(), additionalProperties = true))
  }

  @Test
  fun `a ref to an encoded definition name resolves under both definitions and defs`() {
    // ts-json-schema-generator keys the definition by its decoded name but percent-encodes the ref.
    for (bag in listOf("definitions", "${'$'}defs")) {
      val koog = scriptedTool(
        """
        {"type":"object","properties":{"prices":{"type":"array","items":{"${'$'}ref":"#/$bag/Record%3Cstring%2Cnumber%3E"}},
          "path":{"type":"array","items":{"${'$'}ref":"#/$bag/a~1b"}}},
         "$bag":{"Record<string,number>":{"type":"object","properties":{"amount":{"type":"number"}}},
                 "a/b":{"type":"object","properties":{"step":{"type":"string"}}}}}
        """,
      ).toKoogToolDescriptor(strict = false)

      val price = (koog.parameter("prices").type as ToolParameterType.List).itemsType as ToolParameterType.Object
      assertThat(price.properties.map { it.name }).containsExactly("amount")
      val step = (koog.parameter("path").type as ToolParameterType.List).itemsType as ToolParameterType.Object
      assertThat(step.properties.map { it.name }).containsExactly("step")
    }
  }

  @Test
  fun `an optional array typed as nullable is still a list`() {
    val koog = scriptedTool(
      """
      {"type":"object","properties":{
        "viaAnyOf":{"anyOf":[{"type":"null"},{"type":"array","items":{"type":"string"}}]},
        "viaTypeUnion":{"type":["array","null"],"items":{"type":"string"}}}}
      """,
    ).toKoogToolDescriptor(strict = false)

    assertThat(koog.parameter("viaAnyOf").type).isEqualTo(ToolParameterType.List(ToolParameterType.String))
    assertThat(koog.parameter("viaTypeUnion").type).isEqualTo(ToolParameterType.List(ToolParameterType.String))
  }

  @Test
  fun `an array of string literals offers only those values`() {
    val koog = scriptedTool(
      """{"type":"object","properties":{"flags":{"type":"array","items":{"type":"string","enum":["A","B"]}}}}""",
    ).toKoogToolDescriptor(strict = false)

    assertThat(koog.parameter("flags").type)
      .isEqualTo(ToolParameterType.List(ToolParameterType.Enum(arrayOf("A", "B"))))
  }

  @Test
  fun `a record is a free-form object and a union of objects is no more specific than that`() {
    val koog = scriptedTool(
      """
      {"type":"object","properties":{
        "extra":{"type":"object","additionalProperties":{}},
        "shape":{"anyOf":[
          {"type":"object","properties":{"type":{"const":"a"}}},
          {"type":"object","properties":{"type":{"const":"b"}}}]}}}
      """,
    ).toKoogToolDescriptor(strict = false)

    val free = ToolParameterType.Object(emptyList(), additionalProperties = true)
    assertThat(koog.parameter("extra").type).isEqualTo(free)
    assertThat(koog.parameter("shape").type).isEqualTo(free)
  }

  @Test
  fun `scalars and enums behave exactly as before`() {
    val koog = scriptedTool(
      """
      {"type":"object","properties":{
        "count":{"type":"integer"},"label":{"type":"string"},"flag":{"type":"boolean"},
        "mixed":{"anyOf":[{"type":"string"},{"type":"number"}]}}}
      """,
    ).toKoogToolDescriptor(strict = false)

    assertThat(koog.parameter("count").type).isEqualTo(ToolParameterType.Integer)
    assertThat(koog.parameter("label").type).isEqualTo(ToolParameterType.String)
    assertThat(koog.parameter("flag").type).isEqualTo(ToolParameterType.Boolean)
    // Not the schema's business here: a scalar union keeps the flat view's answer (text).
    assertThat(koog.parameter("mixed").type).isEqualTo(ToolParameterType.String)
  }

  @Test
  fun `an array parameter of a tool with no schema stays text`() {
    val koog = TrailblazeToolDescriptor(
      name = "yaml_tool",
      optionalParameters = listOf(TrailblazeToolParameterDescriptor(name = "items", type = "array")),
    ).toKoogToolDescriptor(strict = false)

    assertThat(koog.parameter("items").type).isEqualTo(ToolParameterType.String)
  }

  @Test
  fun `a self-referential schema nested past the depth cap still builds a bounded descriptor`() {
    // 30 levels of `{ "type": "object", "properties": { "n": <next> } }`.
    val deep = (1..30).fold("""{"type":"string"}""") { inner, _ ->
      """{"type":"object","properties":{"n":$inner}}"""
    }
    val koog = scriptedTool("""{"type":"object","properties":{"root":$deep}}""").toKoogToolDescriptor(strict = false)

    assertThat(koog.parameter("root").type).isInstanceOf<ToolParameterType.Object>()
  }

  @Test
  fun `the composite-preserving path (sampling, inner loop) offers the same list shape`() {
    val tool = scriptedTool(
      """{"type":"object","properties":{"names":{"type":"array","items":{"type":"object","properties":{"first":{"type":"string"}}}}}}""",
    )
    val parameter = tool.optionalParameters.single()

    val type = tool.toKoogParameterTypePreservingComposites(parameter)

    assertThat((type as ToolParameterType.List).itemsType).isInstanceOf<ToolParameterType.Object>()
    // Without the tool's schema the mirror alone can only say "list of strings".
    assertThat(parameter.copy(type = "ARRAY").toKoogParameterTypePreservingComposites())
      .isEqualTo(ToolParameterType.List(ToolParameterType.String))
  }
}
