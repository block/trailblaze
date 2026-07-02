package xyz.block.trailblaze.trailrunner

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import xyz.block.trailblaze.yaml.TrailConfig

/**
 * Unit tests for the `.trail.yaml` schema generator. Pure (catalog + target tool-name set in, schema
 * out). Pins the things that matter: (1) the schema is a `oneOf` over the v1-list and unified-map
 * shapes, (2) a v1 item is a single OPEN object (so an unmodeled item like `- trailhead:` never
 * false-flags), and (3) the `recording:` → `tools:` block carries the target-scoped, open tool-call
 * item so tool calls autocomplete/validate — reusing [ToolYamlSchemaBuilder]'s tool-call shape.
 */
class TrailYamlSchemaBuilderTest {

  private fun entry(id: String, trailmap: String) =
    ToolCatalogEntry(id = id, flavor = ToolFlavor.KOTLIN, trailmap = trailmap, sourcePath = "x", description = null, parameters = emptyList())

  private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  private fun branches(schema: JsonObject) = schema["oneOf"]!!.jsonArray.map { it.jsonObject }

  // The v1 shape is the array-typed branch; unified is the object-typed branch.
  private fun v1Branch(schema: JsonObject) = branches(schema).first { it["type"]!!.toString().trim('"') == "array" }

  // A v1 list item is a single open object with config/prompts/tools properties.
  private fun v1Item(schema: JsonObject) = v1Branch(schema)["items"]!!.jsonObject

  private fun v1ItemProps(schema: JsonObject) = v1Item(schema)["properties"]!!.jsonObject

  // The tool-call item lives at: v1 item → properties.prompts.items → recording.tools.items
  private fun recordingToolCallItem(schema: JsonObject): JsonObject {
    val step = v1ItemProps(schema)["prompts"]!!.jsonObject["items"]!!.jsonObject
    val recording = step["properties"]!!.jsonObject["recording"]!!.jsonObject
    return recording["properties"]!!.jsonObject["tools"]!!.jsonObject["items"]!!.jsonObject
  }

  private fun recordingToolCallProps(schema: JsonObject): JsonObject =
    recordingToolCallItem(schema)["properties"]!!.jsonObject

  @Test
  fun `schema is a oneOf over the v1-list and unified-map shapes`() {
    val schema = parse(TrailYamlSchemaBuilder.build(emptyList(), targetToolNames = null))
    val kinds = branches(schema).map { it["type"]!!.toString().trim('"') }
    assertThat(kinds).contains("array") // v1 list
    assertThat(kinds).contains("object") // unified mapping
  }

  @Test
  fun `recording tools autocomplete the in-target tool ids`() {
    val catalog = listOf(entry("myapp_tapCharge", "myapp"), entry("otherapp_sendMoney", "otherapp"))
    // Target resolves to just myapp_tapCharge.
    val schema = parse(TrailYamlSchemaBuilder.build(catalog, targetToolNames = setOf("myapp_tapCharge")))
    val props = recordingToolCallProps(schema)
    assertThat(props.keys).contains("myapp_tapCharge")
    assertThat(props.keys).doesNotContain("otherapp_sendMoney")
  }

  @Test
  fun `recording tool names are open and always include framework tools`() {
    // Framework tools are in scope even when the target set excludes them (recordings use primitives
    // like mobile_maestro), and the tool-call item is OPEN so an unknown recorded tool is never flagged.
    val catalog = listOf(entry("myapp_tapCharge", "myapp"), entry("mobile_maestro", "trailblaze"))
    val schema = parse(TrailYamlSchemaBuilder.build(catalog, targetToolNames = setOf("myapp_tapCharge")))
    val toolCallItem = recordingToolCallItem(schema)
    // Framework tool present despite not being in the target set.
    assertThat(toolCallItem["properties"]!!.jsonObject.keys).contains("mobile_maestro")
    // Open: unknown recorded tool names are permitted (not flagged).
    assertThat(toolCallItem["additionalProperties"].toString()).isEqualTo("true")
  }

  @Test
  fun `null target tool names falls back to the whole catalog`() {
    val catalog = listOf(entry("myapp_a", "myapp"), entry("otherapp_b", "otherapp"))
    val props = recordingToolCallProps(parse(TrailYamlSchemaBuilder.build(catalog, targetToolNames = null)))
    assertThat(props.keys).contains("myapp_a")
    assertThat(props.keys).contains("otherapp_b")
  }

  @Test
  fun `a v1 item documents config prompts and tools but stays open for unmodeled item shapes`() {
    val schema = parse(TrailYamlSchemaBuilder.build(listOf(entry("myapp_a", "myapp")), targetToolNames = null))
    val item = v1Item(schema)
    // Open + no required: a `- trailhead:` (or any future item kind) passes instead of tripping a
    // false "matches no branch" error.
    assertThat(item["additionalProperties"].toString()).isEqualTo("true")
    assertThat(item.containsKey("required")).isEqualTo(false)
    val props = item["properties"]!!.jsonObject
    assertThat(props.keys).contains("config")
    assertThat(props.keys).contains("prompts")
    assertThat(props.keys).contains("tools")
    // config completion still exposes the target field (drives config-block completion/hover).
    assertThat(props["config"]!!.jsonObject["properties"]!!.jsonObject.keys).contains("target")
  }

  @Test
  fun `every config field the schema documents is a real TrailConfig field`() {
    // The config-block completion keys are hand-mirrored from TrailConfig. This guards the drift that
    // matters: a renamed/removed TrailConfig field leaving a stale key in the schema. (The reverse —
    // TrailConfig gaining a field the schema omits — only costs a missing completion, never an error,
    // since config is additionalProperties:true, so it's intentionally not asserted here.)
    val schema = parse(TrailYamlSchemaBuilder.build(emptyList(), targetToolNames = null))
    val configKeys = v1ItemProps(schema)["config"]!!.jsonObject["properties"]!!.jsonObject.keys
    val trailConfigFields = TrailConfig.serializer().descriptor.elementNames.toSet()
    configKeys.forEach { key ->
      assertThat(trailConfigFields).contains(key)
    }
  }

  @Test
  fun `a step accepts either step or verify and keeps unknown fields open`() {
    val schema = parse(TrailYamlSchemaBuilder.build(emptyList(), targetToolNames = null))
    val step = v1ItemProps(schema)["prompts"]!!.jsonObject["items"]!!.jsonObject
    // Permissive: never flag a valid step for an unmodeled field.
    assertThat(step["additionalProperties"].toString()).isEqualTo("true")
    val stepProps = step["properties"]!!.jsonObject
    assertThat(stepProps["step"]).isNotNull()
    assertThat(stepProps["verify"]).isNotNull()
  }
}
