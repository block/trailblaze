package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.toolcalls.ToolName
import kotlin.test.Test

class SubprocessToolRegistrationTest {

  private val emptySchema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList())

  private val schemaWithEmail = ToolSchema(
    properties = buildJsonObject {
      putJsonObject("email") { put("type", "string"); put("description", "email") }
    },
    required = listOf("email"),
  )

  private fun stubProvider(): () -> McpSubprocessSession = {
    error("session not needed during descriptor construction")
  }

  @Test fun `trailblazeDescriptor mirrors the advertised name description and schema`() {
    val registered = RegisteredSubprocessTool(
      advertisedName = ToolName("myapp_login"),
      description = "Log in",
      inputSchema = schemaWithEmail,
      meta = TrailblazeToolMeta(),
    )
    val registration = SubprocessToolRegistration(registered, stubProvider())

    assertThat(registration.name).isEqualTo(ToolName("myapp_login"))
    assertThat(registration.trailblazeDescriptor.name).isEqualTo("myapp_login")
    assertThat(registration.trailblazeDescriptor.description).isEqualTo("Log in")
    assertThat(registration.trailblazeDescriptor.requiredParameters.single().name).isEqualTo("email")
  }

  @Test fun `decodeToolCall returns a SubprocessTrailblazeTool bound to this source`() {
    val registered = RegisteredSubprocessTool(
      advertisedName = ToolName("myapp_login"),
      description = null,
      inputSchema = emptySchema,
      meta = TrailblazeToolMeta(),
    )
    val registration = SubprocessToolRegistration(registered, stubProvider())

    val decoded = registration.decodeToolCall("""{"email":"a@b.c"}""")
    assertThat(decoded).isInstanceOf(SubprocessTrailblazeTool::class)
      .prop(SubprocessTrailblazeTool::advertisedName).isEqualTo(ToolName("myapp_login"))
  }

  @Test fun `buildKoogTool wires the serializer and descriptor`() {
    val registered = RegisteredSubprocessTool(
      advertisedName = ToolName("tick"),
      description = "tick the clock",
      inputSchema = emptySchema,
      meta = TrailblazeToolMeta(),
    )
    val registration = SubprocessToolRegistration(registered, stubProvider())
    val koogTool = registration.buildKoogTool { error("context not needed to build") }

    assertThat(koogTool.descriptor.name).isEqualTo("tick")
    assertThat(koogTool.descriptor.description).isEqualTo("tick the clock")
  }
}
