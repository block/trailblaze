package xyz.block.trailblaze.llm.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Screenshot scaling configuration.
 *
 * @property maxDimensions Maximum dimensions as `WIDTHxHEIGHT` (e.g., `1536x768`).
 *   Width is the max for the longer side, height for the shorter side.
 *   Screenshots are scaled down (never up) to fit within these dimensions while
 *   maintaining aspect ratio.
 */
@Serializable
data class LlmScreenshotConfig(
  @SerialName("max_dimensions") val maxDimensions: String? = null,
) {
  /**
   * Parses `max_dimensions` into a pair of (maxDimension1, maxDimension2).
   * Returns null if not set or invalid.
   */
  fun parseDimensions(): Pair<Int, Int>? {
    val parts = maxDimensions?.split("x", ignoreCase = true) ?: return null
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    return w to h
  }
}
