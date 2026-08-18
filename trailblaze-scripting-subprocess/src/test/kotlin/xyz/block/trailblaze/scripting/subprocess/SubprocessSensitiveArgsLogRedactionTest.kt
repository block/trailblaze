package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.toolcalls.DeclaredSensitiveArgs
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.toLogPayload
import kotlin.test.Test

/**
 * Pins the log-encode redaction contract for a **scripted (TypeScript) tool** on the `runtime:
 * subprocess` path. Twin of `QuickJsSensitiveArgsLogRedactionTest` in `:trailblaze-quickjs-tools` —
 * one TS source can deploy to either runtime, so the masking has to hold on both or a credential
 * leaks into the persisted session log (which ships as a CI artifact) depending only on which
 * runtime the author declared.
 *
 * Asserts observable output only (the payload `toLogPayload()` returns). Redaction is log-only, so
 * each case also pins that the real value still reaches the subprocess via `rawToolArguments`.
 */
class SubprocessSensitiveArgsLogRedactionTest {

  private fun fakeSessionProvider(): () -> McpSubprocessSession = {
    error("session not expected to be invoked during log-encoding tests")
  }

  @Test fun `declared sensitive args are masked in the log payload`() {
    val tool = SubprocessTrailblazeTool(
      sessionProvider = fakeSessionProvider(),
      advertisedName = ToolName("myapp_signIn"),
      args = buildJsonObject {
        put("email", "user@example.com")
        put("password", "s3cret-staging-pw")
      },
      sensitiveArgs = DeclaredSensitiveArgs.Named(setOf("password")),
    )

    val payload = tool.toLogPayload()

    assertThat(payload.raw["password"]).isEqualTo(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER))
    assertThat(payload.raw["email"]).isEqualTo(JsonPrimitive("user@example.com"))
  }

  @Test fun `dispatch still receives the real value`() {
    val tool = SubprocessTrailblazeTool(
      sessionProvider = fakeSessionProvider(),
      advertisedName = ToolName("myapp_signIn"),
      args = buildJsonObject { put("password", "s3cret-staging-pw") },
      sensitiveArgs = DeclaredSensitiveArgs.Named(setOf("password")),
    )

    assertThat(tool.rawToolArguments["password"]).isEqualTo(JsonPrimitive("s3cret-staging-pw"))
  }

  @Test fun `a tool declaring nothing sensitive logs its args unchanged`() {
    val tool = SubprocessTrailblazeTool(
      sessionProvider = fakeSessionProvider(),
      advertisedName = ToolName("myapp_tap"),
      args = buildJsonObject { put("password", "not-declared-so-not-masked") },
    )

    assertThat(tool.toLogPayload().raw["password"])
      .isEqualTo(JsonPrimitive("not-declared-so-not-masked"))
  }

  @Test fun `an unreadable declaration masks every arg in the log payload`() {
    // An external MCP server's advertised `_meta` never passes through the descriptor loader's
    // shape validation, so a malformed declaration reaches the runtime. Masking nothing there
    // would leak the credential the author was trying to protect.
    val malformed = TrailblazeToolMeta.fromJsonObject(
      buildJsonObject { put("trailblaze/sensitiveArgNames", "password") },
    )
    val tool = SubprocessTrailblazeTool(
      sessionProvider = fakeSessionProvider(),
      advertisedName = ToolName("myapp_signIn"),
      args = buildJsonObject {
        put("email", "user@example.com")
        put("password", "s3cret-staging-pw")
      },
      sensitiveArgs = malformed.sensitiveArgs,
    )

    val payload = tool.toLogPayload()

    assertThat(payload.raw["password"]).isEqualTo(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER))
    assertThat(payload.raw["email"]).isEqualTo(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER))
    assertThat(tool.rawToolArguments["password"]).isEqualTo(JsonPrimitive("s3cret-staging-pw"))
  }

  @Test fun `registration threads the advertised _meta names onto the decoded tool`() {
    // The production path: an advertised tool's `_meta.trailblaze/sensitiveArgNames` (emitted by the
    // analyzer from the TS spec, or by the `.tool.yaml` shortcut) must reach the decoded tool.
    val advertised = Tool(
      name = "myapp_signIn",
      description = "sign in",
      inputSchema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList()),
      meta = buildJsonObject {
        put("trailblaze/sensitiveArgNames", buildJsonArray { add(JsonPrimitive("password")) })
      },
    )
    val registered = SubprocessToolRegistrar.filterAdvertisedTools(
      tools = listOf(advertised),
      driver = TrailblazeDriverType.DEFAULT_ANDROID,
      preferHostAgent = true,
    ).single()

    val decoded = SubprocessToolRegistration(registered, fakeSessionProvider())
      .decodeToolCall("""{"email":"user@example.com","password":"s3cret"}""")

    assertThat(decoded.toLogPayload().raw["password"])
      .isEqualTo(JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER))
    assertThat((decoded as RawArgumentTrailblazeTool).rawToolArguments["password"])
      .isEqualTo(JsonPrimitive("s3cret"))
  }
}
