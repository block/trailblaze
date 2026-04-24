package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
}
