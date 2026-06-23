package xyz.block.trailblaze.scripting

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins that [LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor] threads a scripted
 * (`.ts`) tool parameter's JSON-Schema `enum` into [xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor.validValues].
 *
 * A TS string-literal union (`"UP" | "DOWN" | "LEFT" | "RIGHT"`) lowers to
 * `{ "type": "string", "enum": [...] }`. Before this fix the descriptor builder read only `type` +
 * `description`, so the LLM saw `type: string` and never the allowed values — the fidelity gap a
 * directional-swipe tool would otherwise work around by spelling the values into the description prose.
 *
 * The downstream Koog/LLM-schema half of the round-trip (validValues → `ToolParameterType.Enum` →
 * `{"type":"string","enum":[…]}`) is pinned in `TrailblazeKoogToolTest`.
 */
class LazyYamlScriptedToolDescriptorTest {

  @Test
  fun `buildScriptedToolDescriptor surfaces a parameter's JSON-Schema enum as validValues`() {
    val config = InlineScriptToolConfig(
      script = "directional_swipe.ts",
      name = "directional_swipe",
      description = "Swipe the screen in the specified direction.",
      inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          // Enum-typed param: a JSON-Schema `enum` riding alongside `type: "string"`.
          putJsonObject("direction") {
            put("type", "string")
            put("description", "The direction to swipe.")
            putJsonArray("enum") {
              add("UP")
              add("DOWN")
              add("LEFT")
              add("RIGHT")
            }
          }
          // Plain free-text param: no `enum`, so validValues must stay null.
          putJsonObject("swipeOnElementText") {
            put("type", "string")
            put("description", "The text value to swipe on.")
          }
        }
        putJsonArray("required") { add("direction") }
      },
    )

    val descriptor = LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor(config)

    val direction = descriptor.requiredParameters.single { it.name == "direction" }
    assertEquals("string", direction.type, "JSON-Schema enum keeps its underlying `string` type")
    assertEquals(
      listOf("UP", "DOWN", "LEFT", "RIGHT"),
      direction.validValues,
      "the enum's allowed values must surface on the descriptor so the LLM can constrain the arg",
    )

    val freeText = descriptor.optionalParameters.single { it.name == "swipeOnElementText" }
    assertNull(
      freeText.validValues,
      "a parameter with no JSON-Schema enum must not gain validValues (renders as free text)",
    )
  }

  @Test
  fun `empty enum folds to null and a non-string enum is not promoted`() {
    val config = InlineScriptToolConfig(
      script = "edge_cases.ts",
      name = "edge_cases",
      inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
          // An empty `enum: []` carries no usable constraint — it must fold to null, not an empty
          // list, so downstream consumers treat it as "no enum" (pinned because the code only
          // documents this in comments).
          putJsonObject("emptyEnum") {
            put("type", "string")
            putJsonArray("enum") { }
          }
          // A non-string enum (integer-typed) must NOT be promoted: koog enums are string-only, so
          // surfacing it would emit a lying {"type":"string","enum":["1","2"]} schema and the LLM
          // would send "1" instead of 1. The param keeps its integer type and drops the constraint.
          putJsonObject("numericEnum") {
            put("type", "integer")
            putJsonArray("enum") {
              add(1)
              add(2)
              add(3)
            }
          }
        }
      },
    )

    val descriptor = LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor(config)
    val all = descriptor.requiredParameters + descriptor.optionalParameters
    assertNull(all.single { it.name == "emptyEnum" }.validValues, "empty `enum: []` must fold to null")
    val numeric = all.single { it.name == "numericEnum" }
    assertNull(numeric.validValues, "a non-string (integer) enum must not be promoted to validValues")
    assertEquals("integer", numeric.type, "the non-string enum param keeps its declared primitive type")
  }
}
