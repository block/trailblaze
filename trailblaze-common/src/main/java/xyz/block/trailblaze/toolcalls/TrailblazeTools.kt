package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf

object TrailblazeTools {
  const val REQUIRED_TEXT_DESCRIPTION = """
The text to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.
      """

  const val REQUIRED_ACCESSIBILITY_TEXT_DESCRIPTION = """
The accessibilityText to match on. This is required.
NOTE:
- The text can be a regular expression.
- If more than one view matches the text, other optional properties are required to disambiguate.
      """

  fun List<KClass<out TrailblazeTool>>.filterForMapsToMaestroCommands(): List<KClass<out TrailblazeTool>> = this.filter { it.isSubclassOf(MapsToMaestroCommands::class) }
}

// Make this a top-level public function so it can be used elsewhere
@Suppress("UNCHECKED_CAST")
fun TrailblazeTool.getToolNameFromAnnotation(): String = try {
  val kClass = this::class
  val annotation = kClass.findAnnotation<TrailblazeToolClass>()
  annotation?.name ?: kClass.simpleName ?: "UnknownTool"
} catch (e: Exception) {
  this::class.simpleName ?: "UnknownTool"
}
