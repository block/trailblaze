package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeElementSelectorSizeSelector(
  val width: Int? = null,
  val height: Int? = null,
  val tolerance: Int? = null,
)
