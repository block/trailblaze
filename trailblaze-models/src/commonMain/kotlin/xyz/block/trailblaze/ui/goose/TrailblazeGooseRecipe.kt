package xyz.block.trailblaze.ui.goose

import kotlinx.serialization.json.Json

/**
 * JSON encoder configured for Goose recipe serialization.
 */
val gooseRecipeJson = Json {
  prettyPrint = true
  encodeDefaults = true
}

/**
 * The base Trailblaze extension configuration for Goose recipes.
 */
val TrailblazeGooseExtension = GooseExtension(
  type = "streamable_http",
  name = "trailblaze",
  description = "AI Powered UI Testing Framework and Device Control for Android, iOS and Web",
  uri = "http://localhost:52525/mcp",
)

/**
 * The base Goose recipe for Trailblaze (without activities).
 * Use [createGooseRecipe] to add custom activities.
 */
val baseGooseRecipe = GooseRecipe(
  title = "Trailblaze",
  description = "Trailblaze AI-powered mobile testing framework",
  instructions = """
    Provide Trailblaze with prompts (natural language) instructing Trailblaze what it should do on the device.
    Assume that the user will ask you to do something on the device unless their request matches an available tool.
    Do not use the Computer Control extension.
  """.trimIndent().replace("\n", " "),
  extensions = listOf(TrailblazeGooseExtension),
)

/**
 * Creates a Goose recipe with custom activities.
 * @param activities List of activity descriptions to include in the recipe
 * @return A new GooseRecipe with the specified activities
 */
fun createGooseRecipe(activities: List<String> = emptyList()): GooseRecipe {
  return baseGooseRecipe.copy(activities = activities)
}

/**
 * Default open source activities for the Goose recipe.
 */
val defaultOpenSourceActivities = listOf(
  "Add a new alarm for 7:30 AM with the system clock app (com.google.android.deskclock), then delete it.",
  "Open the Contacts App and add a new contact with the name 'Trailblaze' and website https://github.com/block/trailblaze.",
)
