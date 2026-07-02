package xyz.block.trailblaze.trailrunner

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Unit tests for the registry-driven `.tool.yaml` schema generator. Pure (catalog in, schema out), so
 * the dynamic part — tool ids become the allowed keys under `tools:`, with their params — is pinned
 * here without a daemon. The route's wiring/envelope is covered separately in TrailRunnerIntegrationTest.
 */
class ToolYamlSchemaBuilderTest {

  private fun entry(id: String, trailmap: String, params: List<ToolParamDto> = emptyList(), description: String? = null) =
    ToolCatalogEntry(id = id, flavor = ToolFlavor.KOTLIN, trailmap = trailmap, sourcePath = "x", description = description, parameters = params)

  private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  // Navigate to properties under `tools.items.properties` — the per-tool-call key set.
  private fun toolCallProps(schema: JsonObject): JsonObject =
    schema["properties"]!!.jsonObject["tools"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject

  @Test
  fun `toolCallItemSchema closes the tool-name set for tools but opens it for trails`() {
    // The shared builder is reused by TrailYamlSchemaBuilder with closedToolNames=false; the ONLY
    // difference between the two must be additionalProperties (closed vs open) — the documented tool
    // properties stay identical. Locks that contract so a refactor can't silently flip the flag's effect.
    val tools = listOf(entry("myapp_a", "myapp"), entry("mobile_maestro", "trailblaze"))
    val closed = ToolYamlSchemaBuilder.toolCallItemSchema(tools, closedToolNames = true)
    val open = ToolYamlSchemaBuilder.toolCallItemSchema(tools, closedToolNames = false)
    assertThat(closed["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
    assertThat(open["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("true")
    assertThat(open["properties"]).isEqualTo(closed["properties"])
  }

  @Test
  fun `tools items list the in-scope tool ids as allowed keys`() {
    val catalog = listOf(
      entry("myapp_tapCharge", "myapp"),
      entry("mobile_maestro", "trailblaze"),
      entry("otherapp_sendMoney", "otherapp"),
    )
    val schema = parse(ToolYamlSchemaBuilder.build(catalog, trailmap = "myapp"))
    val props = toolCallProps(schema)
    // myapp (this trailmap) + trailblaze (framework) are in scope; otherapp is not.
    assertThat(props.keys).contains("myapp_tapCharge")
    assertThat(props.keys).contains("mobile_maestro")
    assertThat(props.keys).doesNotContain("otherapp_sendMoney")
  }

  @Test
  fun `tool-call items are closed to known tool names (typo detection) but each tool's args stay open`() {
    val schema = parse(ToolYamlSchemaBuilder.build(listOf(entry("myapp_tapCharge", "myapp")), "myapp"))
    val items = schema["properties"]!!.jsonObject["tools"]!!.jsonObject["items"]!!.jsonObject
    // Closed at the tool-name level → an unknown/typo'd tool is flagged + names autocomplete.
    assertThat(items["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
    assertThat(items["maxProperties"]!!.jsonPrimitive.content).isEqualTo("1")
    // ...but each tool's argument object is open (catalog params are lossy for framework tools).
    val argsSchema = items["properties"]!!.jsonObject["myapp_tapCharge"]!!.jsonObject
    assertThat(argsSchema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("true")
  }

  @Test
  fun `a tool's args object also accepts null so a body-less tool entry is not flagged`() {
    // `- myapp_tapCharge:` with no args (null value) is a valid composition of a no-arg tool; the
    // args schema must accept object OR null so it isn't flagged "Incorrect type. Expected object".
    val schema = parse(ToolYamlSchemaBuilder.build(listOf(entry("myapp_tapCharge", "myapp")), "myapp"))
    val argsType = toolCallProps(schema)["myapp_tapCharge"]!!.jsonObject["type"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertThat(argsType).contains("object")
    assertThat(argsType).contains("null")
  }

  @Test
  fun `a tool's declared params become typed, described properties for completion`() {
    val catalog = listOf(
      entry(
        "myapp_setAmount", "myapp",
        params = listOf(
          ToolParamDto(name = "cents", type = "integer", required = true, description = "Amount in cents"),
          ToolParamDto(name = "label", type = "string", required = false),
        ),
      ),
    )
    val schema = parse(ToolYamlSchemaBuilder.build(catalog, "myapp"))
    val argProps = toolCallProps(schema)["myapp_setAmount"]!!.jsonObject["properties"]!!.jsonObject
    assertThat(argProps["cents"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("integer")
    assertThat(argProps["cents"]!!.jsonObject["description"]!!.jsonPrimitive.content).isEqualTo("Amount in cents")
    assertThat(argProps["label"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("string")
  }

  @Test
  fun `null trailmap includes every tool`() {
    val catalog = listOf(entry("myapp_a", "myapp"), entry("otherapp_b", "otherapp"), entry("mm", "trailblaze"))
    val props = toolCallProps(parse(ToolYamlSchemaBuilder.build(catalog, trailmap = null)))
    assertThat(props.keys).contains("myapp_a")
    assertThat(props.keys).contains("otherapp_b")
    assertThat(props.keys).contains("mm")
  }

  @Test
  fun `the envelope is always present even with an empty catalog`() {
    val schema = parse(ToolYamlSchemaBuilder.build(emptyList(), "myapp"))
    val props = schema["properties"]!!.jsonObject
    assertThat(props["id"]).isNotNull()
    assertThat(props["parameters"]).isNotNull()
    assertThat(props["shortcut"]).isNotNull()
    assertThat(schema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
  }

  @Test
  fun `parameter-definition items are closed and document the default field`() {
    // The param-definition fields are a known closed set, so a typo'd key must be flagged
    // (additionalProperties:false) and `default` must be a documented property for completion/hover.
    val schema = parse(ToolYamlSchemaBuilder.build(emptyList(), "myapp"))
    val paramItems = schema["properties"]!!.jsonObject["parameters"]!!.jsonObject["items"]!!.jsonObject
    assertThat(paramItems["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
    val paramProps = paramItems["properties"]!!.jsonObject
    assertThat(paramProps["name"]).isNotNull()
    assertThat(paramProps["type"]).isNotNull()
    assertThat(paramProps["default"]).isNotNull()
  }

  @Test
  fun `jsonTypeFor maps common types and leaves unknowns unconstrained`() {
    assertThat(ToolYamlSchemaBuilder.jsonTypeFor("integer")).isEqualTo("integer")
    assertThat(ToolYamlSchemaBuilder.jsonTypeFor("Boolean")).isEqualTo("boolean")
    assertThat(ToolYamlSchemaBuilder.jsonTypeFor("string")).isEqualTo("string")
    assertThat(ToolYamlSchemaBuilder.jsonTypeFor("SomeCustomType")).isNull()
  }
}
