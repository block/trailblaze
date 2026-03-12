package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Configuration for how screenshots are returned in tool results.
 */
@Serializable
enum class ScreenshotFormat {
  /**
   * Return screenshots as MCP ImageContent type.
   * Preferred for clients that support native image handling.
   */
  IMAGE_CONTENT,

  /**
   * Return screenshots as base64-encoded text.
   * Fallback for clients without native image support.
   */
  BASE64_TEXT,

  /**
   * Don't include screenshots in tool results.
   * Use separate getScreenshot() tool to fetch on-demand.
   */
  NONE,
}
