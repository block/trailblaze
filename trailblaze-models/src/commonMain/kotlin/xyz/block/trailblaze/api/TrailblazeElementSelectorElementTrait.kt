package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

@Serializable
enum class TrailblazeElementSelectorElementTrait(val description: String) {
  TEXT("Has text"),
  SQUARE("Is square"),
  LONG_TEXT("Has long text"),
}
