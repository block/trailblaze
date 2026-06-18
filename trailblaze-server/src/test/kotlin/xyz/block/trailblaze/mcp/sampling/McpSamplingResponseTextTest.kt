package xyz.block.trailblaze.mcp.sampling

import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [extractSamplingResponseText], which adapts the MCP SDK 0.13.0 change where
 * `CreateMessageResult.content` became a `List<SamplingMessageContent>`.
 */
class McpSamplingResponseTextTest {

  @Test
  fun `single text content returns its text`() {
    val text = extractSamplingResponseText(listOf(TextContent(text = "hello world")))
    assertEquals("hello world", text)
  }

  @Test
  fun `multiple text parts are concatenated verbatim with no separator`() {
    val text = extractSamplingResponseText(
      listOf(
        TextContent(text = """{"tool":"tap","""),
        TextContent(text = """"args":{"x":1}}"""),
      ),
    )
    // Chunked text must be reconstructed exactly — an injected separator would corrupt JSON.
    assertEquals("""{"tool":"tap","args":{"x":1}}""", text)
  }

  @Test
  fun `non-text parts are ignored and only text is kept`() {
    val text = extractSamplingResponseText(
      listOf(
        ImageContent(data = "base64==", mimeType = "image/png"),
        TextContent(text = "describe this"),
      ),
    )
    assertEquals("describe this", text)
  }

  @Test
  fun `empty content falls back to the list string representation`() {
    assertEquals("[]", extractSamplingResponseText(emptyList()))
  }

  @Test
  fun `content with only non-text falls back instead of returning empty`() {
    val text = extractSamplingResponseText(
      listOf(ImageContent(data = "base64==", mimeType = "image/png")),
    )
    // No TextContent → joinToString is empty → fallback to toString so the caller still sees
    // *something* (a degenerate response) rather than a silently empty completion.
    assertEquals(true, text.isNotEmpty())
  }
}
