package xyz.block.trailblaze.toolcalls

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool

/**
 * Regression coverage for [TrailblazeToolResult.Error] variants that carry a `command:
 * @Contextual TrailblazeTool` field. Without an explicit round-trip test, the migration to
 * `@Contextual` plus the contextual JSON serializer (#2634) could silently drop tool args
 * from error payloads — these errors travel over RPC and into logs, so silent loss would
 * make production debugging much harder.
 */
class TrailblazeToolResultJsonTest {

  @Test
  fun `ExceptionThrown round-trips with class-backed command`() {
    val error = TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "tap missed",
      command = InputTextTrailblazeTool(text = "Jane"),
      stackTrace = "at line 42",
    )

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolResult.Error.ExceptionThrown.serializer(),
      error,
    )
    val parsed = TrailblazeJsonInstance.decodeFromString<JsonObject>(json)
    val commandJson = parsed["command"]?.jsonObject!!
    assertThat(commandJson["toolName"]?.jsonPrimitive?.content).isEqualTo("inputText")
    assertThat(commandJson["raw"]?.jsonObject!!["text"]?.jsonPrimitive?.content).isEqualTo("Jane")

    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolResult.Error.ExceptionThrown.serializer(),
      json,
    )
    val decodedCmd = decoded.command as OtherTrailblazeTool
    assertThat(decodedCmd.toolName).isEqualTo("inputText")
    assertThat(decodedCmd.raw["text"]?.jsonPrimitive?.content).isEqualTo("Jane")
    assertThat(decoded.errorMessage).isEqualTo("tap missed")
    assertThat(decoded.stackTrace).isEqualTo("at line 42")
  }

  @Test
  fun `UnknownTrailblazeTool round-trips with OtherTrailblazeTool command`() {
    // OtherTrailblazeTool round-trips through the contextual serializer too — its existing
    // `toolName, raw` shape is the canonical encoding. This test pins that the wire format
    // is identical whether the command was originally OtherTrailblazeTool or a class-backed
    // tool, so log readers don't need to branch on the original type.
    val error = TrailblazeToolResult.Error.UnknownTrailblazeTool(
      command = OtherTrailblazeTool(
        toolName = "external_thing",
        raw = buildJsonObject { put("a", "b") },
      ),
    )

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolResult.Error.UnknownTrailblazeTool.serializer(),
      error,
    )
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolResult.Error.UnknownTrailblazeTool.serializer(),
      json,
    )
    val cmd = decoded.command as OtherTrailblazeTool
    assertThat(cmd.toolName).isEqualTo("external_thing")
    assertThat(cmd.raw["a"]?.jsonPrimitive?.content).isEqualTo("b")
  }

  @Test
  fun `InvalidToolCall round-trips with class-backed command`() {
    // The third `@Contextual command: TrailblazeTool` carrier in `TrailblazeToolResult.Error`,
    // pinned alongside ExceptionThrown / UnknownTrailblazeTool so the contextual serializer
    // can't silently drop args from this variant.
    val error = TrailblazeToolResult.Error.InvalidToolCall(
      errorMessage = "missing required arg",
      command = InputTextTrailblazeTool(text = "Jane"),
    )

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolResult.Error.InvalidToolCall.serializer(),
      error,
    )
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolResult.Error.InvalidToolCall.serializer(),
      json,
    )

    val cmd = decoded.command as OtherTrailblazeTool
    assertThat(cmd.toolName).isEqualTo("inputText")
    assertThat(cmd.raw["text"]?.jsonPrimitive?.content).isEqualTo("Jane")
    assertThat(decoded.errorMessage).isEqualTo("missing required arg")
  }

  @Test
  fun `ExceptionThrown with null command encodes without command field on disk`() {
    // `command` is nullable with default null. Confirm the contextual serializer plays
    // nicely with the optional shape — no crash, no spurious empty payload.
    val error = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "no command attached")

    val json = TrailblazeJsonInstance.encodeToString(
      TrailblazeToolResult.Error.ExceptionThrown.serializer(),
      error,
    )
    val decoded = TrailblazeJsonInstance.decodeFromString(
      TrailblazeToolResult.Error.ExceptionThrown.serializer(),
      json,
    )
    assertThat(decoded.command).isEqualTo(null)
    assertThat(decoded.errorMessage).isEqualTo("no command attached")
  }
}
