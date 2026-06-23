package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import kotlin.test.Test

class SubprocessToolDescriptorsTest {

  @Test fun `empty schema produces a descriptor with no parameters`() {
    val schema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList())
    val descriptor = schema.toTrailblazeToolDescriptor("ping", description = null)
    assertThat(descriptor.name).isEqualTo("ping")
    assertThat(descriptor.description).isEqualTo(null)
    assertThat(descriptor.requiredParameters).containsExactly()
    assertThat(descriptor.optionalParameters).containsExactly()
  }

  @Test fun `properties split into required vs optional`() {
    val schema = ToolSchema(
      properties = buildJsonObject {
        putJsonObject("email") {
          put("type", "string")
          put("description", "User's email")
        }
        putJsonObject("rememberMe") {
          put("type", "boolean")
        }
      },
      required = listOf("email"),
    )
    val descriptor = schema.toTrailblazeToolDescriptor("myapp_login", "Log in")

    assertThat(descriptor.requiredParameters).containsExactly(
      TrailblazeToolParameterDescriptor("email", "string", "User's email"),
    )
    assertThat(descriptor.optionalParameters).containsExactlyInAnyOrder(
      TrailblazeToolParameterDescriptor("rememberMe", "boolean", null),
    )
  }

  @Test fun `missing type falls back to string`() {
    val schema = ToolSchema(
      properties = buildJsonObject {
        putJsonObject("raw") { /* no type */ }
      },
      required = emptyList(),
    )
    val descriptor = schema.toTrailblazeToolDescriptor("x", null)
    assertThat(descriptor.optionalParameters.single().type).isEqualTo("string")
  }

  @Test fun `a property's JSON-Schema enum surfaces as validValues`() {
    val schema = ToolSchema(
      properties = buildJsonObject {
        putJsonObject("direction") {
          put("type", "string")
          put("description", "which direction")
          putJsonArray("enum") {
            add("UP")
            add("DOWN")
            add("LEFT")
            add("RIGHT")
          }
        }
        putJsonObject("label") {
          put("type", "string")
        }
      },
      required = listOf("direction"),
    )
    val descriptor = schema.toTrailblazeToolDescriptor("swipe", "Swipe")

    // The enum's allowed values ride through to the descriptor so the LLM-facing schema can
    // constrain the argument rather than showing a bare `type: string`.
    assertThat(descriptor.requiredParameters).containsExactly(
      TrailblazeToolParameterDescriptor(
        name = "direction",
        type = "string",
        description = "which direction",
        validValues = listOf("UP", "DOWN", "LEFT", "RIGHT"),
      ),
    )
    // A plain string property must not gain validValues.
    assertThat(descriptor.optionalParameters.single().validValues).isNull()
  }

  @Test fun `a non-string enum is not promoted to validValues`() {
    // An MCP server can advertise an integer (or boolean) enum. Koog's enum type is string-only, so
    // surfacing it would emit a lying {"type":"string","enum":["1","2"]} schema — the LLM would send
    // "1" instead of 1 and the tool would receive the wrong JSON type. The param must keep its
    // integer type and drop the (koog-inexpressible) enum constraint.
    val schema = ToolSchema(
      properties = buildJsonObject {
        putJsonObject("count") {
          put("type", "integer")
          putJsonArray("enum") {
            add(1)
            add(2)
            add(3)
          }
        }
      },
      required = listOf("count"),
    )
    val descriptor = schema.toTrailblazeToolDescriptor("pick", "Pick")
    val count = descriptor.requiredParameters.single()
    assertThat(count.type).isEqualTo("integer")
    assertThat(count.validValues).isNull()
  }
}
