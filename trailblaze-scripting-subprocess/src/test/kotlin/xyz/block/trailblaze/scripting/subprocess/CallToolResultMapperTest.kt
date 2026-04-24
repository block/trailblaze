package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.Test

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
}
