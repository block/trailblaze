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
Type into an input, identified by elementId (preferred), testTag, or existingText. Clears the field first unless clearFirst is false.
""",
)
data class ComposeTypeTool(
  @param:LLMDescription("Text to type.")
  val text: String,
  @param:LLMDescription("Element ID, e.g. 'e3'.")
  val elementId: String? = null,
  @param:LLMDescription("Input testTag.")
  val testTag: String? = null,
  @param:LLMDescription("Input's current text, exact whole-text match.")
  val existingText: String? = null,
  @param:LLMDescription("Short description of the element, for logs.")
  val element: String = "",
  @param:LLMDescription("Clear the field before typing (default true); false appends.")
  val clearFirst: Boolean = true,
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    target: ComposeTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execution, so `text` arrives resolved here.
    val description = element.ifBlank { elementId ?: testTag ?: existingText ?: "unknown" }
    Console.log("### Typing into $description: $text")
    return try {
      val matcher =
        ComposeExecutableTool.resolveElement(elementId, testTag, existingText, context)
          ?: return TrailblazeToolResult.Error.ExceptionThrown(
            "Must provide elementId, testTag, or existingText to identify the element."
          )
      val nthIndex = ComposeExecutableTool.getNthIndex(elementId, context)
      val node = ComposeExecutableTool.findNode(target, matcher, nthIndex)
      target.dispatchAndAwaitSettle {
        if (clearFirst) {
          target.clearText(node)
        }
        target.typeText(node, text)
      }
      val action = if (clearFirst) "Filled" else "Typed"
      TrailblazeToolResult.Success(message = "$action '$text' into '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Type failed on '$description': ${e.message}")
    }
  }
}
