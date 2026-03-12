package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@OptIn(ExperimentalTestApi::class)
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
    composeUiTest: ComposeUiTest,
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
      val node =
        if (nthIndex > 0) {
          composeUiTest.onAllNodes(matcher).get(nthIndex)
        } else {
          composeUiTest.onNode(matcher)
        }
      if (clearFirst) {
        node.performTextClearance()
      }
      node.performTextInput(interpolatedText)
      composeUiTest.waitForIdle()
      val action = if (clearFirst) "Filled" else "Typed"
      TrailblazeToolResult.Success(message = "$action '$interpolatedText' into '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Type failed on '$description': ${e.message}")
    }
  }
}
