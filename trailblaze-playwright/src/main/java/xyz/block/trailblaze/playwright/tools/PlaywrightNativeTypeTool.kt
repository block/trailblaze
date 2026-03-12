package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("playwright_type")
@LLMDescription(
  """
Type text into a web input element identified by its element ID, ARIA descriptor, or CSS selector.
By default this clears the field first and fills in the new text.
Set clearFirst to false to append text instead.
""",
)
class PlaywrightNativeTypeTool(
  @param:LLMDescription("The text to type into the element.") val text: String,
  @param:LLMDescription(
    "Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'textbox \"Email\"'), " +
      "or CSS selector with css= prefix (e.g., 'css=#email-input').",
  )
  val ref: String,
  @param:LLMDescription("Human-readable description of the element being typed into, for logging.")
  val element: String = "",
  @param:LLMDescription("If true (default), clear the field before typing. If false, append to existing text.")
  val clearFirst: Boolean = true,
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val elementDescriptor: String? get() = element.ifBlank { null }
  override val targetRef: String get() = ref
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeTypeTool(text = text, ref = ref, element = element, clearFirst = clearFirst, reasoning = reasoning, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val interpolatedText = context.memory.interpolateVariables(text)
    val description = element.ifBlank { ref }
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Typing into $description: $interpolatedText")
    return try {
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      if (clearFirst) {
        locator!!.fill(interpolatedText)
      } else {
        locator!!.pressSequentially(interpolatedText)
      }
      val action = if (clearFirst) "Filled" else "Typed"
      TrailblazeToolResult.Success(message = "$action '$interpolatedText' into '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Type failed on '$description': ${e.message}")
    }
  }
}
