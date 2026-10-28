package xyz.block.trailblaze.trailrunner

import assertk.assertThat
import assertk.assertions.contains
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
 * Unit tests for the `.tool.yaml` envelope schema and the registry-driven tool-call item schema that
 * `.trail.yaml` recordings reuse. Pure (inputs in, schema out), so both are pinned here without a
 * daemon. The route's wiring is covered separately in TrailRunnerIntegrationTest.
 */
class ToolYamlSchemaBuilderTest {

  private fun entry(id: String, trailmap: String, params: List<ToolParamDto> = emptyList(), description: String? = null) =
    ToolCatalogEntry(id = id, flavor = ToolFlavor.KOTLIN, trailmap = trailmap, sourcePath = "x", description = description, parameters = params)

  private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

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
  fun `tool-call items are closed to known tool names (typo detection) but each tool's args stay open`() {
    val items = ToolYamlSchemaBuilder.toolCallItemSchema(listOf(entry("myapp_tapCharge", "myapp")))
    // Closed at the tool-name level → an unknown/typo'd tool is flagged + names autocomplete.
    assertThat(items["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
    assertThat(items["maxProperties"]!!.jsonPrimitive.content).isEqualTo("1")
    // ...but each tool's argument object is open (catalog params are lossy for framework tools).
    val argsSchema = items["properties"]!!.jsonObject["myapp_tapCharge"]!!.jsonObject
    assertThat(argsSchema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("true")
  }

  @Test
  fun `a tool's args object also accepts null so a body-less tool entry is not flagged`() {
    // `- myapp_tapCharge:` with no args (null value) is a valid call of a no-arg tool; the args
    // schema must accept object OR null so it isn't flagged "Incorrect type. Expected object".
    val items = ToolYamlSchemaBuilder.toolCallItemSchema(listOf(entry("myapp_tapCharge", "myapp")))
    val argsType = items["properties"]!!.jsonObject["myapp_tapCharge"]!!.jsonObject["type"]!!.jsonArray
      .map { it.jsonPrimitive.content }
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
    val items = ToolYamlSchemaBuilder.toolCallItemSchema(catalog)
    val argProps = items["properties"]!!.jsonObject["myapp_setAmount"]!!.jsonObject["properties"]!!.jsonObject
    assertThat(argProps["cents"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("integer")
    assertThat(argProps["cents"]!!.jsonObject["description"]!!.jsonPrimitive.content).isEqualTo("Amount in cents")
    assertThat(argProps["label"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("string")
  }

  @Test
  fun `the tool yaml envelope offers id, class and trailhead and rejects a tools body`() {
    val schema = parse(ToolYamlSchemaBuilder.build())
    val props = schema["properties"]!!.jsonObject
    assertThat(props["id"]).isNotNull()
    assertThat(props["class"]).isNotNull()
    assertThat(props["trailhead"]).isNotNull()
    // Closed envelope: a retired `tools:` composition body (or `parameters:`) is not a known key, so
    // the editor flags it instead of accepting a file the loader rejects.
    assertThat(props["tools"]).isNull()
    assertThat(props["parameters"]).isNull()
    assertThat(schema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
  }

  @Test
  fun `jsonTypeFor maps common types and leaves unknowns unconstrained`() {
    assertThat(ToolYamlSchemaBuilder.jsonTypeFor("integer")).isEqualTo("integer")
    assertThat(ToolYamlSchemaBuilder.jsonTypeFor("Boolean")).isEqualTo("boolean")
    assertThat(ToolYamlSchemaBuilder.jsonTypeFor("string")).isEqualTo("string")
    assertThat(ToolYamlSchemaBuilder.jsonTypeFor("SomeCustomType")).isNull()
  }
}
