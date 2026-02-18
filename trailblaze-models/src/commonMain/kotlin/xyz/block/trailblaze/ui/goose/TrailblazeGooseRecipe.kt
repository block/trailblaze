package xyz.block.trailblaze.ui.goose

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDevicePort

/**
 * JSON encoder configured for Goose recipe serialization.
 */
val gooseRecipeJson = Json {
  prettyPrint = true
  encodeDefaults = true
}

/**
 * Creates a Trailblaze extension configuration for Goose recipes.
 * @param port The HTTP port the Trailblaze server is running on.
 */
fun createTrailblazeGooseExtension(port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) = GooseExtension(
  type = "streamable_http",
  name = "trailblaze",
  description = "AI Powered UI Testing Framework and Device Control for Android, iOS and Web",
  uri = "http://localhost:$port/mcp",
)

/**
 * The base Trailblaze extension configuration for Goose recipes (using default port).
 */
val TrailblazeGooseExtension = createTrailblazeGooseExtension()

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
 * @param port The HTTP port the Trailblaze server is running on.
 * @return A new GooseRecipe with the specified activities
 */
fun createGooseRecipe(activities: List<String> = emptyList(), port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT): GooseRecipe {
  val extension = createTrailblazeGooseExtension(port)
  return baseGooseRecipe.copy(activities = activities, extensions = listOf(extension))
}

/**
 * Default open source activities for the Goose recipe.
 */
val defaultOpenSourceActivities = listOf(
  "Add a new alarm for 7:30 AM with the system clock app (com.google.android.deskclock), then delete it.",
  "Open the Contacts App and add a new contact with the name 'Trailblaze' and website https://github.com/block/trailblaze.",
)
