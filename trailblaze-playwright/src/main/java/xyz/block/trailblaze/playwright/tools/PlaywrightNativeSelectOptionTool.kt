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
@TrailblazeToolClass("web_select_option")
@LLMDescription(
  """
Select one or more options from a <select> dropdown element identified by its element ID, ARIA descriptor, or CSS selector.
Provide the option values or labels to select.
""",
)
class PlaywrightNativeSelectOptionTool(
  @param:LLMDescription(
    "Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'combobox \"Category\"'), " +
      "or CSS selector with css= prefix (e.g., 'css=#my-select').",
  )
  val ref: String? = null,
  @param:LLMDescription("The option values or visible text labels to select.")
  val values: List<String>,
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val targetRef: String? get() = ref
  override val targetNodeSelector: TrailblazeNodeSelector? get() = nodeSelector
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeSelectOptionTool(ref = null, values = values, reasoning = reasoning, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = PlaywrightExecutableTool.describeTarget(nodeSelector, ref)
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Selecting options in $description: $values")
    if (values.isEmpty()) {
      return TrailblazeToolResult.Error.ExceptionThrown("No option values provided to select.")
    }
    return try {
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      locator!!.selectOption(values.toTypedArray())
      TrailblazeToolResult.Success(message = "Selected ${values.joinToString(", ") { "'$it'" }} in '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        "Select option failed on '$description': ${e.message}",
      )
    }
  }
}
