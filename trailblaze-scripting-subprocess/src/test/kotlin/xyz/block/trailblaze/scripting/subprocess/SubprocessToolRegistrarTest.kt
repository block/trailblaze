package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test

class SubprocessToolRegistrarTest {

  private val emptySchema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList())

  private fun tool(
    name: String,
    description: String? = null,
    meta: JsonObject? = null,
  ): Tool = Tool(
    name = name,
    description = description,
    inputSchema = emptySchema,
    meta = meta,
  )

  @Test fun `unrestricted tools register for every session`() {
    val tools = listOf(tool("search"), tool("fetchPosts"))
    val registered = SubprocessToolRegistrar.filterAdvertisedTools(
      tools,
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = true,
    )
    assertThat(registered.map { it.advertisedName.toolName }).containsExactly("search", "fetchPosts")
  }

  @Test fun `supportedDrivers filters out non-matching tools`() {
    val tools = listOf(
      tool(
        "ios_openKeychain",
        meta = buildJsonObject {
          put("trailblaze/supportedDrivers", buildJsonArray { add("ios-host") })
        },
      ),
      tool("search"),
    )
    val onAndroid = SubprocessToolRegistrar.filterAdvertisedTools(
      tools,
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = true,
    )
    assertThat(onAndroid.map { it.advertisedName.toolName }).containsExactly("search")

    val onIos = SubprocessToolRegistrar.filterAdvertisedTools(
      tools,
      driver = TrailblazeDriverType.IOS_HOST,
      preferHostAgent = true,
    )
    assertThat(onIos.map { it.advertisedName.toolName }).containsExactly("ios_openKeychain", "search")
  }

  @Test fun `requiresHost tools are skipped under on-device-agent mode`() {
    val tools = listOf(
      tool(
        "checkout_applyCoupon",
        meta = buildJsonObject { put("trailblaze/requiresHost", true) },
      ),
      tool("search"),
    )
    val hostAgent = SubprocessToolRegistrar.filterAdvertisedTools(
      tools,
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = true,
    )
    assertThat(hostAgent.map { it.advertisedName.toolName }).containsExactly("checkout_applyCoupon", "search")

    val onDeviceAgent = SubprocessToolRegistrar.filterAdvertisedTools(
      tools,
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = false,
    )
    assertThat(onDeviceAgent.map { it.advertisedName.toolName }).containsExactly("search")
  }

  @Test fun `registered records preserve description inputSchema and parsed meta`() {
    val schema = ToolSchema(
      properties = JsonObject(emptyMap()),
      required = listOf("email"),
    )
    val source = Tool(
      name = "myapp_login",
      description = "Log the current user in",
      inputSchema = schema,
      meta = buildJsonObject {
        put("trailblaze/toolset", "auth")
        put("trailblaze/isRecordable", false)
      },
    )
    val registered = SubprocessToolRegistrar.filterAdvertisedTools(
      listOf(source),
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = true,
    )
    assertThat(registered).hasSize(1)
    val record = registered.single()
    assertThat(record.advertisedName.toolName).isEqualTo("myapp_login")
    assertThat(record.description).isEqualTo("Log the current user in")
    assertThat(record.inputSchema).isEqualTo(schema)
    assertThat(record.meta.toolset).isEqualTo("auth")
    assertThat(record.meta.isRecordable).isEqualTo(false)
  }

  @Test fun `order of output mirrors order of input`() {
    val tools = listOf(tool("a"), tool("b"), tool("c"))
    val registered = SubprocessToolRegistrar.filterAdvertisedTools(
      tools,
      driver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      preferHostAgent = true,
    )
    assertThat(registered.map { it.advertisedName.toolName }).containsExactly("a", "b", "c")
  }
}
