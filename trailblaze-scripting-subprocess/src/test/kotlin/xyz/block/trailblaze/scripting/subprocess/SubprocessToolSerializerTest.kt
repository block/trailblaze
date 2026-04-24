package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.toolcalls.ToolName
import kotlin.test.Test

class SubprocessToolSerializerTest {

  private fun fakeSessionProvider(): () -> McpSubprocessSession = {
    error("session not expected to be invoked during serialization tests")
  }

  @Test fun `deserialize populates a bound SubprocessTrailblazeTool with LLM args`() {
    val serializer = SubprocessToolSerializer(
      advertisedName = ToolName("myapp_login"),
      sessionProvider = fakeSessionProvider(),
    )
    val argsJson = """{"email":"user@example.com","rememberMe":true}"""
    val tool = Json.decodeFromString(serializer, argsJson)

    assertThat(tool.advertisedName).isEqualTo(ToolName("myapp_login"))
    assertThat(tool.args).isEqualTo(
      buildJsonObject {
        put("email", "user@example.com")
        put("rememberMe", true)
      },
    )
  }

  @Test fun `deserialize treats empty object as no args`() {
    val serializer = SubprocessToolSerializer(ToolName("simple"), fakeSessionProvider())
    val tool = Json.decodeFromString(serializer, "{}")
    assertThat(tool.args).isEqualTo(JsonObject(emptyMap()))
  }

  @Test fun `serialize emits the raw args JsonObject`() {
    val serializer = SubprocessToolSerializer(ToolName("roundtrip"), fakeSessionProvider())
    val args = buildJsonObject { put("x", JsonPrimitive(42)) }
    val tool = SubprocessTrailblazeTool(fakeSessionProvider(), ToolName("roundtrip"), args)
    val encoded = Json.encodeToString(serializer, tool)
    assertThat(encoded).isEqualTo("""{"x":42}""")
  }

  @Test fun `serializer wires the sessionProvider through to the deserialized instance`() {
    val provider: () -> McpSubprocessSession = { error("never called in test") }
    val serializer = SubprocessToolSerializer(ToolName("ping"), provider)
    val tool = Json.decodeFromString(serializer, "{}")
    assertThat(tool.sessionProvider).isSameInstanceAs(provider)
  }
}
