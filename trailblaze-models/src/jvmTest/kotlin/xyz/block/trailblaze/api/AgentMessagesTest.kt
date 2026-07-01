package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.api.AgentMessages.toContentString
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class AgentMessagesTest {

  private val noArgs: JsonObject = buildJsonObject {}

  @Test
  fun `explicit message wins over structuredContent`() {
    val result = TrailblazeToolResult.Success(
      message = "custom summary",
      structuredContent = Json.parseToJsonElement("""{"appIds":["com.a"]}"""),
    )

    val content = result.toContentString("mobile_listInstalledApps", noArgs)

    assertTrue(content.contains("custom summary"))
    assertFalse(content.contains("appIds"))
  }

  @Test
  fun `null message falls back to compact structuredContent JSON`() {
    val result = TrailblazeToolResult.Success(
      structuredContent = Json.parseToJsonElement("""{"appIds":["com.a","com.b"]}"""),
    )

    val content = result.toContentString("mobile_listInstalledApps", noArgs)

    assertTrue(content.contains("""{"appIds":["com.a","com.b"]}"""))
  }

  @Test
  fun `null message and null structuredContent falls back to args dump`() {
    val args = buildJsonObject { put("x", 1) }
    val result = TrailblazeToolResult.Success()

    val content = result.toContentString("tapOnPoint", args)

    assertTrue(content.contains("Successfully used the `tapOnPoint` tool"))
  }
}
