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
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig

/**
 * Unit tests for the `.trail.yaml` schema generator. Pure (catalog + target tool-name set in, schema
 * out). Pins the things that matter: (1) the schema is a `oneOf` over the v1-list and unified-map
 * shapes, (2) a v1 item is a single OPEN object (so an unmodeled item like `- trailhead:` never
 * false-flags), (3) the `recording:` → `tools:` block carries the target-scoped, open tool-call
 * item so tool calls autocomplete/validate — reusing [ToolYamlSchemaBuilder]'s tool-call shape, and
 * (4) the unified branch completes the same surface (config fields + tool names under a step's or a
 * trailhead's per-classifier `recording:`) rather than nothing.
 */
class TrailYamlSchemaBuilderTest {

  private fun entry(id: String, trailmap: String) =
    ToolCatalogEntry(id = id, flavor = ToolFlavor.KOTLIN, trailmap = trailmap, sourcePath = "x", description = null, parameters = emptyList())

  private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  private fun branches(schema: JsonObject) = schema["oneOf"]!!.jsonArray.map { it.jsonObject }

  // The v1 shape is the array-typed branch; unified is the object-typed branch.
  private fun v1Branch(schema: JsonObject) = branches(schema).first { it["type"]!!.toString().trim('"') == "array" }

  private fun unifiedBranch(schema: JsonObject) = branches(schema).first { it["type"]!!.toString().trim('"') == "object" }

  private fun unifiedProps(schema: JsonObject) = unifiedBranch(schema)["properties"]!!.jsonObject

  private fun unifiedStep(schema: JsonObject) = unifiedProps(schema)["trail"]!!.jsonObject["items"]!!.jsonObject

  // A unified `recording:` is keyed by dynamic device classifier, so the per-classifier value schema
  // hangs off `additionalProperties` (a tool-call list for a step, a single tool call for a trailhead).
  private fun unifiedClassifierRecording(step: JsonObject) =
    step["properties"]!!.jsonObject["recording"]!!.jsonObject["additionalProperties"]!!.jsonObject

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
  fun `a unified config block completes the config fields`() {
    val schema = parse(TrailYamlSchemaBuilder.build(emptyList(), targetToolNames = null))
    val configProps = unifiedProps(schema)["config"]!!.jsonObject["properties"]!!.jsonObject
    assertThat(configProps.keys).contains("target")
    assertThat(configProps.keys).contains("title")
    // Per-classifier in the unified format (v1's `driver:`/`platform:` scalars are retired), so they
    // must be typed as maps or a real unified config gets flagged.
    assertThat(configProps.keys).contains("devices")
    assertThat(configProps["devices"]!!.jsonObject["type"]!!.toString().trim('"')).isEqualTo("object")
    assertThat(configProps["skip"]!!.jsonObject["type"]!!.toString().trim('"')).isEqualTo("object")
  }

  @Test
  fun `every unified config field the schema documents is a real UnifiedTrailConfig field`() {
    // Same drift guard as the v1 config block, against the unified config model.
    val schema = parse(TrailYamlSchemaBuilder.build(emptyList(), targetToolNames = null))
    val configKeys = unifiedProps(schema)["config"]!!.jsonObject["properties"]!!.jsonObject.keys
    val unifiedConfigFields = UnifiedTrailConfig.serializer().descriptor.elementNames.toSet()
    configKeys.forEach { key ->
      assertThat(unifiedConfigFields).contains(key)
    }
  }

  @Test
  fun `a unified step recording completes tool names under a device classifier`() {
    val catalog = listOf(entry("myapp_tapCharge", "myapp"), entry("mobile_maestro", "trailblaze"), entry("otherapp_sendMoney", "otherapp"))
    val schema = parse(TrailYamlSchemaBuilder.build(catalog, targetToolNames = setOf("myapp_tapCharge")))
    val step = unifiedStep(schema)
    val stepProps = step["properties"]!!.jsonObject
    assertThat(stepProps["step"]).isNotNull()
    assertThat(stepProps["verify"]).isNotNull()
    // A step's classifier value is a list of tool calls; the tool names complete on its items.
    val toolCallItem = unifiedClassifierRecording(step)["items"]!!.jsonObject
    val toolNames = toolCallItem["properties"]!!.jsonObject.keys
    assertThat(toolNames).contains("myapp_tapCharge")
    assertThat(toolNames).contains("mobile_maestro") // framework tools always in scope
    assertThat(toolNames).doesNotContain("otherapp_sendMoney")
    // Open like the v1 branch: an unmodeled step field never false-flags a valid unified step.
    assertThat(step["additionalProperties"].toString()).isEqualTo("true")
  }

  @Test
  fun `a unified trailhead completes tool names and allows an empty no-op recording`() {
    val catalog = listOf(entry("myapp_launch", "myapp"))
    val schema = parse(TrailYamlSchemaBuilder.build(catalog, targetToolNames = null))
    val trailhead = unifiedProps(schema)["trailhead"]!!.jsonObject
    // A trailhead classifier records at most ONE tool call directly (never a list), or `{}` for an
    // explicit no-op — so no minProperties floor.
    val classifierRecording = unifiedClassifierRecording(trailhead)
    assertThat(classifierRecording["properties"]!!.jsonObject.keys).contains("myapp_launch")
    assertThat(classifierRecording["maxProperties"].toString()).isEqualTo("1")
    assertThat(classifierRecording.containsKey("minProperties")).isEqualTo(false)
  }

  @Test
  fun `a unified trailhead does not offer verify, which its parser rejects`() {
    // UnifiedTrailStepSerializer(isTrailhead = true) throws on `verify:` — a trailhead is a
    // deterministic bootstrap, not an assertion. Completing it would suggest an unloadable trail.
    val schema = parse(TrailYamlSchemaBuilder.build(emptyList(), targetToolNames = null))
    val trailheadProps = unifiedProps(schema)["trailhead"]!!.jsonObject["properties"]!!.jsonObject
    assertThat(trailheadProps["step"]).isNotNull()
    assertThat(trailheadProps.containsKey("verify")).isEqualTo(false)
  }

  @Test
  fun `the unified branch requires nothing, so a half-written mapping still matches it`() {
    // Both branches of the `oneOf` are open: requiring `config` + `trail` here would make a mapping
    // that only has `config:` so far match neither branch and report "matches no schema" mid-edit.
    val schema = parse(TrailYamlSchemaBuilder.build(emptyList(), targetToolNames = null))
    val unified = unifiedBranch(schema)
    assertThat(unified["additionalProperties"].toString()).isEqualTo("true")
    assertThat(unified.containsKey("required")).isEqualTo(false)
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
