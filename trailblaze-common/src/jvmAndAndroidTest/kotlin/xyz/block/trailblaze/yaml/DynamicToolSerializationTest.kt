package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder

class DynamicToolSerializationTest {

  @Test
  fun `raw-argument dynamic tool encodes to yaml with instance name and args`() {
    val yaml = createTrailblazeYaml().encodeToString(
      TrailblazeYamlBuilder()
        .tools(
          listOf(
            FakeDynamicTool(
              instanceToolName = "ios_contacts_create_contact",
              rawToolArguments = buildJsonObject {
                put("firstName", "Ada")
                put("lastName", "Lovelace")
              },
            ),
          ),
        )
        .build(),
    )

    assertThat(yaml).contains("ios_contacts_create_contact:")
    assertThat(yaml).contains("firstName: Ada")
    assertThat(yaml).contains("lastName: Lovelace")
  }

  @Test
  fun `raw-argument dynamic tool produces flat OtherTrailblazeTool log payload`() {
    val tool = FakeDynamicTool(
      instanceToolName = "ios_contacts_open_saved_contact",
      rawToolArguments = buildJsonObject { put("fullName", "Ada Lovelace") },
    )

    val payload = tool.toLogPayload()

    assertThat(payload.toolName).isEqualTo("ios_contacts_open_saved_contact")
    assertThat(payload.raw["fullName"]).isEqualTo(JsonPrimitive("Ada Lovelace"))
  }

  private data class FakeDynamicTool(
    override val instanceToolName: String,
    override val rawToolArguments: JsonObject,
  ) : RawArgumentTrailblazeTool
}
