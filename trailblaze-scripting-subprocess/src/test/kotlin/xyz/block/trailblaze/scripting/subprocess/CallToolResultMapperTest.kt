package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

class CallToolResultMapperTest {

  @Test fun `isError false maps to Success with first text block`() {
    val result = CallToolResult(
      content = listOf(TextContent(text = "User fetched")),
      isError = false,
    )
    val mapped = result.toTrailblazeToolResult()
    assertThat(mapped)
      .isInstanceOf(TrailblazeToolResult.Success::class)
      .prop(TrailblazeToolResult.Success::message)
      .isEqualTo("User fetched")
  }

  @Test fun `missing isError defaults to Success per MCP spec`() {
    val result = CallToolResult(
      content = listOf(TextContent(text = "ok")),
      isError = null,
    )
    assertThat(result.toTrailblazeToolResult())
      .isInstanceOf(TrailblazeToolResult.Success::class)
      .prop(TrailblazeToolResult.Success::message)
      .isEqualTo("ok")
  }

  @Test fun `isError true maps to ExceptionThrown carrying the text message`() {
    val result = CallToolResult(
      content = listOf(TextContent(text = "API request timed out")),
      isError = true,
    )
    val mapped = result.toTrailblazeToolResult()
    assertThat(mapped).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((mapped as TrailblazeToolResult.Error.ExceptionThrown).errorMessage)
      .isEqualTo("API request timed out")
  }

  @Test fun `non-text content blocks are skipped - first TextContent wins`() {
    val result = CallToolResult(
      content = listOf(
        ImageContent(data = "ignored", mimeType = "image/png"),
        TextContent(text = "Actual message"),
        TextContent(text = "Second block"),
      ),
      isError = false,
    )
    assertThat(result.toTrailblazeToolResult())
      .isInstanceOf(TrailblazeToolResult.Success::class)
      .prop(TrailblazeToolResult.Success::message)
      .isEqualTo("Actual message")
  }

  @Test fun `isError true with no text content surfaces a diagnostic message`() {
    val result = CallToolResult(
      content = emptyList(),
      isError = true,
    )
    val mapped = result.toTrailblazeToolResult()
    assertThat(mapped).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
      .prop(TrailblazeToolResult.Error.ExceptionThrown::errorMessage)
      .contains("Subprocess tool returned isError=true")
  }

  @Test fun `Success message is null when no text content is present`() {
    val result = CallToolResult(content = emptyList(), isError = false)
    assertThat(result.toTrailblazeToolResult())
      .isInstanceOf(TrailblazeToolResult.Success::class)
      .prop(TrailblazeToolResult.Success::message)
      .isEqualTo(null)
  }

  @Test fun `Success threads structuredContent through unchanged`() {
    // The MCP SDK's CallToolResult exposes `structuredContent: JsonObject?` for tools that
    // return a typed JSON value (MCP 0.7+). Trailblaze threads it through to
    // `TrailblazeToolResult.Success.structuredContent` so the dispatcher can land it on the
    // `JsScriptingCallbackResult.CallToolResult.structuredContent` wire field — and ultimately
    // the TS SDK's `client.tools.<name>(args)` proxy can unwrap it as the typed `result`.
    val payload = buildJsonObject {
      put("formatted", JsonPrimitive("prefix:msg"))
      put("inputLength", JsonPrimitive(3))
    }
    val result = CallToolResult(
      content = listOf(TextContent(text = "(structured)")),
      isError = false,
      structuredContent = payload,
    )
    val mapped = result.toTrailblazeToolResult()
    assertThat(mapped)
      .isInstanceOf(TrailblazeToolResult.Success::class)
      .prop(TrailblazeToolResult.Success::structuredContent)
      .isEqualTo(payload)
  }

  @Test fun `Success structuredContent stays null when the MCP result omits one`() {
    // Negative companion: existing string-returning tools must not synthesize a stub
    // structuredContent — otherwise every legacy tool would start tripping the TS SDK's
    // unwrap branch and surface null/empty objects in place of the expected text.
    val result = CallToolResult(
      content = listOf(TextContent(text = "plain text")),
      isError = false,
    )
    val mapped = result.toTrailblazeToolResult()
    assertThat(mapped)
      .isInstanceOf(TrailblazeToolResult.Success::class)
      .prop(TrailblazeToolResult.Success::structuredContent)
      .isEqualTo(null)
  }

  @Test fun `isError true with structuredContent maps to ExceptionThrown and drops the payload`() {
    // Pins the design decision documented in [toTrailblazeToolResult]'s error branch:
    // `Error.ExceptionThrown` is text-only by design, so any structuredContent on an
    // isError MCP response is intentionally dropped. If a future refactor extends the
    // Error variant with a structured payload field but forgets to thread it here, this
    // test fails and surfaces the gap — the comment in `CallToolResultMapper` calls out
    // exactly this scenario.
    val payload = buildJsonObject {
      put("errorCode", JsonPrimitive("E_TIMEOUT"))
      put("retryAfterSeconds", JsonPrimitive(30))
    }
    val result = CallToolResult(
      content = listOf(TextContent(text = "tool timed out")),
      isError = true,
      structuredContent = payload,
    )
    val mapped = result.toTrailblazeToolResult()
    assertThat(mapped).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
      .prop(TrailblazeToolResult.Error.ExceptionThrown::errorMessage)
      .isEqualTo("tool timed out")
    // The ExceptionThrown variant has no field to carry structuredContent — verifying the
    // mapped result is the ExceptionThrown shape (not Success-with-structuredContent) is
    // the pin. If a future Error variant gains a structuredContent field, update this test
    // to assert on it explicitly rather than relying on absence.
  }
}
