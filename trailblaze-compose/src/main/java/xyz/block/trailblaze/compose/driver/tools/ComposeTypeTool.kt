package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("compose_type")
@LLMDescription(
  """
Type text into a UI input element.
Identify the element using its element ID from the view hierarchy (e.g., 'e3'),
or by existing text content.
By default this clears the field first. Set clearFirst to false to append text instead.
""",
)
class ComposeTypeTool(
  @param:LLMDescription("The text to type into the element.")
  val text: String,
  @param:LLMDescription("Element ID from the view hierarchy, e.g., 'e3'. Preferred method.")
  val elementId: String? = null,
  @param:LLMDescription("Accessibility identifier of the input element.")
  val testTag: String? = null,
  @param:LLMDescription("The existing text content of the input element.")
  val existingText: String? = null,
  @param:LLMDescription("Human-readable description of the element being typed into, for logging.")
  val element: String = "",
  @param:LLMDescription(
    "If true (default), clear the field before typing. If false, append to existing text."
  )
  val clearFirst: Boolean = true,
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    target: ComposeTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val interpolatedText = context.memory.interpolateVariables(text)
    val description = element.ifBlank { elementId ?: testTag ?: existingText ?: "unknown" }
    Console.log("### Typing into $description: $interpolatedText")
    return try {
      val matcher =
        ComposeExecutableTool.resolveElement(elementId, testTag, existingText, context)
          ?: return TrailblazeToolResult.Error.ExceptionThrown(
            "Must provide elementId, testTag, or existingText to identify the element."
          )
      val nthIndex = ComposeExecutableTool.getNthIndex(elementId, context)
      val node = ComposeExecutableTool.findNode(target, matcher, nthIndex)
      if (clearFirst) {
        target.clearText(node)
      }
      target.typeText(node, interpolatedText)
      target.waitForIdle()
      val action = if (clearFirst) "Filled" else "Typed"
      TrailblazeToolResult.Success(message = "$action '$interpolatedText' into '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Type failed on '$description': ${e.message}")
    }
  }
}
