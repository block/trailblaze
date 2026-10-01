package xyz.block.trailblaze.mcp.utils

import io.ktor.util.encodeBase64
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.ScreenshotFormat

/**
 * Builder for constructing MCP tool result content.
 *
 * Provides a clean API for building tool results that may include:
 * - Text messages
 * - Screenshots (as base64 text based on session config)
 * - Structured data
 *
 * Note: Currently uses TextContent for all content types. ImageContent support
 * will be added when the MCP SDK exposes it for tool results.
 *
 * Usage:
 * ```kotlin
 * val content = McpContentBuilder(sessionContext)
 *   .addText("Tapped on 'Submit' button")
 *   .addScreenshot(screenshotBytes)
 *   .build()
 * ```
 */
class McpContentBuilder(
  private val sessionContext: TrailblazeMcpSessionContext? = null,
) {
  private val contentList = mutableListOf<TextContent>()

  /**
   * Adds a text content item to the result.
   */
  fun addText(text: String): McpContentBuilder {
    contentList.add(TextContent(text))
    return this
  }

  /**
   * Adds a screenshot to the result based on the session's screenshot format setting.
   *
   * @param screenshotBytes The raw screenshot bytes (format auto-detected from magic bytes)
   * @param mimeType The image MIME type. Defaults to auto-detection via [ImageFormatDetector].
   * @return This builder for chaining
   */
  fun addScreenshot(
    screenshotBytes: ByteArray?,
    mimeType: String = ImageFormatDetector.detectFormat(screenshotBytes ?: ByteArray(0)).mimeType,
  ): McpContentBuilder {
    if (screenshotBytes == null) return this

    val format = sessionContext?.screenshotFormat ?: ScreenshotFormat.NONE

    when (format) {
      ScreenshotFormat.NONE -> {
        // Don't include screenshot
      }

      ScreenshotFormat.IMAGE_CONTENT -> {
        // For now, fall back to base64 text until ImageContent is available
        // TODO: Use MCP's native ImageContent type when SDK supports it
        val base64Data = screenshotBytes.encodeBase64()
        contentList.add(
          TextContent("[Screenshot ($mimeType)]\n$base64Data"),
        )
      }

      ScreenshotFormat.BASE64_TEXT -> {
        // Include as text with base64 data
        val base64Data = screenshotBytes.encodeBase64()
        contentList.add(
          TextContent("[Screenshot ($mimeType)]\n$base64Data"),
        )
      }
    }

    return this
  }

  /**
   * Builds the content list for a CallToolResult.
   */
  fun build(): MutableList<TextContent> = contentList.toMutableList()

  companion object {
    /**
     * Creates a simple text-only result.
     */
    fun textOnly(text: String): MutableList<TextContent> = mutableListOf(TextContent(text))

    /**
     * Creates an error result.
     */
    fun error(message: String): MutableList<TextContent> = mutableListOf(TextContent("Error: $message"))
  }
}
