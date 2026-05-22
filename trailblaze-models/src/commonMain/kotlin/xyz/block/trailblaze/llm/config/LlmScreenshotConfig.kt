package xyz.block.trailblaze.llm.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeImageFormat

/**
 * Screenshot scaling configuration.
 *
 * Note: when a workspace yaml sets *any* screenshot subfield, unset peer fields fall back
 * to the framework default — not the per-machine `trailblaze config screenshot-*` override.
 * This keeps a committed `trailblaze.yaml` deterministic across developer machines (so two
 * teammates with different local screenshot overrides see the same encoded bytes from the
 * same committed yaml). See `LlmConfigResolver.resolveScreenshotConfig`.
 *
 * Inheritance for unset fields follows the resolver's `base` parameter: a model-level
 * partial override inherits unset fields from the project-level `defaults.screenshot`, and a
 * project-level partial override inherits unset fields from the framework default. The
 * per-machine `trailblaze config screenshot-*` override is **never** consulted for unset
 * fields on a committed yaml — see `LlmConfigResolver.resolveScreenshotConfig`.
 *
 * @property maxDimensions Maximum dimensions as `<longer>x<shorter>` (e.g., `1536x768`).
 *   The first value is the cap for the image's longer side, the second is the cap for the
 *   shorter side — orientation-agnostic, matching the CLI vocabulary
 *   (`trailblaze config screenshot-max-dimensions`) and `ScreenshotScalingConfig.maxDimension1/2`.
 *   Screenshots are scaled down (never up) to fit while maintaining aspect ratio.
 * @property format Encoded image format (`PNG`, `JPEG`, `WEBP`). When null on a model-level
 *   override, falls back to the project-level `defaults.screenshot.format` if set, otherwise
 *   to the framework default (currently WEBP).
 * @property quality Compression quality 0.0..1.0 for lossy formats (JPEG, WEBP). Ignored
 *   for PNG. Out-of-range values are clamped to `0.0..1.0` at resolve time. When null on a
 *   model-level override, falls back to the project-level `defaults.screenshot.quality` if
 *   set, otherwise to the framework default.
 */
@Serializable
data class LlmScreenshotConfig(
  @SerialName("max_dimensions") val maxDimensions: String? = null,
  @SerialName("format") val format: TrailblazeImageFormat? = null,
  @SerialName("quality") val quality: Float? = null,
) {
  /**
   * Parses `max_dimensions` into a pair of (maxDimension1, maxDimension2).
   * Returns null if not set, malformed, or contains non-positive values.
   *
   * Mirrors the positivity guard the CLI enforces in `CONFIG_KEYS["screenshot-max-dimensions"]`
   * so a hand-edited `trails/config/trailblaze.yaml` with `max_dimensions: 0x768` or `-1x768`
   * is rejected here rather than silently propagating non-positive dimensions into downstream
   * scalers.
   */
  fun parseDimensions(): Pair<Int, Int>? {
    val parts = maxDimensions?.split("x", ignoreCase = true) ?: return null
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    if (w <= 0 || h <= 0) return null
    return w to h
  }
}
