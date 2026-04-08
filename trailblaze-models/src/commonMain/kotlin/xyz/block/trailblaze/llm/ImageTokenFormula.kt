package xyz.block.trailblaze.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Specifies how to estimate the number of input tokens consumed by an image.
 *
 * Each LLM provider tokenizes images differently. This enum selects the formula
 * used by [LlmTokenBreakdownEstimator] to distribute the LLM-reported total
 * input tokens across categories (system, user, tools, images) for the breakdown
 * visualization.
 *
 * Set via `image_token_formula` in the YAML cost config, or defaults based on
 * the provider type.
 */
@Serializable
enum class ImageTokenFormula {
  /**
   * Anthropic: tokens = (width * height) / 750.
   * Images > 1568px on the long edge are scaled down first.
   * https://docs.anthropic.com/en/docs/build-with-claude/vision#evaluate-image-size
   */
  @SerialName("anthropic") ANTHROPIC,

  /**
   * OpenAI GPT-4.1 (tile-based, high detail):
   * Scale so shortest side <= 768 and longest <= 2048, then count 512x512 tiles.
   * tokens = ceil(w/512) * ceil(h/512) * 170 + 85
   * https://platform.openai.com/docs/guides/images-vision
   */
  @SerialName("openai_tile") OPENAI_TILE,

  /**
   * Google Gemini 3+ (tile-based):
   * Small images (both dims <= 384): 258 tokens.
   * Larger: crop_unit = floor(min(w,h) / 1.5), tiles = ceil(w/cu) * ceil(h/cu) * 258.
   * https://ai.google.dev/gemini-api/docs/image-understanding
   */
  @SerialName("google_tile") GOOGLE_TILE,

  /**
   * Flat estimate of 765 tokens per image regardless of dimensions.
   * Used as a fallback when provider-specific formula is unknown.
   */
  @SerialName("default") DEFAULT;

  /**
   * Estimates the number of input tokens for a single image with the given dimensions.
   *
   * @param width Scaled image width in pixels
   * @param height Scaled image height in pixels
   * @return Estimated token count for one image
   */
  fun estimateTokens(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return DEFAULT_ESTIMATE
    return when (this) {
      ANTHROPIC -> estimateAnthropic(width, height)
      OPENAI_TILE -> estimateOpenAiTile(width, height)
      GOOGLE_TILE -> estimateGoogleTile(width, height)
      DEFAULT -> DEFAULT_ESTIMATE
    }
  }

  companion object {
    const val DEFAULT_ESTIMATE = 765

    // --- Anthropic constants ---
    // https://docs.anthropic.com/en/docs/build-with-claude/vision#evaluate-image-size
    /** Images wider/taller than this are scaled down before tokenization. */
    private const val ANTHROPIC_MAX_EDGE_PX = 1568
    /** Anthropic tokens = (width * height) / 750. */
    private const val ANTHROPIC_PIXELS_PER_TOKEN = 750

    // --- OpenAI GPT-4.1 constants (high-detail mode) ---
    // https://platform.openai.com/docs/guides/images-vision
    /** Short side is scaled to at most 768 px. */
    private const val OPENAI_SHORT_SIDE_MAX_PX = 768
    /** Long side is scaled to at most 2048 px. */
    private const val OPENAI_LONG_SIDE_MAX_PX = 2048
    /** Tile size for the 512×512 grid. */
    private const val OPENAI_TILE_SIZE_PX = 512
    /** Each 512×512 tile costs 170 tokens. */
    private const val OPENAI_TOKENS_PER_TILE = 170
    /** Fixed base cost added once per image. */
    private const val OPENAI_BASE_TOKENS = 85

    // --- Google Gemini 3+ constants ---
    // https://ai.google.dev/gemini-api/docs/image-understanding
    /** Images with both dimensions ≤ 384 px are treated as a single tile. */
    private const val GOOGLE_SMALL_IMAGE_THRESHOLD_PX = 384
    /** Crop unit divisor: crop_unit = floor(min(w,h) / 1.5). */
    private const val GOOGLE_CROP_UNIT_DIVISOR = 1.5
    /** Each tile (or the whole small image) costs 258 tokens. */
    private const val GOOGLE_TOKENS_PER_TILE = 258

    private fun estimateAnthropic(width: Int, height: Int): Int {
      var w = width
      var h = height
      val longEdge = maxOf(w, h)
      if (longEdge > ANTHROPIC_MAX_EDGE_PX) {
        val scale = ANTHROPIC_MAX_EDGE_PX.toDouble() / longEdge
        w = (w * scale).toInt()
        h = (h * scale).toInt()
      }
      return ((w.toLong() * h) / ANTHROPIC_PIXELS_PER_TOKEN).toInt()
    }

    private fun estimateOpenAiTile(width: Int, height: Int): Int {
      var w = width.toDouble()
      var h = height.toDouble()
      val longSide = maxOf(w, h)
      val shortSide = minOf(w, h)
      if (shortSide > OPENAI_SHORT_SIDE_MAX_PX || longSide > OPENAI_LONG_SIDE_MAX_PX) {
        val scaleShort =
          if (shortSide > OPENAI_SHORT_SIDE_MAX_PX) OPENAI_SHORT_SIDE_MAX_PX / shortSide else 1.0
        val scaleLong =
          if (longSide > OPENAI_LONG_SIDE_MAX_PX) OPENAI_LONG_SIDE_MAX_PX / longSide else 1.0
        val scale = minOf(scaleShort, scaleLong)
        w *= scale
        h *= scale
      }
      val tilesW = ceil(w / OPENAI_TILE_SIZE_PX).toInt()
      val tilesH = ceil(h / OPENAI_TILE_SIZE_PX).toInt()
      return tilesW * tilesH * OPENAI_TOKENS_PER_TILE + OPENAI_BASE_TOKENS
    }

    private fun estimateGoogleTile(width: Int, height: Int): Int {
      if (width <= GOOGLE_SMALL_IMAGE_THRESHOLD_PX && height <= GOOGLE_SMALL_IMAGE_THRESHOLD_PX) {
        return GOOGLE_TOKENS_PER_TILE
      }
      val cropUnit = floor(minOf(width, height) / GOOGLE_CROP_UNIT_DIVISOR).toInt()
      if (cropUnit <= 0) return GOOGLE_TOKENS_PER_TILE
      val tilesW = ceil(width.toDouble() / cropUnit).toInt()
      val tilesH = ceil(height.toDouble() / cropUnit).toInt()
      return tilesW * tilesH * GOOGLE_TOKENS_PER_TILE
    }
  }
}
