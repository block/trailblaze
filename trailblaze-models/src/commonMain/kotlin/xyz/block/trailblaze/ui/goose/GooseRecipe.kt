package xyz.block.trailblaze.ui.goose

import kotlinx.serialization.Serializable

/**
 * Goose recipe data model for deep linking into the Goose app.
 * See: https://block.github.io/goose/docs/tutorials/recipes-tutorial/
 */
@Serializable
data class GooseRecipe(
  val version: String = "1.0.0",
  val title: String,
  val description: String,
  val instructions: String,
  val extensions: List<GooseExtension> = emptyList(),
  val activities: List<String> = emptyList(),
  val parameters: List<String> = emptyList(),
)
