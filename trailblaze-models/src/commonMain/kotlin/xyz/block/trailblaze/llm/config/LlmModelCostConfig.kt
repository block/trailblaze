package xyz.block.trailblaze.llm.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.ImageTokenFormula

@Serializable
data class LlmModelCostConfig(
  @SerialName("input_per_million") val inputPerMillion: Double? = null,
  @SerialName("output_per_million") val outputPerMillion: Double? = null,
  @SerialName("cached_input_per_million") val cachedInputPerMillion: Double? = null,
  /** Which formula to use for estimating image token counts. Defaults based on provider type. */
  @SerialName("image_token_formula") val imageTokenFormula: ImageTokenFormula? = null,
)
